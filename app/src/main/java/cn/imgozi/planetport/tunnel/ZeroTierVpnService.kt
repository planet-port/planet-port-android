package cn.imgozi.planetport.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * VpnService 壳（对齐设计文档 §4：≤200 行生命周期组装，具体逻辑在组件）。
 * T1 版只承载启停意图与前台通知；NodeRuntime/UdpCom/TunnelManager/Dataplane
 * 由后续任务接入 onCreate 组装。
 */
internal class ZeroTierVpnService : VpnService() {
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
                TunnelStateBus.setTunnelState(TunnelState.Running)
                VpnNotification.start(this, "运行中")
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    /** T1 版无组件可停；后续任务在此扩展组件停机序列 */
    private fun stopTunnel() {
        TunnelStateBus.setTunnelState(TunnelState.Stopped())
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        TunnelStateBus.setTunnelState(TunnelState.Idle)
        super.onDestroy()
    }
}
