package cn.imgozi.planetport.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StorePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    /** 帧/配置消费协程——停机前须 join：scope.cancel 只置取消标志不中断 JNI，
     *  协程可能正处 handleFrameFromCore 冲刷待决包或 applyAggregated 的
     *  join/multicastSubscribe JNI 内，runtime.shutdown 关 Node 会 native UAF */
    private var frameJob: Job? = null
    private var configJob: Job? = null

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
                frameJob = s.launch {
                    for (ev in r.frameChannel) {
                        val packets = conv.handleFrameFromCore(
                            ev.nwid, ev.srcMac, ev.dstMac, ev.etherType, ev.frame
                        )
                        if (packets.isNotEmpty()) dp.writePackets(packets)
                    }
                }
                // 配置消费：netconf 事件 → TunnelManager
                configJob = s.launch {
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
                    joinConsumerJobs() // 启动失败分支同理：消费协程可能仍在 JNI 内
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
        joinConsumerJobs()
        runtime?.shutdown()
        runtime = null
        TunnelStateBus.setNodeStatus(null)
        TunnelStateBus.clearNetworkStatuses()
        stopForeground(true)
        stopSelf()
    }

    /**
     * 停机收口：等帧/配置消费协程真正退出后再关 Node。scope.cancel 只置取消标志
     * 不中断 JNI——协程可能正处 handleFrameFromCore 冲刷待决包（逐目标逐包 JNI）或
     * applyAggregated 的 join/multicastSubscribe JNI 内，直接 r.shutdown() 会 native
     * UAF（同 supervisor/udp join 收口）。runBlocking 不继承外层 Job，取消态下
     * finally 也能等完；主线程阻塞上限 500ms，叠加既有 udp/supervisor join 仍远低
     * 于 ANR 阈值。joinAll 重抛子协程异常——协程已退出 JNI 即达目的，停机中吞掉。
     */
    private fun joinConsumerJobs() {
        val jobs = listOfNotNull(frameJob, configJob)
        frameJob = null
        configJob = null
        if (jobs.isEmpty()) return
        runBlocking {
            try {
                withTimeoutOrNull(500) { jobs.joinAll() }
            } catch (_: Exception) {
                // 消费协程异常完成也算退出 JNI，停机路径吞掉
            }
        }
    }

    /** 系统撤销 VPN（快捷开关关闭/被其他 VPN 接管）——必须走完整停机清理，
     *  否则 scope/数据面残留且 TUN 已失，60s CONFIG_UPDATE 到来时状态错乱 */
    override fun onRevoke() {
        stopTunnel()
    }

    override fun onDestroy() {
        TunnelStateBus.setTunnelState(TunnelState.Idle)
        super.onDestroy()
    }
}
