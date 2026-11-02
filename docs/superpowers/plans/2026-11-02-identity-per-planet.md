# 节点身份模式（每行星独立身份）Android 移植实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 iOS 已交付的「节点身份模式」功能整体移植到 Android：所有行星共用一个节点身份（默认），或每个行星使用独立身份（互不可关联），可随时切换且往返保真。

**Architecture:** 模式偏好存 `AppPrefs.identityPerPlanet`（prefs.json）；`NodeRuntime.stateFile` 在独立模式下把 `identity.*` 重定向到 per-planet 目录（`files/zt/<planetId>/`，与 moon/peer/netconf 同目录）——模式经 NodeRuntime 构造参数快照注入，核心重启即切换生效。身份文件复制（seed/adopt）全部由 App 侧 `IdentityStore` 完成：**ZeroTier 核心只在构造（ZT_Node_new）时读/生成身份、退出不回写**，唯一硬性顺序是复制必须先于新核心启动；切换编排 = 复制身份 → `TunnelController.restartIfRunning`（停 → 等停机终态 → 启）。

**Tech Stack:** Kotlin + Jetpack Compose（M3）、kotlinx.serialization（AppPrefs）、ZeroTier 1.16.2 JNI（App 进程内，无 Extension）。

## 移植蓝本（iOS feat/identity-per-planet，已终审 Approved）

| iOS | Android 对应 |
|---|---|
| `AppGroup.prefIdentityPerPlanet`（UserDefaults） | `AppPrefs.identityPerPlanet`（prefs.json，kotlinx 默认值反序列化免迁移） |
| `IdentityStore.swift`（enum + static） | `store/IdentityStore.kt`（object，纯阻塞 File API，调用方负责线程） |
| `ZTBridge ztHookedPath` IDENTITY 分支 | `NodeRuntime.stateFile` identity 分支（构造参数注入模式快照） |
| `AppState.switchIdentityMode`（stopAndWait → apply → prepareAndStart） | `PrefsViewModel.setIdentityMode`（复制 → restartIfRunning） |
| `PrefsView.swift` 身份 section + IdentityModeSheet + IdentityPickView | `PrefsView.kt` 身份 section + 两个 ModalBottomSheet + AlertDialog 确认 |
| 删行星级联 `deletePlanet` 删 planet-state | **已天然成立**：`AppConfigStore.deletePlanet` 已 `planetStateDir.deleteRecursively()` |
| 加行星惰性生成 | **零改动**：独立模式下核心首次在该行星启动时自行生成身份 |

## 平台差异（已裁定，不要在实现中"修复"）

- iOS 经 AppGroup + Darwin 通知跨进程；Android 单进程，`NodeRuntime` 直接读构造参数快照，**JNI 线程不读 prefs**。
- iOS `stopAndWait` 回调内 apply；Android 核心运行期不碰身份文件，复制放 restart 发起**之前**即满足"先于新核心启动"（restartIfRunning 的 START 在停机终态后才下发）。
- iOS identity 私钥 chmod 0600；Android app 私有目录本就沙箱，secret 收紧对齐 `NodeRuntime.StorePutListener` 现有做法即可。
- Android 无 pick.empty 死文案（空候选直接切换，见 Task 2），不移植该 key。

## Global Constraints

- 分支 `feat/identity-per-planet`（已基于 dev@2e432ff 创建），**不 push、不合并**。
- 用户规矩：**不写测试用例**；每任务以 `./gradlew --no-daemon -q assembleDebug` 构建通过为验证门禁（在 `/Users/zivyuan/GoziLib/Zerotier/planet-port-android` 执行）。
- 提交信息：Conventional Commits 中文，type/scope 英文，必须 Body，**禁止 Co-authored-by**；每任务一次完整提交。
- 语义与 iOS 严格对齐：默认共享；单→多 seed 当前激活行星（沿用现有身份、网络不中断），其余行星惰性生成；多→单 adopt 选中行星身份为全局共享（覆盖 `files/zt/identity.*`），其余行星独立身份**原地保留**；空候选（无行星有独立身份）切共享直接切换不弹选择页；删行星级联清理已天然成立。
- 共享身份位置 `files/zt/identity.*`（= 隧道 dataStoreRoot），per-planet 身份 `files/zt/<planetId>/identity.*`（= `StorePaths.planetStateDir`）。两者必须同源（都从 `StoreRepo.store.paths` 派生），禁止硬编码第二份路径。
- 注释里关于核心身份生命周期只能写：**核心只在构造时读/生成身份、退出不回写、身份复制全部由 App 侧完成、复制必须先于新核心启动**（此结论经 iOS 侧终审取证 Core/node/Node.cpp:78-110，勿引入"核心退出回写"的错误说法）。
- 三语文案 key 命名对齐仓内风格（`prefs_identity_*`）；文案从 iOS xcstrings 三语逐条搬运（Task 2 给全）。
- ignore 行号漂移：文中行号为 dev@2e432ff 基准，编辑前以内容定位为准。

---

### Task 1: 存储层与核心身份路径重定向

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/store/Models.kt`（AppPrefs，约 83-109 行）
- Modify: `app/src/main/java/cn/imgozi/planetport/store/AppConfigStore.kt`（StorePaths，约 13-23 行）
- Create: `app/src/main/java/cn/imgozi/planetport/store/IdentityStore.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/NodeRuntime.kt`（构造参数约 72-73 行、stateFile 约 236-242 行）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（装配点约 108-114 行）

**Interfaces:**
- Consumes: 现有 `StorePaths.planetStateDir(planetId)`、`AppConfigStore.readPrefs()/activePlanetId()`
- Produces（Task 2 依赖，签名精确）:
  - `AppPrefs.identityPerPlanet: Boolean`（默认 false）
  - `StorePaths.dataStoreRoot: File`
  - `IdentityStore.hasPlanetIdentity(paths: StorePaths, planetId: String): Boolean`
  - `IdentityStore.planetIdentityAddress(paths: StorePaths, planetId: String): String?`
  - `IdentityStore.seedSharedIdentity(paths: StorePaths, planetId: String)`
  - `IdentityStore.adoptSharedIdentity(paths: StorePaths, planetId: String)`
  - `NodeRuntime` 新构造参数 `identityPerPlanet: Boolean`（named arg，插在 planetFile 之后）

- [ ] **Step 1: AppPrefs 加字段**

`Models.kt` 的 `AppPrefs`，加在 `connectOnDemand` 之后（data class 末位）：

```kotlin
    /** 节点身份模式（对齐 iOS identity_per_planet；默认 false = 所有行星共享一个身份）。
     *  独立模式下身份文件按行星隔离（files/zt/<planetId>/identity.*），核心只在
     *  构造时读/生成身份，切换模式经隧道整体重启生效。 */
    val identityPerPlanet: Boolean = false,
```

- [ ] **Step 2: StorePaths 暴露 dataStoreRoot**

`AppConfigStore.kt` 的 `StorePaths`，加在 `planetStateDir` 旁：

```kotlin
    /** 核心数据存储根（身份/planet 默认落点）。与 [planetStateDir] 同根派生，
     *  隧道装配（ZeroTierVpnService）与身份复制（IdentityStore）共用，禁止各自硬编码。 */
    val dataStoreRoot: File get() = File(filesRoot, "zt")
```

- [ ] **Step 3: 新建 IdentityStore.kt**

整文件（对齐 iOS `Shared/IdentityStore.swift`）：

```kotlin
package cn.imgozi.planetport.store

import android.util.Log
import java.io.File

/**
 * 节点身份模式的身份文件操作（App 侧管理；对齐 iOS IdentityStore.swift）。
 * 共享身份：files/zt/identity.*（全行星共用，默认）；
 * 每行星身份：files/zt/<planetId>/identity.*（核心首次在该行星以独立模式启动时
 * 自行生成；目录随删除行星级联清理，见 AppConfigStore.deletePlanet）。
 *
 * ZeroTier 核心只在构造（ZT_Node_new）时读/生成身份、退出不回写——身份复制
 * 全部由本 object 完成，唯一硬性顺序是复制必须先于新核心启动（调用方保证）。
 * 纯阻塞 File API，方法都很小（两个 <1KB 文件），调用方负责线程选择。
 */
object IdentityStore {
    private const val TAG = "IdentityStore"
    const val IDENTITY_PUBLIC = "identity.public"
    const val IDENTITY_SECRET = "identity.secret"

    /** 该行星是否已生成独立身份（以私钥文件为准）。 */
    fun hasPlanetIdentity(paths: StorePaths, planetId: String): Boolean =
        File(paths.planetStateDir(planetId), IDENTITY_SECRET).exists()

    /** 解析该行星独立身份的节点地址：identity.public 内容为 "address:publicKey"，
     *  冒号前 10 位 hex 即 ZeroTier 节点地址；无身份或不可解析返回 null。 */
    fun planetIdentityAddress(paths: StorePaths, planetId: String): String? =
        runCatching { File(paths.planetStateDir(planetId), IDENTITY_PUBLIC).readText() }
            .getOrNull()
            ?.split(":")
            ?.firstOrNull()
            ?.takeIf { it.length == 10 }

    /** 播种：把当前共享身份复制为指定行星的独立身份（已有独立身份或共享身份
     *  缺失时不动作）。切独立模式时让当前激活行星沿用现有身份，网络不中断。 */
    fun seedSharedIdentity(paths: StorePaths, planetId: String) {
        if (hasPlanetIdentity(paths, planetId)) return
        copyIdentity(from = paths.dataStoreRoot, to = paths.planetStateDir(planetId))
    }

    /** 采纳：把指定行星的独立身份复制为全局共享身份（覆盖原共享身份文件）。
     *  其余行星的独立身份文件原地保留，切回独立模式时原样恢复。 */
    fun adoptSharedIdentity(paths: StorePaths, planetId: String) {
        copyIdentity(from = paths.planetStateDir(planetId), to = paths.dataStoreRoot)
    }

    /** 成对复制身份文件（源缺失跳过；目标已存在先删除再复制——seed 场景由
     *  hasPlanetIdentity 挡，adopt 场景覆盖即语义）。失败记日志不抛（尽力而为：
     *  失败后独立模式该行星由核心生成全新身份、共享模式沿用原共享身份）。 */
    private fun copyIdentity(from: File, to: File) {
        for (name in listOf(IDENTITY_PUBLIC, IDENTITY_SECRET)) {
            val src = File(from, name)
            if (!src.exists()) continue
            runCatching {
                to.mkdirs()
                val dst = File(to, name)
                if (dst.exists()) dst.delete()
                src.copyTo(dst)
                if (name == IDENTITY_SECRET) {
                    // 私钥收紧为仅属主可读写（对齐 NodeRuntime.StorePutListener 的 secure 分支）
                    dst.setReadable(false, false)
                    dst.setWritable(false, false)
                    dst.setReadable(true, true)
                    dst.setWritable(true, true)
                }
            }.onFailure { Log.w(TAG, "身份文件复制失败 $name", it) }
        }
    }
}
```

注意：先看 `NodeRuntime.StorePutListener`（约 259-280 行）对 `identity.secret` 的权限收紧实际写法，若与本处不同则以它为准对齐（语义：仅属主可读写）。

- [ ] **Step 4: NodeRuntime 注入模式并重定向 identity 路径**

构造参数（约 72-73 行 `dataStoreRoot`/`planetDir` 之间或 `planetFile` 之后，插在 `planetFile` 之后）：

```kotlin
    private val identityPerPlanet: Boolean,
```

`stateFile`（约 236-242 行）identity 分支与文档注释改为：

```kotlin
    /**
     * dataStore 对象名 → 落盘文件。返回 null 表示未知对象（读写各按错误码上报）。
     * identity 按模式分流：共享模式落 dataStoreRoot（files/zt/，全行星共用，默认）；
     *  独立模式落 planetDir（files/zt/<planetId>/，构造参数快照注入，核心重启即
     *  模式切换生效）。moon/peer/netconf 按激活 planet 隔离（files/zt/<planetId>/）；
     *  planet 在自定义 planet 时重定向到导入文件（官方 planet 时读核心内置——
     *  文件不存在 stateGet 返回 -1 即可）。
     */
    internal fun stateFile(name: String): File? = when {
        name == "identity.public" || name == "identity.secret" ->
            if (identityPerPlanet) File(planetDir, name) else File(dataStoreRoot, name)
        name == "planet" -> planetFile ?: File(dataStoreRoot, "planet")
        name.startsWith("moons.d/") || name.startsWith("networks.d/") ||
            name.startsWith("peers.d/") -> File(planetDir, name)
        else -> null
    }
```

- [ ] **Step 5: ZeroTierVpnService 装配传参**

装配点（约 108-114 行，在 `s.launch` 协程内，`store` 已在场且 `readPrefs()` 是 suspend 可直接调用）：

```kotlin
            val r = NodeRuntime(
                dataStoreRoot = store.paths.dataStoreRoot,
                planetDir = store.paths.planetStateDir(planet.id),
                planetFile = planet.fileName?.let { File(store.paths.planetsDir, it) },
                identityPerPlanet = store.readPrefs().identityPerPlanet,
                scope = s,
                identityCollisionReason = getString(R.string.tunnel_status_fatalConflict),
            )
```

`dataStoreRoot = File(filesDir, "zt")` 原硬编码改用 `store.paths.dataStoreRoot`（等价：先 grep `StoreRepo` 单例构造确认其 filesRoot 即 `context.filesDir`，若不一致停下来报告，不要自行改 StoreRepo）。

- [ ] **Step 6: 构建验证**

Run: `./gradlew --no-daemon -q assembleDebug`
Expected: BUILD SUCCESSFUL（无新警告引入）。

- [ ] **Step 7: 提交**

```
feat(store): 节点身份模式存储层与核心身份路径按行星分流

AppPrefs 新增 identityPerPlanet 偏好（默认共享）；NodeRuntime 构造参数注入
模式快照，stateFile 在独立模式下把 identity.* 重定向到 per-planet 目录，
核心重启即模式切换生效。新建 IdentityStore 承担 App 侧身份复制（seed/adopt/
地址解析）——核心只在构造时读/生成身份、退出不回写，复制必须先于新核心启动。
StorePaths 暴露 dataStoreRoot 供隧道装配与身份复制同源取径。
```

---

### Task 2: 设置页 UI 与切换编排 + 三语文案

**Files:**
- Modify: `app/src/main/res/values/strings.xml`（prefs 区，按字母序插入）
- Modify: `app/src/main/res/values-en/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsViewModel.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsView.kt`

**Interfaces:**
- Consumes: Task 1 全部 Produces；现有 `write(mutate, onPersisted)`、`PickerRow`、`ModalBottomSheet` 范式（LanguagePickerSheet）、`common_cancel`
- Produces: `PrefsViewModel.setIdentityMode(perPlanet: Boolean, adopting: String?)`、`PrefsViewModel.sharedIdentityCandidates(): suspend List<Pair<PlanetProfile, String>>`（UI 消费）

- [ ] **Step 1: 三语 strings（10 个 key，文案逐条搬运 iOS）**

`values/strings.xml`（简体，按区 alphabetical：插在 `prefs_english_*`/`prefs_identity_*` 字母序位置——现有 prefs 区按前缀排序，插在 `prefs_english`（若存在）之后 `prefs_language` 之前；实际以文件内字母序为准）：

```xml
    <string name="prefs_identity_confirmMessage">当前行星沿用现有身份；其余行星首次连接时生成新身份，其已加入的网络需要重新授权。</string>
    <string name="prefs_identity_confirmSwitch">切换</string>
    <string name="prefs_identity_confirmTitle">切换为每行星独立身份？</string>
    <string name="prefs_identity_footer">所有行星共用一个节点身份，或每个行星使用独立身份。独立身份的行星之间互不可关联，适合隔离不同网络的隐私。</string>
    <string name="prefs_identity_mode">身份模式</string>
    <string name="prefs_identity_modePerPlanet">每行星独立</string>
    <string name="prefs_identity_modeShared">共享身份</string>
    <string name="prefs_identity_pickFooter">选定的身份将成为所有行星共用的节点身份。各行星现有独立身份保留，切回独立模式时恢复。</string>
    <string name="prefs_identity_pickTitle">选择共享身份</string>
    <string name="prefs_identity_sectionHeader">节点身份</string>
```

`values-en/strings.xml`（英文）：

```xml
    <string name="prefs_identity_confirmMessage">The current planet keeps its identity; other planets get a new one on first connect, and their networks must be authorized again.</string>
    <string name="prefs_identity_confirmSwitch">Switch</string>
    <string name="prefs_identity_confirmTitle">Switch to per-planet identities?</string>
    <string name="prefs_identity_footer">All planets share one node identity, or each planet uses its own. Separate identities cannot be correlated across planets, keeping networks isolated.</string>
    <string name="prefs_identity_mode">Identity Mode</string>
    <string name="prefs_identity_modePerPlanet">Per-Planet</string>
    <string name="prefs_identity_modeShared">Shared Identity</string>
    <string name="prefs_identity_pickFooter">The chosen identity becomes the node identity for all planets. Each planet\'s own identity is kept and restored when you switch back.</string>
    <string name="prefs_identity_pickTitle">Choose Shared Identity</string>
    <string name="prefs_identity_sectionHeader">Node Identity</string>
```

`values-zh-rTW/strings.xml`（繁体）：

```xml
    <string name="prefs_identity_confirmMessage">當前行星沿用現有身份；其餘行星首次連接時生成新身份，其已加入的網路需要重新授權。</string>
    <string name="prefs_identity_confirmSwitch">切換</string>
    <string name="prefs_identity_confirmTitle">切換為每行星獨立身份？</string>
    <string name="prefs_identity_footer">所有行星共用一個節點身份，或每個行星使用獨立身份。獨立身份的行星之間互不可關聯，適合隔離不同網路的隱私。</string>
    <string name="prefs_identity_mode">身份模式</string>
    <string name="prefs_identity_modePerPlanet">每行星獨立</string>
    <string name="prefs_identity_modeShared">共享身份</string>
    <string name="prefs_identity_pickFooter">選定的身份將成為所有行星共用的節點身份。各行星現有獨立身份保留，切回獨立模式時恢復。</string>
    <string name="prefs_identity_pickTitle">選擇共享身份</string>
    <string name="prefs_identity_sectionHeader">節點身份</string>
```

- [ ] **Step 2: PrefsViewModel 加切换编排与候选查询**

```kotlin
    /**
     * 切换节点身份模式（设置页身份模式选择触发，对齐 iOS AppState.switchIdentityMode）。
     * 独立模式：当前激活行星播种现有共享身份（沿用，正在用的网络不中断），其余
     * 行星首次连接时由核心生成新身份；共享模式：adopting 行星的独立身份复制为
     * 全局共享身份（null = 无行星有独立身份，直接切换），其余行星独立身份文件
     * 原地保留（切回独立模式时原样恢复）。
     *
     * 顺序约束：核心只在构造时读/生成身份、退出不回写，身份复制必须先于新核心
     * 启动——restartIfRunning 的 START 在停机终态后才下发，故复制完成后才发起
     * 重启即满足；复制放 IO 线程（文件虽小仍是磁盘操作），发起重启切回主线程
     * （restartIfRunning 的调用方收敛主线程，见其注释）。
     */
    fun setIdentityMode(perPlanet: Boolean, adopting: String?) {
        write({ it.copy(identityPerPlanet = perPlanet) }, onPersisted = {
            viewModelScope.launch(Dispatchers.IO) {
                val store = StoreRepo.store
                if (perPlanet) {
                    IdentityStore.seedSharedIdentity(store.paths, store.activePlanetId())
                } else if (adopting != null) {
                    IdentityStore.adoptSharedIdentity(store.paths, adopting)
                }
                withContext(Dispatchers.Main) {
                    TunnelController.restartIfRunning(getApplication())
                }
            }
        })
    }

    /** 切共享模式的候选行星（已有独立身份者），附节点地址供选择页展示（不可解析为 null）。 */
    suspend fun sharedIdentityCandidates(): List<Pair<PlanetProfile, String?>> {
        val paths = StoreRepo.store.paths
        return StoreRepo.store.readPlanets().filter {
            IdentityStore.hasPlanetIdentity(paths, it.id)
        }.map { it to IdentityStore.planetIdentityAddress(paths, it.id) }
    }
```

imports 补：`kotlinx.coroutines.Dispatchers`、`kotlinx.coroutines.withContext`、`cn.imgozi.planetport.store.IdentityStore`、`cn.imgozi.planetport.store.PlanetProfile`。

- [ ] **Step 3: PrefsView 身份 section（按需连接与语言之间，对齐 iOS 位置）**

状态区加（`showLanguagePicker` 旁）：

```kotlin
    var showIdentityModePicker by remember { mutableStateOf(false) }
    var showIdentityConfirm by remember { mutableStateOf(false) }
    var identityPickCandidates by remember { mutableStateOf<List<Pair<PlanetProfile, String?>>?>(null) }
```

section（插在「按需连接」FormSection 之后、「语言」FormSection 之前）：

```kotlin
            // 节点身份（对齐 iOS 身份 section：网络→按需连接→身份→语言）：模式行
            // 点开选择弹层；切独立先确认，切共享有候选先选身份（无候选直接切）
            FormSection(
                header = stringResource(R.string.prefs_identity_sectionHeader),
                footer = stringResource(R.string.prefs_identity_footer),
            ) {
                PickerRow(
                    title = stringResource(R.string.prefs_identity_mode),
                    value = stringResource(
                        if (prefs.identityPerPlanet) R.string.prefs_identity_modePerPlanet
                        else R.string.prefs_identity_modeShared
                    ),
                    onClick = { showIdentityModePicker = true },
                )
            }
```

- [ ] **Step 4: 模式选择弹层 + 确认弹窗 + 身份选择弹层**

弹层区（`showLanguagePicker` 的 if 之后）：

```kotlin
    // 身份模式选择（语言选择弹层同范式）：共享=当前项关闭；独立=确认弹窗；
    // 切共享查候选——空直接切（不弹选择页），非空进身份选择弹层
    if (showIdentityModePicker) {
        IdentityModeSheet(
            perPlanet = prefs.identityPerPlanet,
            onSelect = { target ->
                showIdentityModePicker = false
                when {
                    target == prefs.identityPerPlanet -> Unit
                    target -> showIdentityConfirm = true
                    else -> scope.launch {
                        val candidates = vm.sharedIdentityCandidates()
                        if (candidates.isEmpty()) {
                            vm.setIdentityMode(false, null)
                        } else {
                            identityPickCandidates = candidates
                        }
                    }
                }
            },
            onDismiss = { showIdentityModePicker = false },
        )
    }

    if (showIdentityConfirm) {
        AlertDialog(
            onDismissRequest = { showIdentityConfirm = false },
            title = { Text(stringResource(R.string.prefs_identity_confirmTitle)) },
            text = { Text(stringResource(R.string.prefs_identity_confirmMessage)) },
            confirmButton = {
                TextButton(onClick = {
                    showIdentityConfirm = false
                    vm.setIdentityMode(true, null)
                }) { Text(stringResource(R.string.prefs_identity_confirmSwitch)) }
            },
            dismissButton = {
                TextButton(onClick = { showIdentityConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    identityPickCandidates?.let { candidates ->
        IdentityPickSheet(
            candidates = candidates,
            onPick = { planetId ->
                identityPickCandidates = null
                vm.setIdentityMode(false, planetId)
            },
            onDismiss = { identityPickCandidates = null },
        )
    }
```

`PrefsView` 函数体内加协程作用域：`val scope = rememberCoroutineScope()`（imports 补 `androidx.compose.runtime.rememberCoroutineScope`、`kotlinx.coroutines.launch`、`androidx.compose.material3.AlertDialog`、`cn.imgozi.planetport.store.PlanetProfile`）。

私有 Composable（文件尾部，LanguageOptions 之后）：

```kotlin
/** 身份模式选择弹层（LanguagePickerSheet 同范式）：两行单选，点选回调目标模式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityModeSheet(
    perPlanet: Boolean,
    onSelect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.prefs_identity_mode),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                listOf(
                    false to stringResource(R.string.prefs_identity_modeShared),
                    true to stringResource(R.string.prefs_identity_modePerPlanet),
                ).forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = perPlanet == value, onClick = { onSelect(value) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** 切共享模式的身份选择弹层（对齐 iOS IdentityPickView）：行星名 + 节点地址
 *  （等宽小字，不可解析显示 "—"），footer 说明往返保真语义。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityPickSheet(
    candidates: List<Pair<PlanetProfile, String?>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.prefs_identity_pickTitle),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                candidates.forEach { (planet, address) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(planet.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(planet.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                address ?: "—",
                                style = PlanetFonts.MonoCaption,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.prefs_identity_pickFooter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

`PlanetFonts` import 已在文件头（`cn.imgozi.planetport.ui.theme.PlanetFonts`）。

- [ ] **Step 5: 构建验证**

Run: `./gradlew --no-daemon -q assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```
feat(prefs): 节点身份模式设置页与切换编排

设置页新增「节点身份」区（位置对齐 iOS：按需连接与语言之间）：模式行点开
选择弹层；切独立先确认（其余行星将生成新身份需重新授权），切共享先选采纳
身份（无候选直接切换），各行星独立身份原地保留、切回即恢复。切换经
setIdentityMode 落盘偏好后复制身份文件（seed/adopt，必须先于新核心启动），
再热重启隧道使新身份生效。三语文案对齐 iOS。
```

---

### Task 3: README ×3 加功能条目

**Files:**
- Modify: `README.md`（行星管理节，约 25-34 行）
- Modify: `README.en.md`
- Modify: `README.zh-Hant.md`

**Interfaces:** 无代码接口；文案逐字搬运 iOS feat 分支。

- [ ] **Step 1: 三语 README 各加一条（行星管理节末尾，「Moon 节点」条目之后）**

`README.md`：

```markdown
- **节点身份模式**：所有行星共用一个节点身份，或每个行星使用独立身份（互不可关联）；可随时切换，各行星身份保留、切回即恢复
```

`README.en.md`（加在其行星管理节末条之后）：

```markdown
- **Node identity mode** — share one node identity across all planets, or give each planet its own (uncorrelated); switch anytime — each planet's identity is kept and restored when you switch back
```

`README.zh-Hant.md`：

```markdown
- **節點身份模式**：所有行星共用一個節點身份，或每個行星使用獨立身份（互不可關聯）；可隨時切換，各行星身份保留、切回即恢復
```

注意：三份 README 行星管理节条目序与中文主文件一致，先各自确认「Moon 节点」条目是节末（英文/繁体文件条目措辞可能略有差异，以各文件实际节末为准追加，不改动既有条目）。

- [ ] **Step 2: 提交**

```
docs(readme): 行星管理节新增节点身份模式条目

三语 README 同步 iOS 已交付功能的说明：共享/独立身份可切换、往返保真。
```

---

## Self-Review 记录

- 语义覆盖：默认共享 / 单→多 seed 激活行星 / 多→单 adopt+保留 / 空候选直切 / 删行星级联（既有）/ 加行星惰性（零改动）✔
- 无占位：三任务代码全量给出；strings 三语全量 ✔
- 类型一致性：`IdentityStore` 签名在 Task 1 Produces 与 Task 2 消费处一致；`NodeRuntime` named arg `identityPerPlanet` 装配一致 ✔
- 用户规矩：无测试用例（以构建为门禁）；中文 Conventional Commits、无 Co-authored-by ✔
