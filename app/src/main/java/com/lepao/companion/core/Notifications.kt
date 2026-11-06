package com.lepao.companion.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object Notifications {
    const val CHANNEL_CAPTURE = "capture"
    const val CHANNEL_RUN = "run"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE, "登录态采集", NotificationManager.IMPORTANCE_LOW).apply {
                description = "本地代理捕获官方乐跑登录响应"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "跑步会话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "跑步进行时在状态栏常驻，保持前台服务优先级"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }
}
