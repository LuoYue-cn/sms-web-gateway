package com.smswebgateway.android.sync

import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.net.ApiResult
import com.smswebgateway.android.net.TaskResultRequest
import com.smswebgateway.android.net.describe
import com.smswebgateway.android.sms.TaskStatus
import com.smswebgateway.android.sms.nextReportStep
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 发送任务状态上报（只重试「上报」，绝不重试「发送」）。
 * 每一步都是服务端允许的合法转移（见 [nextReportStep]）；服务端回复 409 视为服务端已有更权威的状态，放弃上报。
 */
object Reporter {
    private val mutex = Mutex()

    /** @return true=没有遗留待上报项；false=仍有失败项，稍后重试。 */
    suspend fun flush(): Boolean = mutex.withLock {
        val dao = Graph.db.tasks()
        var allDone = true
        for (pending in dao.pendingReports()) {
            var guard = 0
            while (guard++ < 4) {
                val t = dao.get(pending.taskId) ?: break
                if (!t.reportPending) break
                val reported = t.reportedStatus ?: TaskStatus.CLAIMED
                val step = nextReportStep(reported, t.status)
                if (step == null) {
                    if (reported == t.status) {
                        dao.finishReportIfCaughtUp(t.taskId, t.status)
                        if (TaskStatus.isTerminalLocal(t.status)) dao.clearContent(t.taskId)
                    } else {
                        AppLog.w("Reporter", "任务 ${t.taskId.take(8)} 无合法上报路径 ($reported -> ${t.status})，放弃上报")
                        dao.dropReport(t.taskId)
                    }
                    break
                }
                val sentParts = java.lang.Long.bitCount(t.sentMask)
                val req = TaskResultRequest(
                    taskId = t.taskId,
                    status = step,
                    errorCode = if (step == TaskStatus.FAILED || step == TaskStatus.UNKNOWN) t.errorCode else null,
                    errorMessage = if (step == TaskStatus.FAILED || step == TaskStatus.UNKNOWN) t.errorMessage else null,
                    partsTotal = t.partsTotal.takeIf { it > 0 },
                    partsSent = sentParts.takeIf { t.partsTotal > 0 },
                    timestamp = System.currentTimeMillis(),
                )
                when (val r = Graph.api.taskResult(req)) {
                    is ApiResult.Ok -> {
                        dao.setReported(t.taskId, step)
                        AppLog.i("Reporter", "任务 ${t.taskId.take(8)} 已上报 $step")
                    }
                    is ApiResult.Err -> {
                        if (r.httpStatus == 409 || r.httpStatus == 404) {
                            AppLog.w("Reporter", "任务 ${t.taskId.take(8)} 上报 $step 被服务端拒绝：${r.code}，放弃上报")
                            dao.dropReport(t.taskId)
                            if (TaskStatus.isTerminalLocal(t.status)) dao.clearContent(t.taskId)
                        } else {
                            AppLog.w("Reporter", "任务 ${t.taskId.take(8)} 上报失败，稍后重试：${r.describe()}")
                            allDone = false
                        }
                        break
                    }
                    is ApiResult.NetworkError -> {
                        AppLog.w("Reporter", "任务 ${t.taskId.take(8)} 上报失败（网络），稍后重试")
                        allDone = false
                        break
                    }
                }
            }
        }
        // 顺带清理：30 天前的完结记录删除；已完结 1 天以上的记录抹去正文
        val now = System.currentTimeMillis()
        dao.scrubOldContent(now - 24 * 3600_000L)
        dao.deleteOld(now - 30L * 24 * 3600_000L)
        allDone
    }
}
