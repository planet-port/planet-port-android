package cn.imgozi.planetport.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 数据层文件布局（filesRoot 通常为 context.filesDir；自检页用 cacheDir 子目录隔离）。 */
class StorePaths(private val filesRoot: File) {
    val configDir: File = File(filesRoot, "config")
    val planetsDir: File = File(filesRoot, "planets")
    val planetsFile: File = File(configDir, "planets.json")
    val networksFile: File = File(configDir, "networks.json")
    val moonsFile: File = File(configDir, "moons.json")
    val prefsFile: File = File(configDir, "prefs.json")

    /** 某个 Planet 的核心状态目录（identity.*、moons.d/ 等，P2 接核心时启用）。 */
    fun planetStateDir(planetId: String): File = File(filesRoot, "zt/$planetId")
}

/**
 * 四份 JSON 的域 API。语义对齐 iOS AppConfigStore：
 * - 空表/缺失一律回退 [PlanetProfile.OFFICIAL]，writePlanets 保证 official 永在且最前；
 * - selected 仅是浏览上下文（静默）；active 是隧道实际使用的指针（P2 负责重启联动）；
 * - writeNetworks/writeMoons 按归属 Planet 整组替换并盖戳 planetId。
 *
 * 并发模型：公开变更方法在 opMutex 内完成整段读-改-写（跨多次文件 IO 的
 * 复合操作串行化，防止交错覆盖丢更新）；复合操作的外层组合（如导入器的
 * 读-追加-写）经 [mutate] 进入同一把锁。单次文件读写由 JsonFileStore 的
 * 文件锁保证原子性；锁序恒为 opMutex → 文件锁，不反向嵌套（*Locked
 * 变体不取 opMutex，供锁内复用；读方法不取 opMutex）。
 */
class AppConfigStore(val paths: StorePaths) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val files = JsonFileStore(json)

    /** 域级复合操作互斥。 */
    private val opMutex = Mutex()

    /** 配置变更信号（P2 订阅触发重配）。有损合并：extraBufferCapacity=16 溢出即丢（对齐 iOS Darwin 通知
     *  语义），订阅方必须 level-triggered——收到信号即全量重读文件，不得假设逐条精确。
     *  进程级共享：UI 与隧道可能各持实例（P2SelfCheck 写入 / TunnelManager 订阅），
     *  事件必须跨实例互通，故流本体挂在 companion，实例属性转发。 */
    val configChanges: SharedFlow<Unit> get() = _configChanges

    private val planetList = ListSerializer(PlanetProfile.serializer())
    private val networkList = ListSerializer(NetworkEntry.serializer())
    private val moonList = ListSerializer(MoonEntry.serializer())

    /** 在域锁内执行一段复合读-改-写（导入器等外部组合方使用；block 内禁调公开变更方法）。 */
    suspend fun <T> mutate(block: suspend () -> T): T = opMutex.withLock { block() }

    // MARK: - Planet

    suspend fun readPlanets(): List<PlanetProfile> {
        bootstrap()
        return readPlanetsFile().orEmpty().ifEmpty { listOf(PlanetProfile.OFFICIAL) }
    }

    suspend fun writePlanets(profiles: List<PlanetProfile>) = mutate {
        writePlanetsLocked(profiles)
    }

    /** 不取域锁的写档（仅在已持有 opMutex 的复合段内使用，如导入器的读-追加-写）。 */
    internal suspend fun writePlanetsLocked(profiles: List<PlanetProfile>) {
        // official 永在且排最前；档案本体以既有条目为准（保留 lastNodeId 等字段变更），缺失才用固定档案兜底
        val official = profiles.firstOrNull { it.id == PlanetProfile.OFFICIAL_ID } ?: PlanetProfile.OFFICIAL
        val normalized = listOf(official) + profiles.filterNot { it.id == PlanetProfile.OFFICIAL_ID }
        files.write(paths.planetsFile, normalized, planetList)
    }

    suspend fun selectedPlanetId(): String {
        val prefs = readPrefs()
        val ids = readPlanets().map { it.id }
        return if (prefs.selectedPlanetId in ids) prefs.selectedPlanetId else PlanetProfile.OFFICIAL_ID
    }

    suspend fun activePlanetId(): String {
        val prefs = readPrefs()
        val stored = prefs.activePlanetId ?: return PlanetProfile.OFFICIAL_ID
        val ids = readPlanets().map { it.id }
        return if (stored in ids) stored else PlanetProfile.OFFICIAL_ID
    }

    /** 是否激活过任何 Planet（activePlanetId 回退 official 仅用于显示，"用过"语义以此区分）。 */
    suspend fun hasActivatedPlanet(): Boolean = readPrefs().activePlanetId != null

    /** 只改变浏览上下文：不发变更通知，更不触发隧道。 */
    suspend fun selectPlanet(id: String) = mutate {
        selectPlanetLocked(id)
    }

    private suspend fun selectPlanetLocked(id: String) {
        if (readPlanets().none { it.id == id }) return
        writePrefsLocked(readPrefs().copy(selectedPlanetId = id))
    }

    /** 设置隧道下次启动要加载的 Planet；只更新指针，重启联动由调用方（P2）负责。 */
    suspend fun activatePlanet(id: String): Boolean = mutate {
        activatePlanetLocked(id)
    }

    private suspend fun activatePlanetLocked(id: String): Boolean {
        if (readPlanets().none { it.id == id }) return false
        val changed = activePlanetId() != id
        writePrefsLocked(readPrefs().copy(activePlanetId = id))
        return changed
    }

    /** 删除私有 Planet 及其名下 Network/Moon/文件/状态目录；被删者正在选中或生效则回退官方。 */
    suspend fun deletePlanet(profile: PlanetProfile) = mutate {
        if (profile.kind != PlanetKind.custom) return@mutate
        val wasSelected = selectedPlanetId() == profile.id
        val wasActive = activePlanetId() == profile.id

        writePlanetsLocked(readPlanets().filterNot { it.id == profile.id })
        files.write(paths.networksFile, readNetworksFile().orEmpty().filterNot { it.planetId == profile.id }, networkList)
        files.write(paths.moonsFile, readMoonsFile().orEmpty().filterNot { it.planetId == profile.id }, moonList)

        withContext(Dispatchers.IO) {
            profile.fileName?.let { File(paths.planetsDir, it).delete() }
            paths.planetStateDir(profile.id).deleteRecursively()
        }

        if (wasSelected) selectPlanetLocked(PlanetProfile.OFFICIAL_ID)
        if (wasActive) activatePlanetLocked(PlanetProfile.OFFICIAL_ID)
        notifyConfigChanged()
    }

    // MARK: - Network（按 Planet 归组）

    suspend fun readNetworks(planetId: String? = null): List<NetworkEntry> {
        val id = planetId ?: selectedPlanetId()
        return readNetworksFile().orEmpty().filter { it.planetId == id }
    }

    /** 一次读盘按 Planet 分组（供首页各分页并行渲染；planetId 为 null 的遗留项排除）。 */
    suspend fun readNetworksByPlanet(): Map<String, List<NetworkEntry>> {
        return readNetworksFile().orEmpty()
            .filter { it.planetId != null }
            .groupBy { it.planetId!! }
    }

    suspend fun readActiveNetworks(): List<NetworkEntry> = readNetworks(planetId = activePlanetId())

    suspend fun writeNetworks(values: List<NetworkEntry>, planetId: String? = null) = mutate {
        val id = planetId ?: selectedPlanetId()
        val all = readNetworksFile().orEmpty().filterNot { it.planetId == id } +
            values.map { it.copy(planetId = id) }
        files.write(paths.networksFile, all, networkList)
        notifyConfigChanged()
    }

    // MARK: - Moon（按 Planet 归组）

    suspend fun readMoons(planetId: String? = null): List<MoonEntry> {
        val id = planetId ?: selectedPlanetId()
        return readMoonsFile().orEmpty().filter { it.planetId == id }
    }

    suspend fun readAllMoons(): List<MoonEntry> = readMoonsFile().orEmpty()

    suspend fun readActiveMoons(): List<MoonEntry> = readMoons(planetId = activePlanetId())

    suspend fun writeMoons(values: List<MoonEntry>, planetId: String? = null) = mutate {
        val id = planetId ?: selectedPlanetId()
        val all = readMoonsFile().orEmpty().filterNot { it.planetId == id } +
            values.map { it.copy(planetId = id) }
        files.write(paths.moonsFile, all, moonList)
        notifyConfigChanged()
    }

    /** 应用启动且隧道未连接时，把所有已连接标记复位（调用时机由 P2/P5 决定）。静默。 */
    suspend fun resetAllNetworkActivation() = mutate {
        val all = readNetworksFile().orEmpty()
        if (all.none { it.lastActivated }) return@mutate
        files.write(paths.networksFile, all.map { it.copy(lastActivated = false) }, networkList)
    }

    /** 数据面是否禁用 IPv6（对齐 iOS network_disable_ipv6；写入口见 [writePrefs]） */
    suspend fun disableIpv6(): Boolean = readPrefs().disableIpv6

    /** 删除单个网络条目（读全量 → 过滤 → 写回），通知语义与 writeNetworks 一致。
     *  networkId 须与存储一致（16 位小写十六进制）；只删归属 Planet 的条目，
     *  其它 Planet 下同 ID 网络不受影响。 */
    suspend fun deleteNetwork(networkId: String, planetId: String? = null) = mutate {
        val id = planetId ?: selectedPlanetId()
        files.write(
            paths.networksFile,
            readNetworksFile().orEmpty().filterNot { it.planetId == id && it.networkId == networkId },
            networkList,
        )
        notifyConfigChanged()
    }

    /** 删除单条 Moon 入轨记录（读全量 → 过滤 → 写回），通知语义与 writeMoons 一致。
     *  只删记录不 deorbit（对齐 iOS MoonView 删除入轨语义）。 */
    suspend fun deleteMoon(worldId: ULong, planetId: String? = null) = mutate {
        val id = planetId ?: selectedPlanetId()
        files.write(
            paths.moonsFile,
            readMoonsFile().orEmpty().filterNot { it.planetId == id && it.worldId == worldId },
            moonList,
        )
        notifyConfigChanged()
    }

    // MARK: - Prefs

    /** 读取 prefs（文件缺失/损坏隔离时回退内存默认）。 */
    suspend fun readPrefs(): AppPrefs =
        files.read(paths.prefsFile, AppPrefs.serializer()) ?: AppPrefs()

    /**
     * 偏好写入入口（读-改-写整段持域锁）：`writePrefs { it.copy(disableIpv6 = true) }`。
     * 静默（不发 configChanges）——隧道侧唯一消费项 disableIpv6 由 UI 经
     * TunnelController.restartIfRunning 显式热重启（对齐 iOS PrefsView 语义），
     * 其余偏好均为 UI 本地态，与隧道无关。
     */
    suspend fun writePrefs(mutate: (AppPrefs) -> AppPrefs) = opMutex.withLock {
        writePrefsLocked(mutate(readPrefs()))
    }

    // MARK: - 文件层

    private suspend fun readPlanetsFile(): List<PlanetProfile>? = files.read(paths.planetsFile, planetList)
    private suspend fun readNetworksFile(): List<NetworkEntry>? = files.read(paths.networksFile, networkList)
    private suspend fun readMoonsFile(): List<MoonEntry>? = files.read(paths.moonsFile, moonList)

    /** 不取域锁的写档（仅在已持有 opMutex 的复合段内使用，如 selectPlanetLocked）。 */
    private suspend fun writePrefsLocked(prefs: AppPrefs) {
        files.write(paths.prefsFile, prefs, AppPrefs.serializer())
    }

    /**
     * 首启引导：planets.json 不存在且无 .corrupt 损坏隔离时写入官方默认；
     * prefs.json 不引导落盘（readPrefs 缺失时回退内存默认，首次真实写才创建）。
     * .corrupt 存在 = 上次现场待排查，不自动重建（读到 null 走内存默认）。
     */
    private suspend fun bootstrap() = withContext(Dispatchers.IO) {
        if (!paths.planetsFile.exists() && !File(paths.planetsFile.path + ".corrupt").exists()) {
            files.write(paths.planetsFile, listOf(PlanetProfile.OFFICIAL), planetList)
        }
    }

    private fun notifyConfigChanged() {
        _configChanges.tryEmit(Unit)
    }

    private companion object {
        private val _configChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    }
}
