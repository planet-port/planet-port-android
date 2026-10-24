package cn.imgozi.planetport.ui.moon

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.imgozi.planetport.store.MoonEntry
import cn.imgozi.planetport.store.MoonImporter
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StoreRepo
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

/** 添加卫星结果（手动入轨与文件导入共用；[Duplicate] = 已有同 worldId 条目，不重复写）。 */
sealed interface AddMoonResult {
    data object Added : AddMoonResult
    data object Duplicate : AddMoonResult
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

    private val _moons = MutableStateFlow<List<MoonRow>>(emptyList())
    val moons: StateFlow<List<MoonRow>> = _moons.asStateFlow()

    /** 首次读盘完成（此前不渲染空态，避免「空态 → 列表」闪变）。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _planetName = MutableStateFlow(PlanetProfile.OFFICIAL.name)
    val planetName: StateFlow<String> = _planetName.asStateFlow()

    init {
        viewModelScope.launch {
            refresh()
            // level-triggered 契约：任何配置变更（含本页写入触发的）全量重读，
            // 缓存文件在位状态随之刷新
            StoreRepo.configChanges.collect { refresh() }
        }
    }

    /** moons.d 缓存文件路径（对齐 iOS MoonEntry.cacheFileURL：%016x.moon）。 */
    fun cacheFile(entry: MoonEntry): File =
        File(File(store.paths.planetStateDir(entry.planetId ?: planetId), "moons.d"), entry.id + ".moon")

    /**
     * 手动入轨：按 worldId 查重，已有同 id 条目时不重复写（返回 [AddMoonResult.Duplicate]）。
     * 成功写入经 configChanges → 隧道侧增量 orbit（运行中即时生效）。
     */
    suspend fun addManual(worldId: ULong, seed: ULong): AddMoonResult {
        val existing = store.readMoons(planetId)
        if (existing.any { it.worldId == worldId }) return AddMoonResult.Duplicate
        store.writeMoons(
            existing + MoonEntry(worldId = worldId, seed = seed, fromFile = false),
            planetId,
        )
        return AddMoonResult.Added
    }

    /**
     * 文件导入：读 Uri → MoonImporter 校验落盘（缓存文件幂等写入，重复导入无害）
     * → 查重 → 写记录。缓存文件先于查重落盘是有意的：即便条目已存在，
     * 重新校验过的文件也顺手修复了缓存。
     */
    suspend fun importFromFile(uri: Uri): AddMoonResult {
        val bytes = withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        } ?: return AddMoonResult.Failed(MoonImporter.ImportError.ReadFailed().message ?: "文件读取失败")

        val entry = try {
            StoreRepo.moonImporter.importMoonData(bytes)
        } catch (e: MoonImporter.ImportError) {
            return AddMoonResult.Failed(e.message ?: "moon 文件导入失败")
        }

        val existing = store.readMoons(planetId)
        if (existing.any { it.worldId == entry.worldId }) return AddMoonResult.Duplicate
        store.writeMoons(existing + entry, planetId)
        return AddMoonResult.Added
    }

    /**
     * 删除入轨：只删记录不 deorbit（对齐 iOS deleteOrbit）。隧道侧 orbit 集合
     * 只增量不清除，变更须重启隧道才真正落地——隧道在跑时经 [TunnelController.restartIfRunning]
     * 自动停 1s 再启，UI 层提示生效说明。
     */
    suspend fun deleteOrbit(entry: MoonEntry) {
        store.deleteMoon(entry.worldId, planetId)
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
        _planetName.value = store.readPlanets().firstOrNull { it.id == planetId }?.name
            ?: PlanetProfile.OFFICIAL.name
        _loaded.value = true
    }

    companion object {
        fun factory(planetId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("APPLICATION_KEY 缺失")
                MoonViewModel(app, planetId)
            }
        }
    }
}
