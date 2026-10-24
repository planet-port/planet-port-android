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
 * minSdk 23 用不了 registerDefaultNetworkCallback（API 24+），走 NetworkRequest
 * 形式注册（INTERNET + NOT_VPN；NOT_VPN 过滤 tun0 自反馈）。**「当前路径」不由
 * activeNetwork 快照求值**——QF-1（.superpowers/sdd/p5-task-1-review.md）：本 app
 * 无 addDisallowedApplication 且 UdpCom 必须 protect，establish 后默认路由指向自家
 * TUN，activeNetwork 返回的是自家 VPN 网络；且 Android 12 之前 VPN 网络 caps 仅含
 * TRANSPORT_VPN、不传播底层 transport（Android 12+ 才继承），于是 API 23–30 上
 * transport 恒 other，wifi↔蜂窝切换全部漏检。改为**回调自帽数据建候选表**：
 * onCapabilitiesChanged 用参数 caps 记表（VPN/失格网络出表，注册已带 NOT_VPN，
 * 表内必然是物理网），onLost 移除；satisfied = 表非空，transport = 表内最强者
 * （ethernet > wifi > cellular > other，同级任一）。方案版本无关，不依赖
 * activeNetwork 的默认视角。
 *
 * 判定（对齐 iOS :589-609）：首次求值仅记基线 (satisfied, transport)；只有
 * regained（断网恢复）或 switched（在线且传输介质变化）才触发；断网（表清空）
 * 只记基线不触发——断网时无从重连，恢复时由 regained 兜住（iOS 同语义）。
 * 主线程 Handler 防抖 1.5s（对齐 iOS :596 asyncAfter(1.5s)），连续变化重置计时，
 * **读数与判定都压到防抖到期时**：切换瞬态（默认网关未翻转、新网 capabilities 未到）
 * 的中间态不会作为读数写进基线，漏检不依赖后续回调补救。
 *
 * 线程模型：回调线程只做候选表增删（单锁互斥）与 handler.post；基线读写/求值/触发
 * 全部收敛主线程。stop 先摘回调与动作引用再反注册，残留的待决 runnable 因
 * onPathChange 已清空而 no-op，不泄漏服务引用。
 */
internal object NetWatch {
    private const val DEBOUNCE_MS = 1_500L

    /** 传输介质码（对齐 iOS NWInterface.InterfaceType 的判定粒度） */
    private const val TRANSPORT_NONE = -1
    private const val TRANSPORT_WIFI = 0
    private const val TRANSPORT_CELLULAR = 1
    private const val TRANSPORT_ETHERNET = 2
    private const val TRANSPORT_OTHER = 3

    /** 取表内最强者的优先级：ethernet > wifi > cellular > other（同级任一） */
    private fun priorityOf(transport: Int): Int = when (transport) {
        TRANSPORT_ETHERNET -> 3
        TRANSPORT_WIFI -> 2
        TRANSPORT_CELLULAR -> 1
        else -> 0
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var onPathChange: (() -> Unit)? = null

    /** 候选物理网络表（network → transport 码）；回调线程写、主线程读，单锁互斥 */
    private val candidates = HashMap<Network, Int>()

    /** 最近一次防抖到期时的路径快照 (satisfied, transport)；null = 尚无基线 */
    private var lastPath: Pair<Boolean, Int>? = null

    /** 防抖到期动作：此刻才对候选表求值并与基线比较（QF-1 修法第 2/3 点） */
    private val fireDebounced = Runnable { evaluate() }

    /**
     * 注册路径监听（幂等：已注册时忽略，适配 START 重复指令与重连后重注册）。
     * [onPathChange] 在主线程防抖到期判定后回调，是否在跑由实现方自判。
     */
    @Synchronized
    fun start(context: Context, onPathChange: () -> Unit) {
        if (callback != null) return
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            // 不覆写 onAvailable：available 后必跟 capabilitiesChanged，表项以 caps 为准
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (recordCandidate(network, caps)) scheduleDebounce()
            }

            override fun onLost(network: Network) {
                if (removeCandidate(network)) scheduleDebounce()
            }
        }
        this.cm = manager
        this.callback = cb
        this.onPathChange = onPathChange
        lastPath = null // 新一轮监听重新记基线
        synchronized(this) { candidates.clear() }
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
        synchronized(this) { candidates.clear() }
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

    /** 回调线程：记表。VPN 网络/失格网络出表（自反馈过滤）；返回表是否实际变化 */
    private fun recordCandidate(network: Network, caps: NetworkCapabilities): Boolean =
        synchronized(this) {
            val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (!usable) return@synchronized candidates.remove(network) != null
            val transport = transportOf(caps)
            val changed = candidates[network] != transport
            if (changed) candidates[network] = transport
            changed
        }

    /** 回调线程：出表；返回表是否实际变化 */
    private fun removeCandidate(network: Network): Boolean = synchronized(this) {
        candidates.remove(network) != null
    }

    /** 表实际变化才重置 1.5s 防抖计时（能力微调如信号强度更新不重置） */
    private fun scheduleDebounce() {
        mainHandler.removeCallbacks(fireDebounced)
        mainHandler.postDelayed(fireDebounced, DEBOUNCE_MS)
    }

    /** 主线程、防抖到期执行：候选表最强者求值，与基线比较，regained/switched 才触发 */
    private fun evaluate() {
        if (callback == null) return
        val best = synchronized(this) {
            candidates.entries.maxByOrNull { priorityOf(it.value) }
        }
        val snapshot = if (best != null) true to best.value else false to TRANSPORT_NONE
        val last = lastPath
        lastPath = snapshot // 基线更新时机与读数同点：防抖到期判定后（QF-1 修法第 3 点）
        if (last == null) return // 首次判定仅记基线（iOS :591-594）
        val regained = !last.first && snapshot.first
        val switched = last.first && snapshot.first && last.second != snapshot.second
        if (!regained && !switched) return
        TunnelStateBus.log("网络路径变化（${describe(last)} → ${describe(snapshot.first, snapshot.second)}），重启核心")
        onPathChange?.invoke()
    }

    private fun transportOf(caps: NetworkCapabilities): Int = when {
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
