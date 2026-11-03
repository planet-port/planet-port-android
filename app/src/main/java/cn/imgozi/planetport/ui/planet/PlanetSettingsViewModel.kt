package cn.imgozi.planetport.ui.planet

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.IdentityStore
import cn.imgozi.planetport.store.PlanetImporter
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StoreRepo
import cn.imgozi.planetport.tunnel.NodeStatusInfo
import cn.imgozi.planetport.tunnel.TunnelController
import cn.imgozi.planetport.tunnel.TunnelStateBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlanetSettingsUiState(
    /** 行星档案（null = 加载中/已不存在） */
    val planet: PlanetProfile? = null,
    /** 隧道当前使用的行星 ID（null 表示从未激活过 → 回退官方语义，同 store） */
    val activePlanetId: String? = null,
    /** 16 位 hex World ID（官方 = 内置 ZT_WORLD_ID_EARTH；私有 = 文件解读，缺失 "—"） */
    val worldId: String = "—",
    /** planet 文件解读信息（官方或文件缺失为 null） */
    val fileInfo: PlanetImporter.PlanetFileInfo? = null,
    /** 本行星节点 ID：从身份文件派生（IdentityStore.planetNodeIds），未生成 "—" */
    val nodeId: String = "—",
)

/**
 * 行星设置页状态。配置读取在 configChanges 后全量重读（level-triggered 契约）；
 * 文件解读（World ID/大小/修订时间）不缓存（iOS 有 worldIdCache，此处文件极小、
 * 重读频率 = 配置变更频率，直读换取实现简单）。
 */
class PlanetSettingsViewModel(
    private val planetId: String,
    application: Application,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(PlanetSettingsUiState())
    val ui: StateFlow<PlanetSettingsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            reload()
            StoreRepo.configChanges.collect { reload() }
        }
        // 本页 rename/updateIcon 写行星表不发 configChanges（iOS 由显式
        // app.reloadConfig() 承接，Android 等价为 UI 级 planetsChanges）
        viewModelScope.launch {
            StoreRepo.planetsChanges.collect { reload() }
        }
    }

    /** 节点快照（激活行星的实时节点 ID 优先用它）。 */
    val nodeStatus: StateFlow<NodeStatusInfo?> get() = TunnelStateBus.nodeStatus

    private suspend fun reload() {
        val store = StoreRepo.store
        val planet = store.readPlanets().firstOrNull { it.id == planetId }
        val importer = StoreRepo.planetImporter
        val info = planet?.let { importer.inspectPlanetFile(it) }
        val worldId = when {
            planet == null -> "—"
            planet.kind == cn.imgozi.planetport.store.PlanetKind.official ->
                "%016x".format(PlanetImporter.ZT_WORLD_ID_EARTH.toLong())
            else -> info?.worldId?.let { "%016x".format(it.toLong()) } ?: "—"
        }
        // 挂起读先算完再赋值：不把挂起调用放进 StateFlow.update 的 CAS 重试循环
        val active = store.activePlanetId()
        // 身份 ID 直连身份文件派生（与首页卡片同源，IdentityStore.planetNodeIds）
        val perPlanet = store.readPrefs().identityPerPlanet
        val nodeId = planet?.let {
            IdentityStore.planetNodeIds(store.paths, listOf(it), perPlanet)[it.id]
        } ?: "—"
        _ui.update {
            it.copy(
                planet = planet,
                activePlanetId = active,
                worldId = worldId,
                fileInfo = info,
                nodeId = nodeId,
            )
        }
    }

    /** 名称变更（私有行星失焦/提交即存）。错误经回调上抛给视图弹窗（对齐 iOS alert）。 */
    fun rename(name: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { StoreRepo.planetImporter.rename(requirePlanet(), name) }
                .onFailure { e ->
                    // 导入器 message 为稳定英文标识串（无 Context 无法本地化），
                    // 已知类型映射资源；其余（写盘失败等）不透出技术串给用户，
                    // 原始异常进日志供诊断（T7b 审查 I-1）
                    if (e is PlanetImporter.ImportError.EmptyName) {
                        onError(getApplication<Application>().getString(R.string.error_planet_emptyName))
                    } else {
                        Log.w(TAG, "rename 失败", e)
                        onError(getApplication<Application>().getString(R.string.planet_renameFailed))
                    }
                }
        }
    }

    /** 图标变更；null = 恢复自动生成。 */
    fun updateIcon(animal: String, color: String?, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { StoreRepo.planetImporter.updateIcon(requirePlanet(), animal, color) }
                .onFailure {
                    // 写盘失败等技术串不透出用户，进日志（T7b 审查 I-1）
                    Log.w(TAG, "updateIcon 失败", it)
                    onError(getApplication<Application>().getString(R.string.planet_iconUpdateFailed))
                }
        }
    }

    /**
     * 删除顺序（对齐 iOS 2488ced 三段式）：行星使用中时先写回 lastActivated=false
     * 断开该行星全部网络（writeNetworks → notifyConfigChanged → 隧道 applyConfig
     * diff 逐网 leave），再 stop + awaitSettled 停用行星等停机终态（给隧道留出
     * 处理 leave 的时间窗），最后级联删除配置（deletePlanet：网络/卫星/文件/
     * 状态目录 + 指针回退官方 + notifyConfigChanged）。未使用中直接删除。
     *
     * 门控核对（沿用旧结论）：
     * - 删非激活行星且隧道在跑：不停——核心只在启动时装载激活行星的 planet 文件，
     *   applyConfig 只重读激活行星的网络/月壳（本次删除未触碰），停机反而误伤
     *   在跑连接。
     * - 删激活行星：三段式后不自动重启——隧道停在已停止，回退官方由下次启动
     *   装载（planetDir 在服务启动期固化）。
     * - awaitSettled 已是终态时立即返回（隧道本就未跑的删除路径不挂起）；
     *   超时返回 false 不阻断删除（进程级异常场景，交由用户手动处理）。
     * [onDeleted] 供视图退栈。
     */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val planet = _ui.value.planet ?: return@launch
            if (planet.id == _ui.value.activePlanetId) {
                // 1. 断开所有网络：写回触发隧道 diff 逐网 leave
                val networks = StoreRepo.store.readNetworks(planetId = planet.id)
                if (networks.any { it.lastActivated }) {
                    StoreRepo.store.writeNetworks(
                        networks.map { it.copy(lastActivated = false) },
                        planetId = planet.id,
                    )
                }
                // 2. 停用行星，等停机终态（stop 幂等，终态 no-op）
                TunnelController.stop(getApplication())
                TunnelController.awaitSettled()
            }
            // 3. 级联删除。失败只记日志不崩进程（对齐 HomeViewModel storeWrite
            // 兜底范式）：行星留档，列表下次重读自然还原；退栈照旧，不因低概率
            // 磁盘故障阻断导航
            runCatching { StoreRepo.store.deletePlanet(planet) }
                .onFailure { Log.w(TAG, "deletePlanet 写盘失败", it) }
            onDeleted()
        }
    }

    private fun requirePlanet(): PlanetProfile =
        _ui.value.planet ?: throw IllegalStateException("行星不存在")

    private companion object {
        const val TAG = "PlanetSettingsVM"
    }
}
