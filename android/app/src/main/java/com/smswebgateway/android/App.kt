package com.smswebgateway.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.Configuration
import com.smswebgateway.android.service.Notifications

class App : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.ensureChannels(this)
    }

    // WorkManager 按需初始化（Direct Boot 阶段不触碰 CE 数据库）。
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()
}
