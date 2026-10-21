package com.smswebgateway.android

import android.content.Context
import android.os.UserManager
import com.smswebgateway.android.data.Prefs
import com.smswebgateway.android.data.SecureTokenStore
import com.smswebgateway.android.data.db.AppDatabase
import com.smswebgateway.android.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写的依赖容器。所有依赖 CE 存储的对象都是 lazy 的：
 * 开机未解锁（Direct Boot）时 Application 也会被创建，此时绝不能触碰 Room / SharedPreferences / Keystore 令牌。
 */
object Graph {
    private lateinit var app: Context
    private var initialized = false

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        initialized = true
    }

    val context: Context get() = app

    /** 已初始化且用户已解锁（CE 存储可用）。 */
    val isReady: Boolean
        get() = initialized && (app.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true)

    val db: AppDatabase by lazy { AppDatabase.create(app) }
    val prefs: Prefs by lazy { Prefs(app) }
    val tokens: SecureTokenStore by lazy { SecureTokenStore(app) }
    val api: ApiClient by lazy { ApiClient(prefs, tokens) }
}
