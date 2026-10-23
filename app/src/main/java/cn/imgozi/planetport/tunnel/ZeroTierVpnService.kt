package cn.imgozi.planetport.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StorePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.SocketException

/**
 * VpnService 壳（对齐设计文档 §4：≤200 行生命周期组装，具体逻辑在组件）。
 * 全链路组装：NodeRuntime 起核心 + UdpCom 物理收发 + FrameConverter/Dataplane
 * 数据面 + TunnelManager netconf 聚合与 TUN 建立。
 */
internal class ZeroTierVpnService : VpnService() {
    private var scope: CoroutineScope? = null
    private var runtime: NodeRuntime? = null
    private var udpCom: UdpCom? = null
    private var converter: FrameConverter? = null
    private var dataplane: Dataplane? = null
    private var tunnelManager: TunnelManager? = null

    companion object {
        private const val ACTION_START = "cn.imgozi.planetport.action.START"
        private const val ACTION_STOP = "cn.imgozi.planetport.action.STOP"

        fun start(context: Context) {
            context.startService(
                Intent(context, ZeroTierVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ZeroTierVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                VpnNotification.start(this, "启动中")
                startTunnel()
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Starting)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val store = AppConfigStore(StorePaths(filesDir))
        s.launch {
            val planetId = store.activePlanetId()
            val planet = store.readPlanets().firstOrNull { it.id == planetId }
                ?: PlanetProfile.OFFICIAL
            var runtimeRef: NodeRuntime? = null
            val udp = UdpCom(this@ZeroTierVpnService) { data, remote ->
                runtimeRef?.processWirePacket(data, remote)
            }
            val r = NodeRuntime(
                dataStoreRoot = File(filesDir, "zt"),
                planetDir = store.paths.planetStateDir(planet.id),
                planetFile = planet.fileName?.let { File(store.paths.planetsDir, it) },
                scope = s,
            )
            runtimeRef = r
            val conv = FrameConverter(r)
            val dp = Dataplane { pkt -> conv.handlePacketsFromTun(listOf(pkt)) }
            val tm = TunnelManager(s, r, conv, dp, store, this@ZeroTierVpnService)
            // started 未置位前的任何退出（失败/取消/STOP 竞态）都走 finally 兜底清理：
            // 字段赋值晚于失败分支与挂起点，仅靠字段收口会泄漏局部 r 的 native Node
            var started = false
            try {
                if (!r.start(sender = udp.asPacketSender())) {
                    TunnelStateBus.setTunnelState(TunnelState.Failed("核心初始化失败", recoverable = true))
                    stopTunnel()
                    return@launch
                }
                // 帧消费：核心回调帧 → 转换 → 写 TUN（IP 包无需协议族标注，TUN 按 IP 头自识别）
                s.launch {
                    for (ev in r.frameChannel) {
                        val packets = conv.handleFrameFromCore(
                            ev.nwid, ev.srcMac, ev.dstMac, ev.etherType, ev.frame
                        )
                        if (packets.isNotEmpty()) dp.writePackets(packets)
                    }
                }
                // 配置消费：netconf 事件 → TunnelManager
                s.launch {
                    for (ev in r.configChannel) tm.onNetworkConfig(ev.nwid, ev.op, ev.conf)
                }
                try {
                    udp.start()
                } catch (e: SocketException) {
                    // 9994 被占：绑定失败不可继续（无物理收发），也不能进程崩溃
                    TunnelStateBus.setTunnelState(TunnelState.Failed("UDP 9994 绑定失败", recoverable = true))
                    stopTunnel()
                    return@launch
                }
                runtime = r; udpCom = udp; converter = conv
                dataplane = dp; tunnelManager = tm
                tm.start()
                tm.applyConfig() // 首启 join 已配置网络 + orbit moons
                // START/STOP 竞态收敛：STOP 落在启动期时不再错置 Running
                if (!s.isActive) {
                    stopTunnel()
                    return@launch
                }
                started = true
                TunnelStateBus.setTunnelState(TunnelState.Running)
                VpnNotification.update(this@ZeroTierVpnService, "运行中")
            } finally {
                if (!started) {
                    // 局部引用兜底清理（幂等，与 stopTunnel 双清安全）；finally 内全非挂起，
                    // 协程被取消时也能执行完
                    s.cancel() // 帧配置消费协程 + TunnelManager 订阅/保活
                    tm.stop()
                    dp.shutdown()
                    udp.shutdown() // 先 join 收包线程再关 Node（防 native UAF，同 stopTunnel 顺序）
                    r.shutdown()
                    // 仅清自己这轮的字段：身份比对防误清新一轮 START 的装配
                    if (runtime === r) {
                        runtime = null; udpCom = null; converter = null
                        dataplane = null; tunnelManager = null
                    }
                    TunnelStateBus.setNodeStatus(null)
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
    }

    private fun stopTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Stopped())
        scope?.cancel()
        scope = null
        tunnelManager?.stop()
        tunnelManager = null
        dataplane?.shutdown()
        dataplane = null
        converter = null
        udpCom?.shutdown()
        udpCom = null
        runtime?.shutdown()
        runtime = null
        TunnelStateBus.setNodeStatus(null)
        TunnelStateBus.clearNetworkStatuses()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        TunnelStateBus.setTunnelState(TunnelState.Idle)
        super.onDestroy()
    }
}
