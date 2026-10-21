package com.smswebgateway.android.data

import android.util.Log
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.db.AppLogEntity
import com.smswebgateway.android.util.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用日志：写入 logcat 与 Room `app_log`。调用方负责不传入完整短信内容 / token，
 * 这里再用 [Redact.scrub] 兜底。直接启动阶段（CE 存储未解锁）数据库不可用时只写 logcat。
 */
object AppLog {
    private val counter = AtomicInteger()

    fun d(tag: String, msg: String) = emit("D", tag, msg)
    fun i(tag: String, msg: String) = emit("I", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = emit("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = emit("E", tag, msg, t)

    private fun emit(level: String, tag: String, raw: String, t: Throwable? = null) {
        val msg = Redact.scrub(raw) + (t?.let { " (${it.javaClass.simpleName}: ${Redact.scrub(it.message.orEmpty()).take(120)})" } ?: "")
        when (level) {
            "E" -> Log.e("SmsGate/$tag", msg)
            "W" -> Log.w("SmsGate/$tag", msg)
            "D" -> Log.d("SmsGate/$tag", msg)
            else -> Log.i("SmsGate/$tag", msg)
        }
        if (level == "D") return
        if (!Graph.isReady) return
        Graph.appScope.launch(Dispatchers.IO) {
            try {
                val dao = Graph.db.logs()
                dao.insert(AppLogEntity(ts = System.currentTimeMillis(), level = level, tag = tag, message = msg.take(500)))
                if (counter.incrementAndGet() % 100 == 0) dao.prune()
            } catch (_: Exception) {
            }
        }
    }
}
