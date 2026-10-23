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
import kotlinx.coroutines.launch
import java.io.File

/**
 * VpnService 壳（对齐设计文档 §4：≤200 行生命周期组装，具体逻辑在组件）。
 * T2 版组装 NodeRuntime 起核心；UdpCom/TunnelManager/Dataplane 由后续任务接入。
 */
internal class ZeroTierVpnService : VpnService() {
    private var scope: CoroutineScope? = null
    private var runtime: NodeRuntime? = null
    private var udpCom: UdpCom? = null

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

    /** T2 版：起核心（空 sender——无物理收发，T3 接 UdpCom 后 ONLINE 才有意义） */
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
            val ok = r.start(sender = udp.asPacketSender())
            if (!ok) {
                TunnelStateBus.setTunnelState(TunnelState.Failed("核心初始化失败", recoverable = true))
                stopTunnel()
                return@launch
            }
            udp.start()
            runtime = r
            udpCom = udp
            TunnelStateBus.setTunnelState(TunnelState.Running)
            VpnNotification.update(this@ZeroTierVpnService, "运行中")
        }
    }

    private fun stopTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Stopped())
        scope?.cancel()
        scope = null
        udpCom?.shutdown()
        udpCom = null
        runtime?.shutdown()
        runtime = null
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        TunnelStateBus.setTunnelState(TunnelState.Idle)
        super.onDestroy()
    }
}
