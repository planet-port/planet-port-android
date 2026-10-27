package cn.imgozi.planetport.store

import android.content.Context
import kotlinx.coroutines.flow.SharedFlow

/**
 * store 层进程单例门面（收敛既有「各处直构 AppConfigStore」的分散实例）。
 *
 * 背景：AppConfigStore 的 opMutex 是实例锁，而 configChanges 流挂在 companion——
 * 多实例并存时复合操作（读-改-写）不互斥，可能交错覆盖丢更新，故全进程收敛为
 * 唯一实例；导入器同样只持这一份。
 *
 * [init] 在 Application/MainActivity onCreate 首处调用（幂等，以首次 filesDir 为准）。
 * 未初始化即访问是编程错误：直接抛出（fail fast），不静默再建实例破坏单例前提。
 *
 * configChanges 有损合并契约不变：extraBufferCapacity=16 溢出即丢（对齐 iOS Darwin
 * 通知语义），订阅方必须 level-triggered——收到信号即全量重读文件，不得假设逐条精确。
 */
object StoreRepo {

    @Volatile private var storeRef: AppConfigStore? = null

    @Volatile private var planetImporterRef: PlanetImporter? = null

    @Volatile private var moonImporterRef: MoonImporter? = null

    /** 幂等；以首次初始化的 filesDir 为准（进程内存储路径不二次变更）。 */
    fun init(context: Context) {
        if (storeRef != null) return
        synchronized(this) {
            if (storeRef != null) return
            val s = AppConfigStore(StorePaths(context.applicationContext.filesDir))
            storeRef = s
            planetImporterRef = PlanetImporter(s)
            moonImporterRef = MoonImporter(s)
        }
    }

    val store: AppConfigStore
        get() = checkNotNull(storeRef) { "StoreRepo 未初始化：须先调用 StoreRepo.init(context)" }

    val planetImporter: PlanetImporter
        get() = checkNotNull(planetImporterRef) { "StoreRepo 未初始化：须先调用 StoreRepo.init(context)" }

    val moonImporter: MoonImporter
        get() = checkNotNull(moonImporterRef) { "StoreRepo 未初始化：须先调用 StoreRepo.init(context)" }

    /** 配置变更信号（转发唯一实例；有损合并契约见类注释）。 */
    val configChanges: SharedFlow<Unit> get() = store.configChanges

    /** UI 级行星表变更信号（转发唯一实例；语义见 AppConfigStore.planetsChanges）。 */
    val planetsChanges: SharedFlow<Unit> get() = store.planetsChanges
}
