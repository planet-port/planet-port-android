package cn.imgozi.planetport.tunnel

import android.content.Context
import android.net.VpnService
import cn.imgozi.planetport.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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
            else -> ZeroTierVpnService.stop(context.applicationContext)
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
        if (reconnectInFlight) {
            TunnelStateBus.log("停启编排进行中，合并本次网络切换触发")
            return
        }
        reconnectInFlight = true
        val app = context.applicationContext
        TunnelStateBus.setTunnelState(TunnelState.Reconnecting("network_change"))
        VpnNotification.update(app, app.getString(R.string.tunnel_status_reconnecting))
        TunnelStateBus.log("网络切换，重启核心")
        ZeroTierVpnService.stop(app)
        restartScope.launch {
            try {
                if (awaitSettled()) ZeroTierVpnService.start(app)
                else TunnelStateBus.log("重连等待停机终态超时，放弃迟到启动")
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
     */
    fun restartIfRunning(context: Context) {
        if (!isRunning) return
        if (reconnectInFlight) {
            TunnelStateBus.log("停启编排进行中，合并本次热重启触发")
            return
        }
        reconnectInFlight = true
        val app = context.applicationContext
        ZeroTierVpnService.stop(app)
        restartScope.launch {
            try {
                if (awaitSettled()) ZeroTierVpnService.start(app)
                else TunnelStateBus.log("重启等待停机终态超时，放弃迟到启动")
            } finally {
                reconnectInFlight = false
            }
        }
    }
}
