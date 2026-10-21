package com.smswebgateway.android.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.data.db.RootAuditEntity
import com.smswebgateway.android.service.GatewayService
import com.smswebgateway.android.sync.Scheduler
import com.smswebgateway.android.sync.TaskRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Root Watchdog 的恢复入口（ROOT_INTERFACE.md 第 2 节）。
 * 发送者限制由系统强制：manifest 中 exported=false，只有 root(uid 0)/system/本应用能向它发显式广播。
 * 恢复语义：
 *  - 未绑定 / 用户主动停止 → 什么都不做；
 *  - 冷却与保护：连续失败 10s/30s/60s/300s，达到 max_failures 进入保护，直到服务健康（心跳成功）自动解除；
 *  - 服务已在运行（单例）→ 不重复启动；否则经 ForegroundServiceStarter 启动，不被允许则排 WorkManager 恢复任务；
 *  - 状态为 sending 的发送任务 → unknown 并上报，绝不重发；claimed 的任务不动。
 */
class RecoverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Graph.init(context)
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        val reasonRaw = intent.getStringExtra("reason").orEmpty()
        val reason = if (REASON_REGEX.matches(reasonRaw)) reasonRaw else "invalid_reason"
        val attempt = intent.getIntExtra("attempt", 0)

        val pending = goAsync()
        Graph.appScope.launch(Dispatchers.IO) {
            try {
                RecoverCoordinator.run(context, reason, attempt, isTest = false)
            } catch (e: Exception) {
                AppLog.e("Recover", "处理恢复广播失败", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "com.smswebgateway.android.action.WATCHDOG_RECOVER"
        val REASON_REGEX = Regex("^[A-Za-z0-9._]+$")
    }
}

object RecoverCoordinator {
    /** 为容忍 Watchdog 与 APK 之间的时钟 / 调度抖动，冷却判定宽限 3 秒。 */
    private const val GRACE_MS = 3_000L

    enum class Result { IGNORED_UNBOUND, IGNORED_USER_STOPPED, COOLDOWN, PROTECTED, ALREADY_RUNNING, SERVICE_STARTED, SCHEDULED_WORK }

    suspend fun run(ctx: Context, reason: String, attempt: Int, isTest: Boolean): Result {
        val prefs = Graph.prefs
        if (!prefs.isBound) return Result.IGNORED_UNBOUND
        if (prefs.userStopped) {
            AppLog.i("Recover", "忽略恢复请求：用户已主动停止服务")
            return Result.IGNORED_USER_STOPPED
        }
        val now = System.currentTimeMillis()
        if (!isTest) {
            val state = GuardState(prefs.guardFailures, prefs.guardLastAttemptAt)
            when (val d = RecoveryGuard.evaluate(now + GRACE_MS, state, prefs.watchdogMaxFailures)) {
                GuardDecision.Protected -> {
                    AppLog.w("Recover", "处于保护状态（连续 ${state.failures} 次恢复未见好转），忽略恢复请求")
                    return Result.PROTECTED
                }
                is GuardDecision.InCooldown -> {
                    AppLog.i("Recover", "冷却中，还需 ${d.remainingSeconds}s，忽略恢复请求")
                    return Result.COOLDOWN
                }
                GuardDecision.Accept -> Unit
            }
            val next = RecoveryGuard.onAccepted(now, state)
            prefs.guardFailures = next.failures
            prefs.guardLastAttemptAt = next.lastAttemptAtMs
            prefs.recoveryCount = prefs.recoveryCount + 1
            prefs.lastRecoveryAt = now
        }
        AppLog.i("Recover", "${if (isTest) "[测试] " else ""}执行恢复：reason=$reason attempt=$attempt")
        Graph.db.rootAudit().insert(
            RootAuditEntity(
                ts = now, action = if (isTest) "recovery_test" else "watchdog_recover", result = "ok",
                exitCode = null, detail = "reason=$reason attempt=$attempt",
            ),
        )

        // 崩溃恢复规则：sending → unknown，绝不重发；claimed 保持不动
        if (isTest) TaskRunner.recoverStale() else TaskRunner.recoverAfterProcessStart().also { TaskRunner.recoverStale() }

        val result = if (GatewayService.running.value) {
            Result.ALREADY_RUNNING
        } else if (ForegroundServiceStarter.start(ctx)) {
            Result.SERVICE_STARTED
        } else {
            Scheduler.enqueueRecovery(ctx)
            Result.SCHEDULED_WORK
        }
        Scheduler.schedulePeriodic(ctx)
        Scheduler.enqueueUpload(ctx)
        AppLog.i("Recover", "恢复结果：$result")
        return result
    }
}
