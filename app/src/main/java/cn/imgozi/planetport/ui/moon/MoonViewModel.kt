package cn.imgozi.planetport.ui.moon

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.MoonEntry
import cn.imgozi.planetport.store.MoonImporter
import cn.imgozi.planetport.store.PlanetKind
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StoreRepo
import cn.imgozi.planetport.store.StreamTooLargeException
import cn.imgozi.planetport.store.readBounded
import cn.imgozi.planetport.tunnel.TunnelController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Moon 行展示模型：条目 + moons.d 缓存文件在位状态（缓存徽标「已缓存/未缓存」）。 */
data class MoonRow(val entry: MoonEntry, val cached: Boolean)

/** 添加卫星结果（手动入轨与文件导入共用）。同 worldId 为替换语义，不区分重复。 */
sealed interface AddMoonResult {
    data object Added : AddMoonResult
    data class Failed(val message: String) : AddMoonResult
}

/**
 * Moon 管理页状态（对齐 iOS MoonView + AppState.moons）：
 * 列表按 [planetId] 归属读取；缓存徽标按 moons.d/ 下文件在位实时判定。
 *
 * 前提约定：导航传入的 [planetId] 即当前浏览行星（= StoreRepo selected），与
 * MoonImporter 的落盘目录语义一致（iOS 同构：MoonView 展示的正是 selected 的 moons）。
 */
class MoonViewModel(application: Application, private val planetId: String) : AndroidViewModel(application) {

    private val store get() = StoreRepo.store

    /** VM 侧资源解析（非 Composable 上下文）。 */
    private fun string(resId: Int): String = getApplication<Application>().getString(resId)

    /**
     * 行星展示名：官方行星存储名不翻译，显示侧映射本地化文案
     * （对齐 iOS PlanetProfile.displayName；档案缺失时按官方语义回退）。
     */
    private fun displayPlanetName(planet: PlanetProfile?): String = when {
        planet == null || planet.kind == PlanetKind.official -> string(R.string.planet_official_name)
        else -> planet.name
    }

    private val _moons = MutableStateFlow<List<MoonRow>>(emptyList())
    val moons: StateFlow<List<MoonRow>> = _moons.asStateFlow()

    /** 首次读盘完成（此前不渲染空态，避免「空态 → 列表」闪变）。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _planetName = MutableStateFlow(displayPlanetName(null))
    val planetName: StateFlow<String> = _planetName.asStateFlow()

    init {
        viewModelScope.launch {
            // 先建立订阅再刷新：configChanges 是无 replay 的 SharedFlow，
            // 若刷新先行，刷新期间到达的变更事件会丢失
            launch {
                // level-triggered 契约：任何配置变更（含本页写入触发的）全量重读，
                // 缓存文件在位状态随之刷新
                StoreRepo.configChanges.collect { refresh() }
            }
            refresh()
        }
    }

    /** moons.d 缓存文件路径（对齐 iOS MoonEntry.cacheFileURL：%016x.moon）。 */
    fun cacheFile(entry: MoonEntry): File =
        File(File(store.paths.planetStateDir(entry.planetId ?: planetId), "moons.d"), entry.id + ".moon")

    /**
     * 手动入轨：对齐 iOS addMoon（removeAll + append），同 worldId 直接替换不查重——
     * 后续导入可升级既有条目。成功写入经 configChanges → 隧道侧增量 orbit（运行中即时生效）。
     */
    suspend fun addManual(worldId: ULong, seed: ULong): AddMoonResult = runCatching {
        val existing = store.readMoons(planetId)
        store.writeMoons(
            existing.filterNot { it.worldId == worldId } +
                MoonEntry(worldId = worldId, seed = seed, fromFile = false),
            planetId,
        )
        AddMoonResult.Added
    }.getOrElse { AddMoonResult.Failed(string(R.string.common_operationRetry)) }

    /**
     * 文件导入：限量读 Uri（超 [MoonImporter.MAX_MOON_BYTES] 抛 [MoonImporter.ImportError.TooLarge]，
     * 避免全量 readBytes 撑爆内存）→ MoonImporter 校验落盘（缓存文件幂等写入，重复导入
     * 顺手修复缓存）→ 写记录。对齐 iOS addMoon，同 worldId 直接替换不查重。
     */
    suspend fun importFromFile(uri: Uri): AddMoonResult = runCatching {
        val bytes = withContext(Dispatchers.IO) { readMoonBytes(uri) }
            ?: return@runCatching AddMoonResult.Failed(string(R.string.error_moon_readFailed))
        val entry = StoreRepo.moonImporter.importMoonData(bytes)
        val existing = store.readMoons(planetId)
        store.writeMoons(existing.filterNot { it.worldId == entry.worldId } + entry, planetId)
        AddMoonResult.Added
    }.getOrElse { e ->
        // store 层 message 为稳定英文标识串（导入器无 Context 无法本地化），按
        // 异常类型映射资源；未识别类型回退通用文案
        val message = when (e as? MoonImporter.ImportError) {
            is MoonImporter.ImportError.BadHeader -> string(R.string.error_moon_badHeader)
            is MoonImporter.ImportError.ReadFailed -> string(R.string.error_moon_readFailed)
            is MoonImporter.ImportError.TooLarge -> string(R.string.error_moon_tooLarge)
            is MoonImporter.ImportError.WriteFailed -> string(R.string.error_moon_writeFailed)
            null -> string(R.string.moon_importFailed)
        }
        AddMoonResult.Failed(message)
    }

    /**
     * 流式限量读取：共享 [readBounded] 分块累计，超过 [MoonImporter.MAX_MOON_BYTES]
     * 即抛 [StreamTooLargeException] 终止。读取失败（无法打开流等）返回 null。
     */
    private fun readMoonBytes(uri: Uri): ByteArray? = try {
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            input.readBounded(MoonImporter.MAX_MOON_BYTES)
        }
    } catch (_: StreamTooLargeException) {
        // 归一为导入器 TooLarge，沿用 importFromFile 既有 error_moon_tooLarge 映射链
        throw MoonImporter.ImportError.TooLarge()
    } catch (_: Exception) {
        null
    }

    /**
     * 删除入轨：只删记录不 deorbit（对齐 iOS deleteOrbit）。隧道侧 orbit 集合
     * 只增量不清除，变更须重启隧道才真正落地——隧道在跑时经 [TunnelController.restartIfRunning]
     * 自动停 1s 再启，UI 层提示生效说明。
     */
    suspend fun deleteOrbit(entry: MoonEntry) {
        // 写盘失败只记日志不崩进程（对齐 HomeViewModel storeWrite 兜底范式，
        // JsonFileStore.rename 失败抛 IOException 会直通崩溃）：记录未删成时不
        // 重启隧道，列表态由 configChanges 收敛
        runCatching { store.deleteMoon(entry.worldId, planetId) }
            .onFailure {
                Log.w(TAG, "deleteMoon 写盘失败", it)
                return
            }
        TunnelController.restartIfRunning(getApplication())
    }

    /** 删除缓存文件（仅文件导入的条目提供入口）：删 moons.d/ 下文件并刷新徽标。 */
    suspend fun deleteCacheFile(entry: MoonEntry) {
        withContext(Dispatchers.IO) { cacheFile(entry).delete() }
        refresh()
    }

    private suspend fun refresh() {
        val entries = store.readMoons(planetId)
        _moons.value = withContext(Dispatchers.IO) {
            entries.map { MoonRow(it, cacheFile(it).exists()) }
        }
        _planetName.value = displayPlanetName(store.readPlanets().firstOrNull { it.id == planetId })
        _loaded.value = true
    }

    companion object {
        private const val TAG = "MoonViewModel"

        fun factory(planetId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY 缺失")
                MoonViewModel(app, planetId)
            }
        }
    }
}
