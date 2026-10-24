package cn.imgozi.planetport.ui.planet

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.imgozi.planetport.R
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
        _ui.update {
            it.copy(
                planet = planet,
                activePlanetId = active,
                worldId = worldId,
                fileInfo = info,
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
    fun updateIcon(symbol: String?, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { StoreRepo.planetImporter.updateIcon(requirePlanet(), symbol) }
                .onFailure {
                    // 写盘失败等技术串不透出用户，进日志（T7b 审查 I-1）
                    Log.w(TAG, "updateIcon 失败", it)
                    onError(getApplication<Application>().getString(R.string.planet_iconUpdateFailed))
                }
        }
    }

    /**
     * 删除顺序对齐 iOS：激活中先停 VPN（TunnelController.stop），级联清理由
     * deletePlanet 完成（网络/卫星/文件/状态目录 + 指针回退官方）。[onDeleted] 供视图退栈。
     */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val planet = _ui.value.planet ?: return@launch
            if (planet.id == _ui.value.activePlanetId) {
                TunnelController.stop(getApplication())
            }
            StoreRepo.store.deletePlanet(planet)
            onDeleted()
        }
    }

    private fun requirePlanet(): PlanetProfile =
        _ui.value.planet ?: throw IllegalStateException("行星不存在")

    private companion object {
        const val TAG = "PlanetSettingsVM"
    }
}
