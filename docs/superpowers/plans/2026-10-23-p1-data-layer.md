# P1 数据层实施计划（Planet Port Android）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立对齐 iOS 端 planet-port-ios 的数据层：四份 JSON 配置（planets/networks/moons/prefs）的原子读写设施、Planet/Moon 导入解析、切换与删除级联，并在真机自检页验证导入/切换/持久化正确。

**Architecture:** 全部落在 `app` 模块 `cn.imgozi.planetport.store` 包：`Models.kt`（kotlinx-serialization 数据模型，字段名与 iOS Codable 默认一致）+ `JsonFileStore.kt`（suspend + 全局 Mutex + tmp/rename 原子写 + 损坏隔离 .corrupt）+ `AppConfigStore.kt`（域 API 与 prefs 指针）+ `PlanetImporter.kt` / `MoonImporter.kt`（二进制头解析与落盘）。UI 侧把 P0 验证页升级为数据层自检页（跑在 cacheDir 隔离目录，不污染真实配置）。设计文档 §5 的 AppConfigStore 职责拆为 JsonFileStore（通用文件设施）+ AppConfigStore（域 API）两个文件，结构意图不变。

**Tech Stack:** Kotlin 2.2.21 / kotlinx-serialization-json 1.9.0（含 serialization 编译器插件）/ kotlinx-coroutines-android 1.10.2 / 既有 Compose 骨架。工具链与 P0 完全一致。

## Global Constraints

- **唯一参考源为 iOS 姊妹项目 planet-port-ios**（原目录名 planet-port-i，2026-10-23 更名；数据结构与语义 1:1 对齐，代码在本工程从零编写，GPL 血统零引入）。参考文件： `Shared/Models.swift` 、 `Shared/AppConfigStore.swift` 、 `Shared/AppGroup.swift` 、 `PlanetPort/PlanetImporter.swift` 、 `PlanetPort/MoonImporter.swift`
- **不写测试用例**；验证 = 编译 + 真机冒烟（Mi Note 3，serial `9311902b`）
- **JSON 字段名与枚举原始值与 iOS Codable 默认完全一致**：PlanetProfile{`id`,`name`,`kind`,`fileName`,`icon`,`lastNodeId`}；NetworkEntry{`networkId`,`networkName`,`lastActivated`,`routeViaZeroTier`,`dnsMode`,`customDns`,`planetId`,`lastConflict`}；MoonEntry{`worldId`,`seed`,`fromFile`,`planetId`}；枚举 DnsMode=`none|network|custom`、PlanetKind=`official|custom`
- **二进制格式常量**：planet 文件首字节 `0x01`；moon 文件首字节 `0x7f`；worldId = 偏移 1 起 8 字节大端；planet 时间戳 = 偏移 9 起 8 字节大端（文件 ≥17 字节才可解读）；`ZT_WORLD_ID_EARTH = 149604618`；moon 缓存文件名 = worldId 的 16 位小写十六进制 + `.moon`
- **路径映射**（filesRoot = `context.filesDir`，自检页用 `context.cacheDir/p1-selfcheck`）：配置 JSON → `config/`（planets.json / networks.json / moons.json / prefs.json）；planet 文件 → `planets/<uuid>.planet`；行星状态 → `zt/<planetId>/`（内含 `moons.d/`）
- **持久化设施语义**：全部公开 API 为 suspend；单一全局 Mutex 串行化四份文件 IO；写 = 序列化 → `<name>.tmp` → rename 原子替换；读损坏 = 隔离为 `<name>.corrupt`（已存在则覆盖）并返回 null、记 Log.w，**不自动重建**（`.corrupt` 存在时首启引导不重写默认文件）
- **prefs.json 首版字段**：`selectedPlanetId`（默认 `"official"`，仅浏览上下文，切换不发变更通知）、`activePlanetId`（null = 从未激活过任何 Planet；activate 只更新此指针，隧道重启由 P2 调用方负责）
- 固定语义对齐 iOS：readPlanets 空表回退 `[official]`；writePlanets 保证 official 永在且排最前；selected/active 指针指向不存在 id 时回退 official；deletePlanet 仅限 custom 且级联删除该 planet 的 networks/moons/planet 文件/状态目录，被删者若正被选中/激活则回退 official；writeNetworks/writeMoons 按 planetId 整组替换并盖戳
- 工具链/包名/applicationId 沿用 P0 不动（AGP 8.13.2 / Kotlin 2.2.21 / minSdk 23 / targetSdk 33 / applicationId `cn.imgozi.a.planetport` / namespace `cn.imgozi.planetport`）
- 提交信息：中文 Conventional Commits，Body 必写，禁止任何 AI 署名尾注；git 命令一律 `--no-pager`
- SDK 版本 API（`Node.getVersion()`）为实例方法且需完整 Node，**本阶段不接**（顺延 P2 首个 Node 任务做符号级验证）

---

### Task 1: 依赖与插件接入（serialization + coroutines）

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`（仓库根）
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: P0 的 version catalog 与别名命名风格（连字符插件别名 / 点分库别名）
- Produces: `libs.plugins.kotlin.serialization` 、 `libs.kotlinx.serialization.json` 、 `libs.kotlinx.coroutines.android` 供本计划后续任务与 P2+ 使用；`lifecycle-viewmodel-compose` 仅入 catalog（P4 才接入依赖，YAGNI）

- [ ] **Step 1: 扩充 version catalog**

`gradle/libs.versions.toml` 全量替换为：

```toml
[versions]
agp = "8.13.2"
kotlin = "2.2.21"
composeBom = "2025.12.01"
activityCompose = "1.10.1"
lifecycle = "2.9.1"
serialization = "1.9.0"
coroutines = "1.10.2"

[libraries]
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-foundation = { group = "androidx.compose.foundation", name = "foundation" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
# P4 起使用的 ViewModel 依赖，先锁版本不接入
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: 根 build.gradle.kts 注册插件**

在根 `build.gradle.kts` 的 plugins 块（现有 4 个 `alias(...) apply false` 之后）追加一行：

```kotlin
    alias(libs.plugins.kotlin.serialization) apply false
```

- [ ] **Step 3: app 模块接入**

`app/build.gradle.kts` 的 plugins 块追加：

```kotlin
    alias(libs.plugins.kotlin.serialization)
```

dependencies 块的 `implementation(libs.lifecycle.runtime.ktx)` 之后追加：

```kotlin
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
```

- [ ] **Step 4: 构建验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts
git commit -m "build: 接入 kotlinx-serialization 与 coroutines 依赖

数据层（P1）需要 JSON 模型序列化与结构化并发；serialization 编译器
插件随 Kotlin 版本走。同时把 lifecycle-viewmodel-compose 预锁进
catalog（P4 才接入 app 依赖），避免后续临时补版本造成漂移。"
```

---

### Task 2: 数据模型与 JSON 文件设施（Models.kt + JsonFileStore.kt）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/store/Models.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/store/JsonFileStore.kt`

**Interfaces:**
- Consumes: kotlinx-serialization（Task 1）
- Produces（后续任务依赖的精确签名）:
  - `enum class DnsMode` / `enum class PlanetKind`（@Serializable）
  - `data class PlanetProfile(id, name, kind, fileName, icon, lastNodeId)`；`PlanetProfile.OFFICIAL_ID = "official"`；`PlanetProfile.OFFICIAL`
  - `data class NetworkEntry(networkId, networkName, lastActivated, routeViaZeroTier, dnsMode, customDns, planetId, lastConflict)`；`val id get() = networkId`
  - `data class MoonEntry(worldId: ULong, seed: ULong, fromFile: Boolean, planetId: String?)`；`MoonEntry.formatWorldId(worldId: ULong): String`（16 位小写十六进制）；`val id get() = formatWorldId(worldId)`
  - `data class AppPrefs(selectedPlanetId = "official", activePlanetId: String? = null)`
  - `internal class JsonFileStore(json: Json)`；`suspend fun <T> read(file: File, serializer: KSerializer<T>): T?`；`suspend fun <T> write(file: File, value: T, serializer: KSerializer<T>)`

- [ ] **Step 1: 写 Models.kt**

`app/src/main/java/cn/imgozi/planetport/store/Models.kt`：

```kotlin
package cn.imgozi.planetport.store

import kotlinx.serialization.Serializable

/** DNS 处理模式（对齐 iOS DNSMode：network = 跟随网络下发，custom = 用户自定义）。 */
@Serializable
enum class DnsMode {
    none, network, custom,
}

/** Planet 来源（对齐 iOS PlanetKind）。 */
@Serializable
enum class PlanetKind {
    official, custom,
}

/** 一套可独立切换的 Planet 配置。Network 与 Moon 均通过 planetId 归属于它。 */
@Serializable
data class PlanetProfile(
    val id: String,
    val name: String,
    val kind: PlanetKind,
    /** 私有 Planet 在 planets/ 下的文件名；官方 Planet 使用核心内置默认。 */
    val fileName: String? = null,
    /** 自定义图标标识；null 时按 id 哈希自动生成（P4）。 */
    val icon: String? = null,
    /** 该行星下本机节点 ID 缓存（隧道运行时回写，离线可显示；P2）。 */
    val lastNodeId: String? = null,
) {
    companion object {
        const val OFFICIAL_ID = "official"
        val OFFICIAL = PlanetProfile(
            id = OFFICIAL_ID,
            name = "ZeroTier 官方",
            kind = PlanetKind.official,
            fileName = null,
        )
    }
}

/** 隶属于某个 Planet 的网络条目。 */
@Serializable
data class NetworkEntry(
    /** 16 位十六进制网络 ID。 */
    val networkId: String,
    val networkName: String,
    /** 上次会话是否处于开启状态（应用启动 + 隧道未连接时整体复位）。 */
    val lastActivated: Boolean,
    /** 是否把该网段路由交给 ZeroTier（对齐 iOS routeViaZeroTier）。 */
    val routeViaZeroTier: Boolean,
    val dnsMode: DnsMode,
    val customDns: List<String>,
    /** 归属 Planet；null 仅为 JSON 兼容保留，写入时总会被盖戳。 */
    val planetId: String? = null,
    /** 最近一次冲突原因码（"address"/"subnet"），再次开启时清除；null = 无记录。 */
    val lastConflict: String? = null,
) {
    val id: String get() = networkId
}

/** Moon 节点条目。 */
@Serializable
data class MoonEntry(
    val worldId: ULong,
    val seed: ULong,
    /** 是否来自 .moon 文件导入（对齐 iOS fromFile）。 */
    val fromFile: Boolean,
    val planetId: String? = null,
) {
    val id: String get() = formatWorldId(worldId)

    companion object {
        /** 16 位小写十六进制（对齐 iOS %016llx；负 Long 的补码十六进制恰好是 64 位无符号表示）。 */
        fun formatWorldId(worldId: ULong): String = "%016x".format(worldId.toLong())
    }
}

/** prefs.json 模型。activePlanetId = null 表示从未激活过任何 Planet。 */
@Serializable
data class AppPrefs(
    val selectedPlanetId: String = PlanetProfile.OFFICIAL_ID,
    val activePlanetId: String? = null,
)
```

- [ ] **Step 2: 写 JsonFileStore.kt**

`app/src/main/java/cn/imgozi/planetport/store/JsonFileStore.kt`：

```kotlin
package cn.imgozi.planetport.store

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * 四份 JSON 配置共用的文件读写设施：
 * - 单一 Mutex 串行化全部配置 IO（写频低，全局锁最简单且足够）；
 * - 写 = 序列化到 <name>.tmp 后 rename 原子替换（序列化失败直接抛，不产生半截文件）；
 * - 读损坏 = 隔离为 <name>.corrupt（覆盖旧隔离）并返回 null——保留现场供排查，
 *   不自动重建；调用方决定内存默认值，文件由下一次真实写回重建。
 */
internal class JsonFileStore(private val json: Json) {

    private val mutex = Mutex()

    suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(serializer, file.readText())
            } catch (e: Exception) {
                quarantine(file, e)
                null
            }
        }
    }

    suspend fun <T> write(file: File, value: T, serializer: KSerializer<T>): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val text = json.encodeToString(serializer, value)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("配置写入失败（rename）: ${file.name}")
            }
        }
    }

    /** 损坏隔离：改名保留现场；隔离文件已存在时覆盖（只保留最近一次现场）。 */
    private fun quarantine(file: File, cause: Exception) {
        val target = File(file.parentFile, file.name + ".corrupt")
        runCatching {
            if (target.exists()) target.delete()
            file.renameTo(target)
        }
        Log.w(TAG, "配置损坏已隔离为 ${target.name}", cause)
    }

    private companion object {
        const val TAG = "PlanetPortStore"
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cn/imgozi/planetport/store/Models.kt app/src/main/java/cn/imgozi/planetport/store/JsonFileStore.kt
git commit -m "feat(store): 数据模型与 JSON 原子读写设施

字段名与枚举值对齐 iOS 端 Codable 默认输出，保证两平台 JSON 结构
一致。JsonFileStore 提供全局 Mutex + tmp/rename 原子写与 .corrupt
损坏隔离（不自动重建，保留现场）。"
```

---

### Task 3: 配置域 API（AppConfigStore.kt）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/store/AppConfigStore.kt`

**Interfaces:**
- Consumes: Task 2 的全部模型与 `JsonFileStore`
- Produces（精确签名，Task 4/5 与 P2+ 依赖）:
  - `class StorePaths(filesRoot: File)`：`configDir: File`、`planetsDir: File`、`planetsFile/networksFile/moonsFile/prefsFile: File`、`fun planetStateDir(planetId: String): File`
  - `class AppConfigStore(val paths: StorePaths)`，全部公开成员为 suspend：
    - `val configChanges: SharedFlow<Unit>`（写配置后发射；selectPlanet/resetAllNetworkActivation 静默不发）
    - `fun readPlanets(): List<PlanetProfile>` / `fun writePlanets(profiles: List<PlanetProfile>)`
    - `fun selectedPlanetId(): String` / `fun activePlanetId(): String` / `fun hasActivatedPlanet(): Boolean`
    - `fun selectPlanet(id: String)` / `fun activatePlanet(id: String): Boolean`（返回值 = 指针是否实际变化）
    - `fun deletePlanet(profile: PlanetProfile)`
    - `fun readNetworks(planetId: String? = null): List<NetworkEntry>` / `fun readNetworksByPlanet(): Map<String, List<NetworkEntry>>` / `fun readActiveNetworks(): List<NetworkEntry>` / `fun writeNetworks(values: List<NetworkEntry>, planetId: String? = null)`
    - `fun readMoons(planetId: String? = null): List<MoonEntry>` / `fun readAllMoons(): List<MoonEntry>` / `fun readActiveMoons(): List<MoonEntry>` / `fun writeMoons(values: List<MoonEntry>, planetId: String? = null)`
    - `fun resetAllNetworkActivation()`
    - `fun <T> mutate(block: suspend () -> T): T`（域锁内执行复合读-改-写；block 内只可用 *Locked 变体与读方法，禁调公开变更方法）
    - `fun writePlanetsLocked(profiles: List<PlanetProfile>)`（internal，域锁内专用）

- [ ] **Step 1: 写 AppConfigStore.kt**

`app/src/main/java/cn/imgozi/planetport/store/AppConfigStore.kt`：

```kotlin
package cn.imgozi.planetport.store

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
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

    private val _configChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    /** 配置发生需要隧道/其它读者感知的变化时发射（P2 订阅触发重配）。 */
    val configChanges: SharedFlow<Unit> = _configChanges

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
        // official 永在且排最前（以固定档案为准，天然去重）
        val normalized = listOf(PlanetProfile.OFFICIAL) +
            profiles.filterNot { it.id == PlanetProfile.OFFICIAL_ID }
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
        writePrefs(readPrefs().copy(selectedPlanetId = id))
    }

    /** 设置隧道下次启动要加载的 Planet；只更新指针，重启联动由调用方（P2）负责。 */
    suspend fun activatePlanet(id: String): Boolean = mutate {
        activatePlanetLocked(id)
    }

    private suspend fun activatePlanetLocked(id: String): Boolean {
        if (readPlanets().none { it.id == id }) return false
        val changed = activePlanetId() != id
        writePrefs(readPrefs().copy(activePlanetId = id))
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

        profile.fileName?.let { File(paths.planetsDir, it).delete() }
        paths.planetStateDir(profile.id).deleteRecursively()

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

    // MARK: - 文件层

    private suspend fun readPlanetsFile(): List<PlanetProfile>? = files.read(paths.planetsFile, planetList)
    private suspend fun readNetworksFile(): List<NetworkEntry>? = files.read(paths.networksFile, networkList)
    private suspend fun readMoonsFile(): List<MoonEntry>? = files.read(paths.moonsFile, moonList)

    private suspend fun readPrefs(): AppPrefs =
        files.read(paths.prefsFile, AppPrefs.serializer()) ?: AppPrefs()

    private suspend fun writePrefs(prefs: AppPrefs) {
        files.write(paths.prefsFile, prefs, AppPrefs.serializer())
    }

    /**
     * 首启引导：planets.json 不存在且无 .corrupt 损坏隔离时写入官方默认；
     * prefs.json 不引导落盘（readPrefs 缺失时回退内存默认，首次真实写才创建）。
     * .corrupt 存在 = 上次现场待排查，不自动重建（读到 null 走内存默认）。
     */
    private suspend fun bootstrap() {
        if (!paths.planetsFile.exists() && !File(paths.planetsFile.path + ".corrupt").exists()) {
            files.write(paths.planetsFile, listOf(PlanetProfile.OFFICIAL), planetList)
        }
    }

    private fun notifyConfigChanged() {
        _configChanges.tryEmit(Unit)
    }
}
```

说明：prefs.json 无需引导写入——`readPrefs()` 对缺失/损坏统一回退内存默认 `AppPrefs()`，首次真实写操作（select/activate）才落盘，减少无意义文件。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/cn/imgozi/planetport/store/AppConfigStore.kt
git commit -m "feat(store): 四份 JSON 配置的域 API 与切换/级联删除

对齐 iOS AppConfigStore 语义：official 保底、selected/active 双指针
（selected 静默浏览、active 供隧道取用）、按 Planet 整组替换盖戳、
删除 Planet 级联清理并回退官方。configChanges 流替代 iOS 的 Darwin
通知，供 P2 隧道订阅重配。"
```

---

### Task 4: Planet 与 Moon 导入（PlanetImporter.kt + MoonImporter.kt）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/store/PlanetImporter.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/store/MoonImporter.kt`

**Interfaces:**
- Consumes: Task 3 的 `AppConfigStore`（构造参数，公开 `paths` 可用）
- Produces:
  - `class PlanetImporter(store: AppConfigStore)`：
    - `sealed class ImportError(message: String, cause: Throwable?) : Exception`，子类 `BadHeader` / `EmptyName` / `DownloadFailed(cause)` （对齐 iOS error case；用户文案映射由 P4 做）
    - `suspend fun importPlanetData(data: ByteArray, name: String): PlanetProfile`
    - `suspend fun importPlanetFromURL(urlString: String, name: String): PlanetProfile`
    - `suspend fun downloadPlanetFile(urlString: String): ByteArray`
    - `suspend fun rename(profile: PlanetProfile, name: String)`
    - `suspend fun updateIcon(profile: PlanetProfile, icon: String?)`
    - `fun worldIdOf(data: ByteArray): ULong?`（companion，纯函数）
    - `suspend fun existingWorldIds(): Set<ULong>`
    - `suspend fun inspectPlanetFile(profile: PlanetProfile): PlanetFileInfo?`；`data class PlanetFileInfo(worldId: ULong, timestamp: ULong, fileSize: Int)`
  - `class MoonImporter(store: AppConfigStore)`：
    - `sealed class ImportError` 子类 `BadHeader` / `ReadFailed(cause)`
    - `suspend fun importMoonData(data: ByteArray): MoonEntry`（落盘 moons.d/，条目持久化由调用方 writeMoons 完成——对齐 iOS 职责划分）

- [ ] **Step 1: 写 PlanetImporter.kt**

`app/src/main/java/cn/imgozi/planetport/store/PlanetImporter.kt`：

```kotlin
package cn.imgozi.planetport.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 多 Planet 文件导入。每份私有 Planet 独立保存，不覆盖其它配置。 */
class PlanetImporter(private val store: AppConfigStore) {

    sealed class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class BadHeader : ImportError("planet 文件头无效（首字节应为 0x01）")
        class EmptyName : ImportError("名称不能为空")
        class DownloadFailed(cause: Throwable? = null) : ImportError("planet 下载失败", cause)
    }

    /** 校验并落盘一份 planet 文件，注册为新的自定义 Planet。 */
    suspend fun importPlanetData(data: ByteArray, name: String): PlanetProfile {
        if (data.isEmpty() || data[0] != PLANET_TYPE_BYTE) throw ImportError.BadHeader()
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw ImportError.EmptyName()

        val id = java.util.UUID.randomUUID().toString().lowercase()
        val fileName = "$id.planet"
        withContext(Dispatchers.IO) {
            val dir = store.paths.planetsDir.apply { mkdirs() }
            val target = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("planet 文件写入失败: $fileName")
            }
        }

        val profile = PlanetProfile(id = id, name = normalizedName, kind = PlanetKind.custom, fileName = fileName)
        // 注册表读-追加-写整段持域锁，防止并发写丢更新
        store.mutate {
            store.writePlanetsLocked(store.readPlanets() + profile)
        }
        return profile
    }

    suspend fun importPlanetFromURL(urlString: String, name: String): PlanetProfile =
        importPlanetData(downloadPlanetFile(urlString), name)

    /** 仅下载文件内容，不校验、不落库（供"先就绪、后添加"交互使用，P4）。 */
    suspend fun downloadPlanetFile(urlString: String): ByteArray = withContext(Dispatchers.IO) {
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            throw ImportError.DownloadFailed(e)
        }
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            try {
                if (conn.responseCode != 200) throw ImportError.DownloadFailed()
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError.DownloadFailed(e)
        }
    }

    suspend fun rename(profile: PlanetProfile, name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw ImportError.EmptyName()
        store.mutate {
            val profiles = store.readPlanets()
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index < 0) return@mutate
            store.writePlanetsLocked(
                profiles.toMutableList().apply { this[index] = this[index].copy(name = normalizedName) }
            )
        }
    }

    /** 更新自定义图标；null 表示恢复自动生成。 */
    suspend fun updateIcon(profile: PlanetProfile, icon: String?) {
        store.mutate {
            val profiles = store.readPlanets()
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index < 0) return@mutate
            store.writePlanetsLocked(
                profiles.toMutableList().apply { this[index] = this[index].copy(icon = icon) }
            )
        }
    }

    /** 已存在的 World ID 集合（含官方内置），供添加时查重。 */
    suspend fun existingWorldIds(): Set<ULong> {
        val ids = mutableSetOf(ZT_WORLD_ID_EARTH)
        for (profile in store.readPlanets()) {
            inspectPlanetFile(profile)?.let { ids.add(it.worldId) }
        }
        return ids
    }

    /** 从 .planet 文件尽力解读只读信息：byte0 类型 + 8B 大端 worldId + 8B 大端时间戳（需 ≥17B）。 */
    suspend fun inspectPlanetFile(profile: PlanetProfile): PlanetFileInfo? = withContext(Dispatchers.IO) {
        val fileName = profile.fileName ?: return@withContext null
        val data = runCatching { File(store.paths.planetsDir, fileName).readBytes() }
            .getOrNull() ?: return@withContext null
        if (data.size < 17 || data[0] != PLANET_TYPE_BYTE) return@withContext null
        PlanetFileInfo(
            worldId = readBigEndianUInt64(data, 1),
            timestamp = readBigEndianUInt64(data, 9),
            fileSize = data.size,
        )
    }

    data class PlanetFileInfo(val worldId: ULong, val timestamp: ULong, val fileSize: Int)

    companion object {
        /** planet 文件类型标记（ZT world 二进制头 byte0）。 */
        const val PLANET_TYPE_BYTE: Byte = 0x01

        /** ZT_WORLD_ID_EARTH：官方 Planet 的内置 World ID。 */
        const val ZT_WORLD_ID_EARTH: ULong = 149604618u

        /** 从 planet 文件数据提取 World ID（byte0 类型 + 8 字节大端）。 */
        fun worldIdOf(data: ByteArray): ULong? {
            if (data.size < 9 || data[0] != PLANET_TYPE_BYTE) return null
            return readBigEndianUInt64(data, 1)
        }

        internal fun readBigEndianUInt64(data: ByteArray, offset: Int): ULong {
            var value = 0uL
            for (i in offset until offset + 8) {
                value = (value shl 8) or data[i].toUByte().toULong()
            }
            return value
        }
    }
}
```

- [ ] **Step 2: 写 MoonImporter.kt**

`app/src/main/java/cn/imgozi/planetport/store/MoonImporter.kt`：

```kotlin
package cn.imgozi.planetport.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Moon 文件导入。校验 .moon 数据并落盘到当前选中 Planet 独立的 moons.d/ 目录。 */
class MoonImporter(private val store: AppConfigStore) {

    sealed class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class BadHeader : ImportError("moon 文件头无效（首字节应为 0x7f）")
        class ReadFailed(cause: Throwable? = null) : ImportError("moon 数据读取失败", cause)
    }

    /**
     * 校验并落盘。文件格式：首字节 0x7f + 8 字节大端 worldId。
     * 返回的 MoonEntry 由调用方经 AppConfigStore.writeMoons 持久化（对齐 iOS 职责划分）。
     */
    suspend fun importMoonData(data: ByteArray): MoonEntry {
        if (data.size < 9) throw ImportError.ReadFailed()
        if (data[0] != MOON_TYPE_BYTE) throw ImportError.BadHeader()
        val worldId = PlanetImporter.readBigEndianUInt64(data, 1)

        val planetId = store.selectedPlanetId()
        withContext(Dispatchers.IO) {
            val dir = File(store.paths.planetStateDir(planetId), "moons.d")
            dir.mkdirs()
            val target = File(dir, MoonEntry.formatWorldId(worldId) + ".moon")
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("moon 文件写入失败: ${target.name}")
            }
        }
        return MoonEntry(worldId = worldId, seed = 0u, fromFile = true, planetId = planetId)
    }

    companion object {
        /** moon 文件类型标记。 */
        const val MOON_TYPE_BYTE: Byte = 0x7f
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cn/imgozi/planetport/store/PlanetImporter.kt app/src/main/java/cn/imgozi/planetport/store/MoonImporter.kt
git commit -m "feat(store): planet 与 moon 文件导入解析

planet 校验 0x01 头后以 UUID 命名原子落盘并注册档案，支持 URL 下载
（5s/10s 超时）与 worldId/时间戳只读解读、查重集合含官方内置 ID
149604618；moon 校验 0x7f 头后按 16 位十六进制 worldId 落入所选
Planet 的 moons.d/。条目持久化由调用方 writeMoons 完成。"
```

---

### Task 5: 真机自检页与 P1 收尾验证

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/ui/P1SelfCheck.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/MainActivity.kt`（全量替换 onCreate 内实现）

**Interfaces:**
- Consumes: Task 2/3/4 的 store 全部 API
- Produces: `internal object P1SelfCheck { suspend fun run(context: android.content.Context): List<String> }`（P2 起该页会被替换，属一次性验证设施）

- [ ] **Step 1: 写 P1SelfCheck.kt**

`app/src/main/java/cn/imgozi/planetport/ui/P1SelfCheck.kt`：

```kotlin
package cn.imgozi.planetport.ui

import android.content.Context
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.MoonEntry
import cn.imgozi.planetport.store.MoonImporter
import cn.imgozi.planetport.store.PlanetImporter
import cn.imgozi.planetport.store.PlanetKind
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StorePaths
import java.io.File

/**
 * P1 数据层真机自检：在 cacheDir 隔离目录跑「引导 → 导入 → 切换 → 持久化 → 级联删除」
 * 全链路，不污染真实配置。P2 起随正式 UI 落地移除。
 */
internal object P1SelfCheck {

    /** 合成 planet：0x01 + worldId 0x0123456789abcdef（大端）+ 时间戳 + 填充，共 17 字节。 */
    private val syntheticPlanet = byteArrayOf(0x01) + byteArrayOf(
        0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
        0x00, 0x00, 0x17, 0x6e, 0x55, 0x68, 0x4b, 0x00, // 时间戳常量即可
    )

    /** 合成 moon：0x7f + worldId 0xfedcba9876543210（大端），共 9 字节。 */
    private val syntheticMoon = byteArrayOf(0x7f) + byteArrayOf(
        0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76.toByte(), 0x54.toByte(), 0x32.toByte(), 0x10.toByte(),
    )

    suspend fun run(context: Context): List<String> {
        val lines = mutableListOf<String>()
        fun line(ok: Boolean, label: String) {
            lines += (if (ok) "✓ " else "✗ ") + label
        }

        val base = File(context.cacheDir, "p1-selfcheck").apply { deleteRecursively(); mkdirs() }
        val store = AppConfigStore(StorePaths(base))
        val planetImporter = PlanetImporter(store)
        val moonImporter = MoonImporter(store)

        // 1. 首启引导：只有官方 Planet
        val boot = store.readPlanets()
        line(boot.size == 1 && boot[0].id == PlanetProfile.OFFICIAL_ID, "planets 首启引导（${boot.size} 个，官方保底）")

        // 2. planet 头解析（纯函数）
        val worldId = PlanetImporter.worldIdOf(syntheticPlanet)
        line(worldId == 0x0123456789abcdefu, "planet 头解析 worldId=${worldId?.let { MoonEntry.formatWorldId(it) }}")

        // 3. planet 导入 + 落盘回读 + 持久化
        val profile = planetImporter.importPlanetData(syntheticPlanet, "自检行星")
        val info = planetImporter.inspectPlanetFile(profile)
        val afterImport = store.readPlanets()
        line(
            afterImport.size == 2 && afterImport[0].id == PlanetProfile.OFFICIAL_ID &&
                info != null && info.worldId == worldId && info.fileSize == syntheticPlanet.size,
            "planet 导入与回读（official 排最前，文件 ${info?.fileSize} 字节）",
        )

        // 4. moon 导入（落盘所选 Planet 的 moons.d/）
        val moon = moonImporter.importMoonData(syntheticMoon)
        val moonFile = File(store.paths.planetStateDir(PlanetProfile.OFFICIAL_ID), "moons.d/${moon.id}.moon")
        line(moon.worldId == 0xfedcba9876543210u && moonFile.exists(), "moon 导入落盘 id=${moon.id}")

        // 5. 指针切换：select 静默、activate 生效
        store.selectPlanet(profile.id)
        val selected = store.selectedPlanetId()
        val changed = store.activatePlanet(profile.id)
        val active = store.activePlanetId()
        line(selected == profile.id && changed && active == profile.id && store.hasActivatedPlanet(), "select/activate 切换（$selected / $active）")

        // 6. 级联删除与回退
        store.deletePlanet(profile)
        val afterDelete = store.readPlanets()
        line(
            afterDelete.size == 1 && store.selectedPlanetId() == PlanetProfile.OFFICIAL_ID &&
                store.activePlanetId() == PlanetProfile.OFFICIAL_ID &&
                !File(store.paths.planetsDir, profile.fileName!!).exists(),
            "删除级联回退 official",
        )

        return lines
    }
}
```

- [ ] **Step 2: 改造 MainActivity.kt**

`app/src/main/java/cn/imgozi/planetport/ui/MainActivity.kt` 全量替换为：

```kotlin
package cn.imgozi.planetport.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * P1 数据层自检页：JNI 加载 + store 全链路（隔离目录）逐项结果。
 * P4 起替换为正式首页。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val jniLoaded = try {
            System.loadLibrary("ZeroTierOneJNI")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libZeroTierOneJNI 加载失败", e)
            false
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val results by produceState(initialValue = listOf("自检中…")) {
                        value = try {
                            P1SelfCheck.run(this@MainActivity)
                        } catch (e: Exception) {
                            listOf("自检异常: ${e.message}")
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(modifier = Modifier.height(48.dp))
                        Text(text = if (jniLoaded) "libZeroTierOneJNI 加载成功" else "JNI 库加载失败")
                        results.forEach { Text(text = it) }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
```

- [ ] **Step 3: 构建并装机验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

Run: `adb -s 9311902b install -r app/build/outputs/apk/debug/app-debug.apk && adb -s 9311902b shell am start -n cn.imgozi.a.planetport/cn.imgozi.planetport.ui.MainActivity && sleep 3 && adb -s 9311902b exec-out screencap -p > /tmp/p1-selfcheck.png`
Expected: 安装 Success；截图中 7 行自检全部以 `✓` 开头（JNI 加载 + 6 项 store 检查）。任何 `✗` 或异常行 = 修复后重来，不允许带病提交。
（设备未连接时：记录说明并跳过装机，构建成功即可提交，装机验证顺延到用户在场时补做。）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/P1SelfCheck.kt app/src/main/java/cn/imgozi/planetport/ui/MainActivity.kt
git commit -m "feat(app): P1 数据层真机自检页

验证页从单行 JNI 状态升级为全链路自检：在 cacheDir 隔离目录执行
引导、planet/moon 合成数据导入、头解析、指针切换、级联删除与回退，
覆盖 P1 完成标准（导入/切换/持久化正确）且不污染真实配置。
顺带闭合 P0 留档项：JNI 加载失败分支补记日志。"
```

---

## 验证与收尾

- 最终验证 = Task 5 Step 3 的真机全 ✓ 截图 + `./gradlew assembleDebug assembleRelease` 双绿。
- 自检页中的合成数据常量仅用于验证设施，不进入 store 正式逻辑。
- P1 不做：UI 表单、URI 文件选择（P4）、隧道联动（P2）、语言/主题设置项（P4）。
