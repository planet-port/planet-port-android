package cn.imgozi.planetport.tunnel

import android.content.Context
import android.net.VpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 隧道启停编排门面（UI 层唯一入口；对齐 iOS VPNManager 职责）。
 *
 * 只做「判态 + 转发 + 授权编排」，不持有隧道内部对象；状态判定读
 * TunnelStateBus（同进程 StateFlow，无 IPC）。上下文一律转 applicationContext，
 * 防止 Activity 泄漏到延迟回调。
 */
object TunnelController {

    /** 重启编排：只跑「等停机终态 → 再启动」的短任务，主线程即时派发（无长期驻留协程） */
    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 启动意图窗口（见 [startIntentAtMs]） */
    private const val START_INTENT_WINDOW_MS = 30_000L

    /**
     * 停启编排进行中标记：切网重连与偏好热重启共用（QF-3，.superpowers/sdd/
     * p5-task-1-review.md——两路都是 stop → awaitSettled → start，交错最坏多一轮
     * 停启循环，用同一标记互斥合并）。窗口（STOP 下发 → 停机终态 → START 下发）
     * 内的新触发一律记日志忽略：合并期间核心/配置本来就要经停启重新生效，不损失
     * 语义；全部调用方收敛主线程（NetWatch 防抖 / ViewModel 主线程），check-then-set
     * 无实际竞态，@Volatile 属保险。
     */
    @Volatile
    private var reconnectInFlight = false

    /**
     * 停启代际计数：用户主动 [stop] 实际下发 STOP 时自增。编排协程在窗口前捕获
     * 当前代际、START 下发前比对——窗口内用户停止会使代际失效，迟到的 START
     * 据此放弃，防「stop → awaitSettled → start」把用户主动停止顶回。
     * 只在真正下发 STOP 时自增：终态下的幂等 no-op 不作数（用户没有实际干预）。
     * stop 收敛主线程、编排协程在 restartScope（Main.immediate）同线程，无实际
     * 竞态，@Volatile 属保险（与 [reconnectInFlight] 同范式）。
     */
    @Volatile
    var stopGeneration = 0
        private set

    /**
     * 最近一次隧道启动意图时间戳（对齐 iOS VPNManager.startIntentAt，30s 窗口）。
     * UI 据此区分「隧道拉起中」与「意外停止」（onRevoke 被其它 VPN 挤掉/用户从
     * 系统快捷开关停止等）：所有拉起路径（含先停后起的切换/热重启序列、Failed
     * 态先 stop 再启的兜底）入口先打点，观察到隧道在跑或启动明确失败即清除；
     * Stopped/Idle 边沿若窗口外到来，判定为意外停止，网络激活回落掉线状态。
     * 30s 窗口既覆盖停启编排的拆建间隙，也兜底 START 被吞时不至于永久卡
     * 「建立隧道」。收敛主线程读写，@Volatile 属保险（与 [reconnectInFlight] 同范式）。
     */
    @Volatile
    private var startIntentAtMs = 0L

    /** 启动意图打点（拉起编排入口调用） */
    fun markStartIntent() {
        startIntentAtMs = System.currentTimeMillis()
    }

    /** 是否处于启动意图窗口内 */
    val startIntentActive: Boolean
        get() = System.currentTimeMillis() - startIntentAtMs < START_INTENT_WINDOW_MS

    /** 清除启动意图（隧道已观察到在跑/启动明确失败时调用） */
    fun clearStartIntent() {
        startIntentAtMs = 0
    }

    /**
     * 隧道是否存活：Running/Starting/Reconnecting 视为在跑；
     * Failed/Stopped/Idle 视为不在（注意：establish 失败的 Failed 态服务进程
     * 仍存活——scope 未取消、前台通知在，仅数据面未建立，见 stop 的门控）。
     */
    val isRunning: Boolean
        get() = when (TunnelStateBus.tunnelState.value) {
            is TunnelState.Running, is TunnelState.Starting, is TunnelState.Reconnecting -> true
            else -> false
        }

    /**
     * 确保隧道已启动（幂等）：在跑 no-op；未跑先过 VPN 授权再启动。
     *
     * [requestPermission] 为授权请求器注入（MainActivity.requestVpnPermission 持有
     * 已注册的 ActivityResultLauncher，object 无法持有 Activity 上下文，故经参数传入）；
     * 已授权（prepare 返回 null）时直接启动，不经授权器。[onPermissionDenied] 供
     * 授权器在用户拒绝时回调（当前 MainActivity 实现对拒绝静默，提示位由 UI 任务接）。
     */
    fun ensureStarted(
        context: Context,
        requestPermission: (onGranted: () -> Unit) -> Unit,
        onPermissionDenied: () -> Unit = {},
    ) {
        if (isRunning) return
        markStartIntent()
        val app = context.applicationContext
        if (VpnService.prepare(context) == null) {
            ZeroTierVpnService.start(app)
            return
        }
        requestPermission { ZeroTierVpnService.start(app) }
    }

    /**
     * 停止隧道（幂等）：终态（Idle/Stopped）不下发；存活态与 Failed 均放行——
     * establish 失败的 Failed 态服务仍活着（scope 存活、通知「运行中」），若按
     * isRunning 门控会静默 no-op，用户从 App 内停不掉只能靠系统撤销；服务侧
     * stopTunnel 幂等，对终态外的一切状态下发 STOP 都安全
     */
    fun stop(context: Context) {
        when (TunnelStateBus.tunnelState.value) {
            is TunnelState.Idle, is TunnelState.Stopped -> return
            else -> {
                // 用户主动干预：代际自增使进行中编排窗口的迟到 START 失效
                stopGeneration++
                // STOP intent 异步落地，StateFlow 仍显示存活态：标记在途供快速
                // 路径排除（见 TunnelStateBus.stopInFlight），stopTunnel 收口时清
                TunnelStateBus.stopInFlight = true
                ZeroTierVpnService.stop(context.applicationContext)
            }
        }
    }

    /**
     * 等隧道离开存活态（Running/Starting/Reconnecting → Stopped/Idle/Failed）；
     * 超时返回 false。供「stop → 等终态 → start」编排用：停机是异步收口
     * （消费协程 join 上限 500ms + JNI shutdown），固定延迟无法覆盖所有路径，
     * 以状态流事件为准才不会把 START 递进未停完的服务。
     */
    internal suspend fun awaitSettled(timeoutMs: Long = 15_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            TunnelStateBus.tunnelState.first {
                it !is TunnelState.Running && it !is TunnelState.Starting && it !is TunnelState.Reconnecting
            }
        } != null

    /**
     * 网络路径变化触发的核心整体重启（对齐 iOS restartCore，PacketTunnelProvider.swift:204-218）。
     * 核心缓存的直连路径基于旧网络的 NAT 映射，接口切换后失效且无法主动重置——
     * Android UDP 虽是通配 bind 天然容忍，但核心重建才能重探测直连路径，与 iOS 同理。
     *
     * 时序：置 Reconnecting（通知/状态行可见）→ STOP → 等停机终态 → START。
     * 未跑 no-op；编排窗口内的重复触发合并（见 [reconnectInFlight]）；等待超时只
     * 记日志放弃迟到启动，不留半启动状态（停机侧 join/JNI 收口有界，超时即进程级
     * 异常，交由用户手动重连）。
     *
     * QF-2（.superpowers/sdd/p5-task-1-review.md）平台差异记录：随后的 stopTunnel
     * 会置 Stopped 并经 startForeground("已停止") + stopForeground(true) 移除通知，
     * 故「重连中」文案实际仅存活亚秒级、且通知短暂消失——iOS restartCore 期间持续
     * 显示 reconnecting（swift:204-211）。Android FGS 的 5s 前台义务使停机链的
     * stopForeground 不可避免，属平台差异；Starting→Running 通知链完整，若 T3 要
     * 抹平可在 UI 状态行把 Stopped→Starting 区间显示为重连中，T7 真机观察实际观感。
     */
    fun reconnectForNetworkChange(context: Context) {
        if (!isRunning) return
        // 入口即打点：STOP 到 START 之间存在 Stopped 边沿，UI 需识别这是
        // 重启序列而非意外停止，不清网络激活
        markStartIntent()
        if (reconnectInFlight) {
            TunnelStateBus.log("停启编排进行中，合并本次网络切换触发")
            return
        }
        reconnectInFlight = true
        // 窗口前捕获代际：START 前比对，窗口内用户主动停止则放弃迟到启动
        val generationAtStop = stopGeneration
        val app = context.applicationContext
        // 通知文案由服务侧 tunnelState collector 驱动（T6 订阅化），此处只置态
        TunnelStateBus.setTunnelState(TunnelState.Reconnecting("network_change"))
        TunnelStateBus.log("网络切换，重启核心")
        ZeroTierVpnService.stop(app)
        restartScope.launch {
            try {
                when {
                    !awaitSettled() ->
                        TunnelStateBus.log("重连等待停机终态超时，放弃迟到启动")
                    stopGeneration != generationAtStop ->
                        TunnelStateBus.log("编排窗口内用户已停止，放弃迟到启动")
                    else -> ZeroTierVpnService.start(app)
                }
            } finally {
                reconnectInFlight = false
            }
        }
    }

    /**
     * 在跑则热重启（disableIpv6 等偏好的可靠生效路径，对齐 iOS VPNManager
     * restartIfRunning）：停 → 等停机终态 → 启；未跑 no-op（偏好已落盘，下次启动
     * 自然生效）。原 1s 固定延迟改走 [awaitSettled]：START 只在服务真正停完后下发，
     * 消除「停机慢于固定窗口时迟到启动撞进收口中」的竞态；等待期也把顶回用户手动
     * 停止的窗口从固定 1s 压缩到实际停机耗时（窗口内用户主动停止的完全裁决归 T3
     * 调用面收口）。
     *
     * 与 [reconnectForNetworkChange] 共用 [reconnectInFlight] 互斥（QF-3）：任一
     * 停启编排进行中时另一路触发合并跳过，避免两路 stop/start 交错最坏多一轮循环。
     *
     * disableIpv6 已纳入 TunnelManager 的 appliedEntries 比较（configChanges 驱动
     * 的 applyConfig 可感知并热重套），但 writePrefs 静默不发变更信号——UI 写完
     * 偏好仍须经本方法显式触达隧道。
     *
     * 另一消费点是行星切换（HomeViewModel.applySelectedPlanetAndStart）：核心
     * planet 文件与状态目录只在服务启动时装载（startTunnel 构造 NodeRuntime 时
     * 固化 planetDir/planetFile），configChanges 驱动的 applyConfig 只重套激活
     * 行星的网络/月壳、不换 planet——activatePlanet 换指针后必须经本方法整隧道
     * 重启才能生效（删激活行星回退官方同理由下次启动装载，见 PlanetSettingsViewModel）。
     */
    fun restartIfRunning(context: Context) {
        if (!isRunning) return
        // 入口即打点（先于 isRunning 判定后的两分支）：STOP 到 START 之间存在
        // Stopped 边沿，UI 需识别这是热重启序列而非意外停止，不清网络激活
        markStartIntent()
        if (reconnectInFlight) {
            // 切网自愈编排不能像丢普通热重启那样丢弃本次触发：行星切换经此路径时，
            // 在飞编排的 START 可能已带旧指针起核（startTunnel 读盘与 activatePlanet
            // 写盘竞态），吞掉重启会让旧 planet 文件的核心 join 新行星的网络——
            // 经旧根服务器取不到 netconf，持续连接失败。排队等在飞编排
            // 收口后补发：届时重读指针，确保核心按新行星装载
            TunnelStateBus.log("停启编排进行中，排队等待收口后补发热重启")
            val app = context.applicationContext
            restartScope.launch {
                // 有界等待：awaitSettled 上限 15s，20s 仍收口即放弃（进程级异常场景）
                var waited = 0
                while (reconnectInFlight && waited < 20_000) {
                    delay(200)
                    waited += 200
                }
                when {
                    reconnectInFlight ->
                        TunnelStateBus.log("等待在飞编排收口超时，放弃后补重启")
                    // 收口后隧道又被停掉（用户主动/编排失败）：直接起核读新指针，
                    // 授权此前已具备（触发时隧道在跑），不经授权器
                    !isRunning -> ZeroTierVpnService.start(app)
                    else -> restartIfRunning(app)
                }
            }
            return
        }
        reconnectInFlight = true
        // 窗口前捕获代际：START 前比对，窗口内用户主动停止则放弃迟到启动
        val generationAtStop = stopGeneration
        val app = context.applicationContext
        ZeroTierVpnService.stop(app)
        restartScope.launch {
            try {
                when {
                    !awaitSettled() ->
                        TunnelStateBus.log("重启等待停机终态超时，放弃迟到启动")
                    stopGeneration != generationAtStop ->
                        TunnelStateBus.log("编排窗口内用户已停止，放弃迟到启动")
                    else -> ZeroTierVpnService.start(app)
                }
            } finally {
                reconnectInFlight = false
            }
        }
    }
}
