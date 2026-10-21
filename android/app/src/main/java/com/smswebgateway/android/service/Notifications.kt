package com.smswebgateway.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.smswebgateway.android.MainActivity
import com.smswebgateway.android.R

object Notifications {
    const val CHANNEL_SERVICE = "gateway_service"
    const val CHANNEL_ALERT = "gateway_alert"
    const val ID_SERVICE = 1001
    const val ID_WORKER = 1002
    const val ID_ALERT = 1003

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "网关常驻服务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持与服务器的连接，用于接收发送任务、上传新短信"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "异常提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "同步延迟、凭据失效等需要处理的提示"
            },
        )
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun serviceNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("短信网关运行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .build()

    fun workerNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("短信网关")
            .setContentText("正在同步短信…")
            .setOngoing(true)
            .build()

    fun alert(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        try {
            nm.notify(ID_ALERT, n)
        } catch (_: SecurityException) {
        }
    }
}
