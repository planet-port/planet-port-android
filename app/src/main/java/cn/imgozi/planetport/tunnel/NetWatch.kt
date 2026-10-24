package cn.imgozi.planetport.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * 物理网络路径监听（切网自愈链的感知端，对齐 iOS NWPathMonitor +
 * handlePathUpdate，PacketTunnelProvider.swift:578-609）。
 *
 * minSdk 23 用不了 registerDefaultNetworkCallback（API 24+），改走 NetworkRequest
 * 形式注册 + activeNetwork 快照求值：NetworkRequest 只声明关注集（INTERNET +
 * NOT_VPN，NOT_VPN 过滤自反馈——tun0 自身是 VPN transport），真正判定以
 * activeNetwork 的 capabilities 快照为准，多网并存时取系统默认视角，即
 * NWPathMonitor 的语义。
 *
 * 判定（对齐 iOS :589-609）：首回调仅记基线 (satisfied, transport)；只有
 * regained（断网恢复）或 switched（在线且传输介质变化 wifi/cellular/ethernet/other）
 * 才触发；断网只记基线不触发——断网时无从重连，恢复时由 regained 兜住（iOS 同语义）。
 * 主线程 Handler 防抖 1.5s（对齐 iOS :596 asyncAfter(1.5s)），连续变化重置计时。
 *
 * 线程模型：回调线程只做 handler.post，快照求值/基线读写/防抖计时全部收敛在主
 * 线程串行执行，无锁；stop 清掉回调与动作引用后，残留的待决 runnable 自然 no-op，
 * 不泄漏服务引用。
 */
internal object NetWatch {
    private const val DEBOUNCE_MS = 1_500L

    /** 传输介质码（对齐 iOS NWInterface.InterfaceType 的判定粒度） */
    private const val TRANSPORT_NONE = -1
    private const val TRANSPORT_WIFI = 0
    private const val TRANSPORT_CELLULAR = 1
    private const val TRANSPORT_ETHERNET = 2
    private const val TRANSPORT_OTHER = 3

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var onPathChange: (() -> Unit)? = null

    /** 最近一次快照 (satisfied, transport)；null = 尚无基线 */
    private var lastPath: Pair<Boolean, Int>? = null

    /** 防抖到期动作：stop 后 onPathChange 已清空，残留待决项直接 no-op */
    private val fireDebounced = Runnable { onPathChange?.invoke() }

    /**
     * 注册路径监听（幂等：已注册时忽略，适配 START 重复指令与重连后重注册）。
     * [onPathChange] 在主线程防抖后回调，是否在跑由实现方自判。
     */
    @Synchronized
    fun start(context: Context, onPathChange: () -> Unit) {
        if (callback != null) return
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleEvaluate()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                scheduleEvaluate()

            override fun onLost(network: Network) = scheduleEvaluate()
        }
        this.cm = manager
        this.callback = cb
        this.onPathChange = onPathChange
        lastPath = null // 新一轮监听重新记基线
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                cb,
            )
        } catch (e: Exception) {
            // 注册失败只损失切网自愈能力，不影响隧道本身；回滚引用保持未注册态
            this.cm = null
            this.callback = null
            this.onPathChange = null
            TunnelStateBus.log("NetWatch 注册失败：${e.message}")
        }
    }

    /** 注销（幂等，重复调用无害）；先摘引用再反注册，杜绝回调窗口期再入 */
    @Synchronized
    fun stop() {
        val cb = callback ?: return
        val manager = cm
        callback = null
        onPathChange = null
        lastPath = null
        cm = null
        mainHandler.removeCallbacks(fireDebounced)
        if (manager != null) {
            try {
                manager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
                // 未注册/已注销等异常一律吞掉：注销失败的最坏结果是系统侧残留
                // 一个回调，但本侧引用已清、动作不会执行
            }
        }
    }

    private fun scheduleEvaluate() {
        mainHandler.post { evaluate() }
    }

    /** 主线程执行：activeNetwork 快照求值，regained/switched 才进 1.5s 防抖 */
    private fun evaluate() {
        val manager = cm ?: return
        @Suppress("DEPRECATION")
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val satisfied = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val transport = transportOf(caps)
        val last = lastPath
        lastPath = satisfied to transport
        if (last == null) return // 首回调仅记基线（iOS :591-594）
        val regained = !last.first && satisfied
        val switched = last.first && satisfied && last.second != transport
        if (!regained && !switched) return
        mainHandler.removeCallbacks(fireDebounced) // 连续变化重置计时
        mainHandler.postDelayed(fireDebounced, DEBOUNCE_MS)
        TunnelStateBus.log("网络路径变化（${describe(last)} → ${describe(satisfied, transport)}），防抖后重启核心")
    }

    private fun transportOf(caps: NetworkCapabilities?): Int = when {
        caps == null -> TRANSPORT_NONE
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TRANSPORT_WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TRANSPORT_CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TRANSPORT_ETHERNET
        else -> TRANSPORT_OTHER
    }

    private fun describe(satisfied: Boolean, transport: Int): String =
        if (!satisfied) "unsatisfied"
        else when (transport) {
            TRANSPORT_WIFI -> "wifi"
            TRANSPORT_CELLULAR -> "cellular"
            TRANSPORT_ETHERNET -> "ethernet"
            else -> "other"
        }

    private fun describe(path: Pair<Boolean, Int>): String = describe(path.first, path.second)
}
