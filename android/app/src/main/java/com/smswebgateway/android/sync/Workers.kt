package com.smswebgateway.android.sync

import android.content.Context
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.keepalive.ForegroundServiceStarter
import com.smswebgateway.android.service.GatewayService
import com.smswebgateway.android.service.HeartbeatSender
import com.smswebgateway.android.service.Notifications
import com.smswebgateway.android.sms.Permissions
import com.smswebgateway.android.util.WakeLocks
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val UNIQUE_UPLOAD = "smsgate_upload_now"
    private const val UNIQUE_PERIODIC = "smsgate_periodic_sync"
    private const val UNIQUE_RECOVERY = "smsgate_recovery"

    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * 立即上传（实时短信队列 + 待上报的发送结果）。带 NetworkType.CONNECTED 约束：
     * 无网络时 WorkManager 会一直等到网络恢复再执行，这同时也是「网络恢复补偿」。
     */
    fun enqueueUpload(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_UPLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** 15 分钟周期同步 / 心跳（约束网络）。 */
    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(connected)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** 开机 / 看门狗 / 服务无法直接启动时的一次性恢复任务（不带网络约束，保证尽快尝试拉起服务）。 */
    fun enqueueRecovery(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RecoveryWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE_RECOVERY, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelAll(ctx: Context) {
        WorkManager.getInstance(ctx).cancelAllWork()
    }
}

private fun Context.isUnlocked() = getSystemService(UserManager::class.java)?.isUserUnlocked ?: true

private suspend fun foregroundInfoFor(ctx: Context, id: Int): ForegroundInfo =
    ForegroundInfo(id, Notifications.workerNotification(ctx))

/** 实时短信队列 / 发送结果上报。 */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfoFor(applicationContext, Notifications.ID_WORKER)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!ctx.isUnlocked()) return Result.retry()
        Graph.init(ctx)
        val prefs = Graph.prefs
        if (!prefs.isBound || prefs.userStopped) return Result.success()
        val ok = WakeLocks.withLock(ctx, "upload-worker", 90_000L) {
            val a = Uploader.flushOutbox()
            val b = Reporter.flush()
            a && b
        }
        return if (ok) Result.success() else Result.retry()
    }
}

/** 周期同步：心跳 + 队列补偿 + 增量历史同步 +（服务未运行时）单次任务轮询。 */
class PeriodicSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runKeepalivePass(applicationContext, "periodic", tryStartService = false)
}

class RecoveryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfoFor(applicationContext, Notifications.ID_WORKER)
    override suspend fun doWork(): Result = runKeepalivePass(applicationContext, "recovery", tryStartService = true)
}

private suspend fun runKeepalivePass(ctx: Context, tag: String, tryStartService: Boolean): androidx.work.ListenableWorker.Result {
    if (!ctx.isUnlocked()) return androidx.work.ListenableWorker.Result.retry()
    Graph.init(ctx)
    val prefs = Graph.prefs
    if (!prefs.isBound || prefs.userStopped) return androidx.work.ListenableWorker.Result.success()

    TaskRunner.recoverStale()
    com.smswebgateway.android.root.RootManager.maintain(ctx)
    if (tryStartService && !GatewayService.running.value) {
        val started = ForegroundServiceStarter.start(ctx, fromWorker = true)
        AppLog.i("Worker", "[$tag] 尝试拉起前台服务：${if (started) "已请求" else "系统不允许，保持 WorkManager 退化模式"}")
    }
    var ok = true
    WakeLocks.withLock(ctx, "keepalive-$tag", 4 * 60_000L) {
        ok = HeartbeatSender.send() && ok
        ok = Uploader.flushOutbox() && ok
        ok = Reporter.flush() && ok
        if (prefs.autoIncrementalSync && Permissions.hasReadSms(ctx)) {
            val o = SyncEngine.run()
            if (o == SyncOutcome.RETRY) ok = false
        }
        // 前台服务没在跑时由 Worker 兜底领取任务（延迟最长一个周期）
        if (!GatewayService.running.value && Permissions.hasSendSms(ctx)) {
            TaskRunner.pollOnce()
        }
    }
    return if (ok) androidx.work.ListenableWorker.Result.success() else androidx.work.ListenableWorker.Result.retry()
}
