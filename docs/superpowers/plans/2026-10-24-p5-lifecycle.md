# P5 收尾（稳定性生命周期）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 补齐锁屏/切网重连、开机自启两大生命周期能力，收敛 activatePlanet 重启编排竞态，消化 P4 终审归档批次，真机验收 DNS 热重配矩阵后全阶段闭合。

**Architecture:** 侦查报告 `.superpowers/sdd/p5-recon-report.md` 六节为依据。T1 以 ConnectivityManager 回调复刻 iOS NWPathMonitor→restartCore 自愈链并落地 `TunnelState.Reconnecting`；T2 BOOT receiver + `connectOnBoot` 偏好（Android 特有需求，iOS 等价物是 Connect On Demand）；T3 收敛重启编排为等终态语义；T4/T5/T6 消化 P4 归档批次（T6 死 key 裁决依赖 T1/T2/T5 落点，故置后）；T7 真机验收矩阵 + P5 终审。

**Tech Stack:** 既有栈（Kotlin/Compose/协程），无新依赖。T1/T2 仅用平台 API（ConnectivityManager/BroadcastReceiver）。

## Global Constraints

- 协议红线：GPL 血统零引入；唯一行为参考源 = planet-port-ios（只读）。
- 不写测试用例；`git --no-pager`；提交 = 中文 Conventional Commits、Body 必写、禁止署名尾注。
- 三语文案 values / values-zh-rTW / values-en 同步（zh-rTW 用台湾正体习惯，参考同文件既有条目）。
- iOS 行为权威对照点（侦查报告行号）：`handlePathUpdate` PacketTunnelProvider.swift:589-609、`restartCore` :204-218、Connect On Demand VPNManager.swift:172-175、on-demand 偏好键 AppGroup.swift:38。
- 死 key 复活优先于删除（T6 只删 T1/T2/T5 落地后仍死的 key）。
- 每任务 worktree 领地互斥由波次编排保证（见文末波次表）；实施前从主仓复制 `local.properties` 到 worktree。

---

### Task 1: 切网自愈链（NetWatch + Reconnecting + 等终态重启原语）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/NetWatch.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/TunnelController.kt`（restartIfRunning 重写 + reconnectForNetworkChange + awaitSettled 原语）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（startTunnel 成功后 NetWatch.start / stopTunnel NetWatch.stop；Reconnecting 通知文案接线）

**Interfaces:**
- Produces: `NetWatch.start(context, onPathChange: () -> Unit) / NetWatch.stop()`；`TunnelController.awaitSettled(timeoutMs)` suspend 原语（T3 消费）；`TunnelController.reconnectForNetworkChange(context)`。
- 消费既有：`TunnelState.Reconnecting`（TunnelStateBus.kt:14 已定义无人写入）、`VpnNotification.update(context, text)`（:21）、死 key `tunnel_status_reconnecting`（复活）。

**实现要点：**

1. `NetWatch`（单例，对齐 iOS handlePathUpdate 判定）：
   - `registerNetworkCallback(NetworkRequest.Builder().addCapability(NET_CAPABILITY_INTERNET).addCapability(NET_CAPABILITY_NOT_VPN).build(), callback)` —— NOT_VPN 过滤自反馈（tun0 自身是 VPN transport）；minSdk 23 故不用 registerDefaultNetworkCallback（API 24+）。
   - 回调 onAvailable/onCapabilitiesChanged/onLost 统一进 `evaluate()`：以 `cm.getNetworkCapabilities(cm.activeNetwork)` 快照求值（多网并存取系统默认视角 = NWPathMonitor 语义；activeNetwork 查询带 `@Suppress("DEPRECATION")`）。
   - 判定：首回调仅记基线 `(satisfied, transport)`（对齐 iOS :591-594）；`regained`（!satisfied→satisfied）或 `switched`（satisfied 且 transport 变化：wifi/cellular/ethernet/other）才触发；主线程 Handler 防抖 1.5s（对齐 iOS :596 的 asyncAfter(1.5s)），连续变化重置计时。
   - 断网（satisfied=false）只记基线不触发（iOS 同语义——断网时无从重连，恢复时 regained 触发）。
2. `TunnelController` 增补：
   ```kotlin
   private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

   /** 等隧道离开存活态（Running/Starting/Reconnecting → 终态或 Failed）；超时返回 false。 */
   internal suspend fun awaitSettled(timeoutMs: Long = 15_000): Boolean =
       withTimeoutOrNull(timeoutMs) {
           TunnelStateBus.tunnelState.first {
               it !is TunnelState.Running && it !is TunnelState.Starting && it !is TunnelState.Reconnecting
           }
       } != null

   /** 网络路径变化触发的核心整体重启（对齐 iOS restartCore :204-218）。 */
   fun reconnectForNetworkChange(context: Context) {
       if (!isRunning) return
       val app = context.applicationContext
       TunnelStateBus.setTunnelState(TunnelState.Reconnecting("network_change"))
       TunnelStateBus.log("网络切换，重启核心")
       ZeroTierVpnService.stop(app)
       restartScope.launch {
           if (awaitSettled()) ZeroTierVpnService.start(app)
           else TunnelStateBus.log("重连等待停机终态超时，放弃迟到启动")
       }
   }
   ```
   注：`Reconnecting(reason)` 的 reason 是技术串（同 Failed reason 范式），UI 侧文案由 TunnelState 类型映射，不消费 reason。
3. `restartIfRunning` 原语义保留（disableIpv6 等偏好路径用），但内部 1s 固定 Handler 延迟改用同一「stop → awaitSettled → start」时序（消除迟到启动顶回用户手动停止的竞态——T3 再统一收口调用面）。**本任务只改内部时序，不改对外签名**（PrefsViewModel.setDisableIpv6 调用点零改动）。
4. 服务接线：startTunnel 成功路径（NodeRuntime 起来后）`NetWatch.start(this) { TunnelController.reconnectForNetworkChange(this) }`；stopTunnel 收口处 `NetWatch.stop()`（幂等，重复 stop 无害）。
5. 通知：reconnectForNetworkChange 置 Reconnecting 后 `VpnNotification.update(app, app.getString(R.string.tunnel_status_reconnecting))`；重启完成走既有 Starting→Running 通知链。查 `ZeroTierVpnService` 现有状态→通知更新点，若已有 tunnelState 订阅则仅需确认 Reconnecting 分支文案；没有则按上述显式调用。

**验证：** assembleDebug 编译过；真机验证归 T7 矩阵（本任务不装机）。日志可通过 ApplyTrace/eventLog 页看到「网络切换，重启核心」。

**领地：** 只碰 tunnel/ 三文件。**UI 状态行的 Reconnecting 映射归 T3**（与 reason 映射同函数区，避免同波冲突）。不碰 NodeRuntime.kt（T3 领地）、不碰 strings.xml（复活的 key 已存在，直接引用）。

---

### Task 2: 开机自启（BootCompletedReceiver + connectOnBoot 偏好）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/tunnel/BootCompletedReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`（RECEIVE_BOOT_COMPLETED 权限 + receiver 声明）
- Modify: `app/src/main/java/cn/imgozi/planetport/store/Models.kt:100`（AppPrefs 加 `connectOnBoot: Boolean = false`，带默认值旧文件免迁移）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsViewModel.kt`（setConnectOnBoot）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsView.kt`（开关行，复用死 key `prefs_ondemand`/`prefs_ondemand_footer`）
- Modify: `app/src/main/res/values*/strings.xml`（**仅改这两个 key 的值**——footer 描述 Android 真实行为；title 保持 iOS 命名「按需连接（Connect On Demand）」跨端一致，footer 三语改为开机自启语义，如 zh：「开启后设备开机时自动启动 VPN；需已授权 VPN，实际连接哪些网络仍由各网络开关决定。」）

**Interfaces:**
- Produces: `AppPrefs.connectOnBoot`；`PrefsViewModel.setConnectOnBoot(on: Boolean)`。
- 消费既有：`TunnelController.isRunning`、`ZeroTierVpnService.start`、`StoreRepo.store.readPrefs`。

**实现要点：**

1. manifest：
   ```xml
   <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
   ```
   application 内：
   ```xml
   <receiver
       android:name="cn.imgozi.planetport.tunnel.BootCompletedReceiver"
       android:exported="false"
       android:enabled="true">
       <intent-filter>
           <action android:name="android.intent.action.BOOT_COMPLETED" />
       </intent-filter>
   </receiver>
   ```
   BOOT_COMPLETED 是 protected broadcast（仅 system 可发），exported=false 不拦系统投递。
2. receiver（产品语义：开关未开 / VPN 未授权（BOOT 无 UI 无法弹授权）/ 已在跑 → 三者均 no-op）：
   ```kotlin
   class BootCompletedReceiver : BroadcastReceiver() {
       override fun onReceive(context: Context, intent: Intent) {
           if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
           val app = context.applicationContext
           val prefs = runCatching {
               runBlocking { StoreRepo.store.readPrefs() }
           }.getOrNull() ?: return
           if (!prefs.connectOnBoot) return
           if (VpnService.prepare(app) != null) return
           if (TunnelController.isRunning) return
           ZeroTierVpnService.start(app)
       }
   }
   ```
   runBlocking 读 prefs.json 小文件在 onReceive 10s 限制内（既有 readPrefs 为挂起函数，IO 快）。
3. PrefsView 开关行样式对齐页内既有行（如「禁用 IPv6」行：标题+footer+Switch）；置位走 PrefsViewModel 既有 `write({ it.copy(connectOnBoot = on) })` 范式（纯偏好，无 onPersisted——不主动启停隧道，与 iOS `updateOnDemand` 只同步偏好不启隧道同构）。

**验证：** assembleDebug 编译过；真机 `adb reboot` 验证归 T7。

**领地：** manifest + Models.kt 仅加一行字段 + ui/prefs/ 两文件 + strings.xml 仅改两 key 的值（不增删 key——T6 领地）。不碰 tunnel/ 其它文件（T1/T4 领地）。

---

### Task 3: 归档①——驱动循环失败置 Failed（NodeRuntime）+ Reconnecting UI 映射

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/NodeRuntime.kt:141-144`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/HomeViewModel.kt`（Failed reason 消费映射 + TunnelState.Reconnecting 状态行映射）

**实现要点：** break 前置状态，对齐终审 B-3：
```kotlin
if (rc != ResultCode.RESULT_OK) {
    TunnelStateBus.log("processBackgroundTasks rc=$rc，停止驱动")
    TunnelStateBus.setTunnelState(
        TunnelState.Failed("core_driver:$rc", recoverable = true),
    )
    break
}
```
- reason 技术串 `"core_driver:$rc"` 与既有 Failed reason 装配范式一致；查 HomeViewModel 侧 Failed reason 消费点（identityCollisionReason 一类）确认未知 reason 落通用失败文案而非直显——若未知 reason 会直显技术串给用户，则消费侧补一条前缀映射（恒定资源文案），照 T7b 已定范式。
- **Reconnecting UI 状态行映射**（T1 移入）：查 `ztStatusTextRes`（或等价状态文案映射）对 `TunnelState.Reconnecting` 的映射现状——未映射则补映射（文案资源查现有 status 家族 key，三语有合适 key 复用之；没有才新增 key，命名对齐 iOS xcstrings）。
- `recoverable = true` 语义：服务进程仍活（scope/通知在），用户可显式停止或切网自愈（T1 链路会在下次网络事件重启核心）。
- keepalive/peers 协程不连带收口（评估结论：Failed 存活态下 keepalive 温热 UDP 无害，统一收口归服务 stopTunnel 既有链路；避免本任务扩散到 TunnelManager）。

**验证：** assembleDebug；状态置位链路靠 T7 真机回归（该分支正常运行期不可达，属防御性修复）。

**领地：** NodeRuntime.kt + HomeViewModel 的映射函数区（波 1 内唯一 HomeViewModel 触碰方——T4 波 2 才碰该文件，串行消歧）。

---

### Task 4: 归档②③④⑤——UI 清理组（技术串/写点兜底/死码/遮蔽）

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/BackgroundImagePickerSheet.kt:124-127`（未知异常分支）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsViewModel.kt:72-74`（else 分支）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/HomeViewModel.kt`（10 写点兜底 + joinNetwork 死回调清理）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/HomeView.kt`（joinNetwork 调用点删 onDuplicate 实参）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/QrScannerSheet.kt:140`（DeniedGuide 删 canRetry 死参及调用点实参）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/HomeParallaxBackground.kt:102-108`（私有 HomeMetrics 改名 ParallaxMetrics，含 :53/:64/:65 使用点）
- Modify: `app/src/main/res/values*/strings.xml`（新增 `bg_error_generic` 一条 ×3）

**实现要点：**

1. ② 背景图技术串：两处未知异常分支改 `getString(R.string.bg_error_generic)` + `Log.w("BgImage", "apply 失败", failure)`；TooLarge/NotImage 分支不动（已是专用资源文案）。新 key 三语：zh「无法应用背景图」zh-rTW「無法套用背景圖」en「Failed to apply background image」。
2. ③ HomeViewModel 写点兜底（目标=写盘 IOException 不崩进程；不弹 UI 提示——HomeViewModel 无提示基建，扩张不值）：加私有 helper 并逐点包裹（对齐 MoonViewModel 既有 runCatching 范式）：
   ```kotlin
   private suspend fun <T> storeWrite(tag: String, block: suspend () -> T) {
       runCatching { block() }.onFailure { Log.w("HomeVM", "$tag 写盘失败", it) }
   }
   ```
   10 处清单（侦查 §5③ 表格行号）：:94 resetAllNetworkActivation / :191 selectPlanet / :232 writeNetworks(关) / :261 writeNetworks(开) / :291 activatePlanet / :349 writeNetworks(加入) / :372 selectPlanet(导入后) / :439 writeNetworks(冲突回写) / :462 writePlanets / :482 writeNetworks(名称回写)。注意行号会随编辑漂移，按调用语义定位；`applySelectedPlanetAndStart` 内 activatePlanet 的返回值/后续动作语义保持不变（失败只记日志，重启联动照走——指针未换成功时重启无害幂等）。
3. ④ 死码收敛：查重归属定为 **UI 侧自查**（JoinNetworkSheet.kt:84-88 已在点按前拦截）——删 HomeViewModel.joinNetwork 的 `onDuplicate` 参数与 HomeView 调用点 `{ duplicateAlert = true }` 实参；QrScannerSheet DeniedGuide 删 `canRetry: Boolean` 参数与函数体内注释引用、调用点 `canRetry = asked` 实参与 `asked` 变量（若变空则一并清）。
4. ⑤ 遮蔽消除：`HomeParallaxBackground.kt` 私有 object `HomeMetrics` 改名 `ParallaxMetrics`（不并入 theme 常量表——两个常量仅视差背景消费，就近内聚更合理；仅消除与 `ui/theme/HomeMetrics.kt` 的同名二义）。

**验证：** assembleDebug；grep 复核死参/死回调零残留、`bg_error_download` 消费点只剩下载场景、新 key 三语齐全。

**领地：** ui/ 五文件 + strings.xml 仅增一条。不碰 tunnel/（T1/T3 领地）、不碰 Models.kt/PrefsView 的 ondemand 区（T2 领地——本任务碰 PrefsViewModel 的 applyBackgroundImage 区与 T2 的 setConnectOnBoot 区同文件不同函数，波次上 T2 先行合入，冲突面为零）。

---

### Task 5: activatePlanet 重启编排收敛（等终态消费 + 删除路径门控核对）

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/TunnelController.kt`（若 T1 已把 restartIfRunning 等终态化则本任务核验签名与注释一致性）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/HomeViewModel.kt:290-313`（applySelectedPlanetAndStart 注释对齐 + Failed 存活态分支复核）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsViewModel.kt:108-117`（deletePlanet 停机门控核对）

**实现要点：**

1. T1 已落地 `awaitSettled` 与等终态 restartIfRunning——本任务核对 `applySelectedPlanetAndStart` 三分支（在跑且 planetChanged → restartIfRunning；未跑 → ensureStarted；Failed 存活态 → 先 stop 再启）在新时序下语义不变，KDoc 中「1s 窗口」表述更新为等终态语义。
2. 删除路径门控核对（侦查 §4 疑点）：`PlanetSettingsViewModel` 现逻辑=「删除的是激活行星 → 先 TunnelController.stop 再 deletePlanet」。核对两种漏网：①删除非激活行星但隧道在跑（不需要停——核心未装载该 planet，核对结论写注释）；②删除激活行星后 `deletePlanet` 内部回退官方+notifyConfigChanged（AppConfigStore.kt:135-136）——该通知会触发 TunnelManager.applyConfig 但 planetDir 在服务启动期固化，**热重载不换目录**——所以激活行星删除必须经显式重启才能落到官方 planet。核对现有「先 stop 再 delete」完成后隧道停在 Stopped，用户需手动开——是否对齐 iOS（iOS 删除走 PlanetSettingsView 同样先 stop）；若 iOS 删除后自动重启回官方，则 Android 补 restartIfRunning(deletePlanet 后)——以 iOS 源（PlanetSettingsView/对应 VM）为准裁定。
3. 不改 TunnelStateBus（T1 领地已稳定）。

**验证：** assembleDebug；真机「切行星 → 隧道重启 → nodeId/网络恢复」归 T7。

**领地：** TunnelController 仅注释/核验级改动（T1 波 1 已合入）；HomeViewModel 是 T4 之后的串行第二改（波次保证）。

---

### Task 6: 归档⑥——死 key 终裁 + MPL 归属声明 + deprecated 三项

**Files:**
- Modify: `app/src/main/res/values/strings.xml` + `values-zh-rTW/` + `values-en/`（删仍死 key）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsView.kt`（开源声明区）
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsViewModel.kt`（版本行下加声明文案出口，如需）
- Modify: `app/src/main/java/cn/imgozi/planetport/tunnel/ZeroTierVpnService.kt`（stopForeground deprecated 修复 + 视核查 identity.secret 权限）
- Modify: `app/src/main/res/values*/strings.xml`（新增 about/opensource key ×3）

**实现要点：**

1. **死 key 终裁**（T1/T2/T4 落地后重跑求差验证：全量 key − 全量 `R.string./@string/` 引用）：
   - T1 复活：`tunnel_status_reconnecting`（通知+可能 UI）；`tunnel_status_reconnectFailed` 若 T1 未消费（重连失败走 Reconnecting→Starting 通用失败文案）则删。
   - T2 复活：`prefs_ondemand`、`prefs_ondemand_footer`。
   - 其余候选（侦查清单 25 个 − 上述复活）：逐个 grep 确认仍死后删，三语同步。重点甄别组：`status_connected/status_connecting/status_pendingAdd`（UI 状态行若用别的家族 key 则这批是 iOS 移植冗余，删）；`tunnel_error_socketBind/socketCreate`（UdpCom 建链失败文案——查服务 Failed 置位现状是否引用，未引用则删）；`home_alert_cellular*/home_error_cellular*`（iOS 蜂窝授权场景，Android 无对应交互，删）；`prefs_network_allowCellular`（同上，删）；`vpn_error_*`（iOS NE 配置保存失败场景，Android 无对应，删）；`peers_refreshHint`、`common_copyId`、`error_planet_reservedName`、`home_a11y_activePlanetHint`、`tunnel_status_applyFailed/startFailed`（Android 用各自资源化 key，删）。
   - 删除后 lint 无 UnusedResources 报警级残留（grep 求差复核为准）。
2. **MPL-2.0 归属声明**（P4 终审归档项）：PrefsView 版本信息区下加「开源声明」行（无交互或点开 AlertDialog 均可，从简：静态 footer 行）。文案三语，zh 示例：「本应用内含 ZeroTier® 核心（MPL-2.0，源码随应用分发，详见 github.com/zerotier/ZeroTierOne）；界面基于自研实现。」key 命名 `about_opensource`。®商标标注与措辞从简，MPL 2.0 要求的源码可得性由 externals/ 源码随仓满足，声明为补充。
3. **deprecated 三项**：
   - `stopForeground(true)` → `stopForeground(Service.STOP_FOREGROUND_REMOVE)`（API 24+ 常量，minSdk 23 之上编译目标已满足；若调用点编译报 API 门槛则 `if (Build.VERSION.SDK_INT >= 24) ... else @Suppress stopForeground(true)` 双分支——先查当前 targetSdk/minSdk 实况再定）。
   - identity.secret 文件权限 0600：查 StorePaths/identity 写入点现状权限（默认 0644）——收紧为 0600（`file.setReadable(false, false); file.setReadable(true, true)` 等四连或 PosixFilePermissions，按项目现有 IO 风格）。
   - `socket @Volatile`：P3 顺手项已做（progress.md P3 段「UdpCom @Volatile」），核实后在本任务报告记「已闭合，无需改动」。

**验证：** assembleDebug + 全量 grep 求差零未引用 key 残留（`app_name`/`dns_mode` 两个已知误报排除）。

**领地：** strings.xml（增删 key）、PrefsView 声明区、ZeroTierVpnService 杂项（stopForeground 调用点与 T1 通知接线不同段；波次上 T1 已合入）。

---

### Task 7: 真机验收矩阵 + P5 终审 + iOS 1:1 终核

**Files:** 无代码（验收与归档；发现问题则按 sdd 流程派修复）。

**矩阵：**
1. DNS 三模式热重配（对齐 iOS 三模式语义）：隧道在跑单网 → 详情页切「不使用/网络DNS/自定义」 → 「修改后需点应用」流程 → 每步 `adb shell dumpsys connectivity | grep -A3 tun` / GetProp 核对 TUN dns 条目变化；多网态 → 一律不设 DNS 断言；隧道未跑改配置 → 起隧道后生效断言。断言「DNS 变更触发单次 TUN 重建（签名闸门后）而非服务重启」（eventLog/applyTrace 核）。
2. 切网自愈：隧道在跑 → 关 WiFi（蜂窝或断网）→ 开 WiFi → 观察 Reconnecting 状态行/通知 → Running 恢复 + 双端 ping 通。WiFi↔蜂窝切换（设备有 SIM 时）同验 switched 分支。
3. 开机自启：prefs 开 connectOnBoot → `adb reboot` → 开机后（等待 60s）隧道自起、网络恢复 ONLINE；开关关 → reboot 不自起。
4. activatePlanet 联动：切自定义 planet → 隧道等终态重启 → nodeId 变化（planet 不同节点身份不同）/网络恢复；删除激活行星 → 按核对的 iOS 语义断言。
5. 归档批次回归：背景图未知失败路径（URL 拉 404 图片）文案非技术串；joinNetwork 查重仍由 UI 自查拦截；视差背景正常；About 声明显示。
6. 全量回归：加网/删网/双网/routeVia/被踢/Moon 导入/扫码/语言切换/主题。

**终审：** whole-branch review（P5 全 commits），范围=协议合规 + 业务逻辑 + 与计划符合度。

**iOS 1:1 终核（循环结束条件）：** 全阶段闭合后，按 roadmap 承诺做一次与 iOS 的功能与交互 1:1 核对——逐页面（Home/Prefs/PlanetSettings/Peers/Moon/详情/扫码/添加网络）对照 iOS 源码行为与文案，产出核对报告，差异逐条裁决（对齐/Android 特有/不修）。

---

## 波次编排（worktree 并行 + 领地互斥）

| 波次 | 任务 | 领地 | 并行性 |
|---|---|---|---|
| 1 | T1 切网自愈 ∥ T2 开机自启 ∥ T3 驱动失败置位+UI映射 | T1=tunnel/{NetWatch新,TunnelController,ZeroTierVpnService通知段}；T2={manifest,Models.kt一行,ui/prefs两文件,strings两值}；T3={NodeRuntime,HomeViewModel映射函数区} | 3 并行（T1/T3 同在 tunnel 包但文件不相交；strings.xml 仅 T2 碰且只改值；HomeViewModel 仅 T3 碰） |
| 2 | T4 UI 清理组 | ui/home 三文件+ui/prefs 两文件+strings 增一 | 单发（HomeViewModel 与波 1 T3 串行消歧；PrefsViewModel 与 T2 同文件不同函数，波 1 已合入） |
| 3 | T5 planet 编排收敛 ∥ T6 死 key 终裁+归属+deprecated | T5={TunnelController注释,HomeViewModel,PlanetSettingsViewModel}；T6={strings×3,PrefsView声明区,ZeroTierVpnService杂项} | 2 并行（T5 碰 HomeViewModel 与 T4 串行消歧已由波次保证；T6 strings 与 T4 增 key 串行已保证） |
| 4 | T7 真机验收+终审+iOS 1:1 终核 | 无代码 | 串行收尾 |

依赖链：T6 依赖 T1/T2/T4（死 key 复活落点）；T5 依赖 T1（awaitSettled）与 T4（HomeViewModel 串行）；T7 依赖全部。
