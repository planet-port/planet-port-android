package cn.imgozi.planetport.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import cn.imgozi.planetport.R

/** 前台通知：渠道幂等创建 + 状态文案更新（对齐设计文档 §4 VpnNotification 组件） */
internal object VpnNotification {
    private const val CHANNEL_ID = "planet_tunnel"
    private const val NOTIFICATION_ID = 1

    fun start(service: Service, text: String) {
        createChannel(service)
        service.startForeground(NOTIFICATION_ID, build(service, text))
    }

    fun update(context: Context, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, build(context, text))
    }

    private fun build(context: Context, text: String): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(context)
        return builder
            // 状态栏小图标 = 品牌轮辐白色剪影（仅 alpha）；通知栏内展示与 App
            // 图标一致的品牌 logo（largeIcon，minSdk 23 起 Builder 支持 Icon 重载）
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(Icon.createWithResource(context, R.drawable.planet_port_logo))
            .setContentTitle("Planet Port")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 渠道名随系统设置可见（Android 独有概念，iOS 无对应 key）
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.tunnel_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
