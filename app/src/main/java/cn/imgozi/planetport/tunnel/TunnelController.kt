package cn.imgozi.planetport.tunnel

import android.content.Context
import android.net.VpnService
import android.os.Handler
import android.os.Looper

/**
 * 隧道启停编排门面（UI 层唯一入口；对齐 iOS VPNManager 职责）。
 *
 * 只做「判态 + 转发 + 授权编排」，不持有隧道内部对象；状态判定读
 * TunnelStateBus（同进程 StateFlow，无 IPC）。上下文一律转 applicationContext，
 * 防止 Activity 泄漏到延迟回调。
 */
object TunnelController {

    /**
     * 隧道是否存活：Running/Starting/Reconnecting 视为在跑；
     * Failed/Stopped/Idle 视为不在（Failed 的各分支服务侧均已自清理）。
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

    /** 停止隧道（幂等）：仅在跑时下发 STOP，避免对已死服务空跑一轮启停。 */
    fun stop(context: Context) {
        if (!isRunning) return
        ZeroTierVpnService.stop(context.applicationContext)
    }

    /**
     * 在跑则热重启（disableIpv6 等仅重启生效的偏好用，对齐 iOS VPNManager
     * restartIfRunning）：停 + 1s 后启；未跑 no-op（偏好已落盘，下次启动自然生效）。
     * 停机是异步收口（join 消费协程/关 Node），1s 窗口保证 UDP 9994 释放与
     * native 资源落定。窗口内用户手动停止则会被这次迟到启动顶回（与 iOS 同构的
     * 既有竞态，窗口短可接受）。
     */
    fun restartIfRunning(context: Context) {
        if (!isRunning) return
        val app = context.applicationContext
        ZeroTierVpnService.stop(app)
        Handler(Looper.getMainLooper()).postDelayed({
            ZeroTierVpnService.start(app)
        }, 1_000)
    }
}
