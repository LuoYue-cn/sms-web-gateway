package com.smswebgateway.android.sync

import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.AppLog
import com.smswebgateway.android.data.db.SendTaskEntity
import com.smswebgateway.android.net.ApiResult
import com.smswebgateway.android.net.PollTask
import com.smswebgateway.android.net.TaskResultRequest
import com.smswebgateway.android.net.describe
import com.smswebgateway.android.sms.PhoneNumber
import com.smswebgateway.android.sms.PollDecision
import com.smswebgateway.android.sms.Permissions
import com.smswebgateway.android.sms.SimHelper
import com.smswebgateway.android.sms.SmsSender
import com.smswebgateway.android.sms.TaskStatus
import com.smswebgateway.android.sms.decideOnPoll
import com.smswebgateway.android.util.Redact
import com.smswebgateway.android.util.WakeLocks
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 发送任务执行器——幂等 / 崩溃协议（docs/API.md「幂等 / 崩溃协议」）的实现：
 *
 *  1. claim 返回 200 → 先在 Room 持久化 task_id（状态 claimed）；
 *  2. 校验（号码、send_paused、SIM 槽位、权限）失败 → 上报 failed（从未调用 SmsManager）；
 *  3. 先成功上报 `sending`；上报失败则不发送，任务保持 claimed；
 *  4. 本地原子地 claimed→sending，之后才调用 SmsManager；
 *  5. 进程重启后停留在 sending 的任务 → 上报 unknown，绝不重发（见 [recoverAfterProcessStart]）；
 *  6. 已出现在本地库里（非 claimed）的 task_id 永不再发；
 *  7. 结果上报失败只重试「上报」（[Reporter]），不重试「发送」。
 */
object TaskRunner {
    /** 本进程标识：区分「本进程内等待回调的 sending」与「进程重启后遗留的 sending」。 */
    val processId: String = UUID.randomUUID().toString()

    private val mutex = Mutex()
    private const val CLAIMED_RESUME_WINDOW_MS = 4 * 60_000L
    private const val SENDING_CALLBACK_TIMEOUT_MS = 10 * 60_000L
    @Volatile private var recoveredThisProcess = false

    /** 处理一次 poll 返回的任务。返回本轮真正推进了的任务数量（用于避免空转）。 */
    suspend fun handlePolled(tasks: List<PollTask>): Int = mutex.withLock {
        var progressed = 0
        for (t in tasks) {
            if (processPolled(t)) progressed++
        }
        progressed
    }

    /** 继续处理本地仍为 claimed 的任务（之前因 send_paused、上报 sending 失败等原因没有发送）。 */
    suspend fun resumeClaimed(): Int = mutex.withLock {
        val dao = Graph.db.tasks()
        val now = System.currentTimeMillis()
        var n = 0
        for (t in dao.claimedTasks()) {
            if (now - t.claimedAt > CLAIMED_RESUME_WINDOW_MS) {
                // 超过服务端 5 分钟回收窗口：服务端会把它放回 queued 重新下发。本地从未发送过，删除记录以便重新 claim。
                dao.delete(t.taskId)
                AppLog.i("Task", "任务 ${t.taskId.take(8)} 长时间停留在 claimed，释放本地记录（未发送）")
                continue
            }
            if (Graph.prefs.sendPaused) continue
            if (execute(t)) n++
        }
        n
    }

    private suspend fun processPolled(task: PollTask): Boolean {
        val prefs = Graph.prefs
        val dao = Graph.db.tasks()
        val local = dao.get(task.taskId)
        if (decideOnPoll(local?.status) == PollDecision.SKIP_PROCESSED) {
            AppLog.d("Task", "任务 ${task.taskId.take(8)} 本地已处理过(${local?.status})，不再发送")
            return false
        }
        if (prefs.sendPaused) return false

        val claim = Graph.api.claimTask(task.taskId)
        val claimed = when (claim) {
            is ApiResult.Ok -> claim.data.task
            is ApiResult.Err -> {
                AppLog.i("Task", "领取任务 ${task.taskId.take(8)} 未成功：${claim.code}")
                return false
            }
            is ApiResult.NetworkError -> {
                AppLog.w("Task", "领取任务网络失败，稍后重试")
                return false
            }
        }
        val now = System.currentTimeMillis()
        val entity = SendTaskEntity(
            taskId = task.taskId,
            recipient = claimed?.recipient ?: task.recipient,
            content = claimed?.content ?: task.content,
            simSlot = if (claimed != null) claimed.simSlot else task.simSlot,
            status = TaskStatus.CLAIMED,
            reportedStatus = TaskStatus.CLAIMED,
            reportPending = false,
            partsTotal = 0,
            sentMask = 0, failMask = 0, deliveredMask = 0, deliveryFailMask = 0,
            errorCode = null, errorMessage = null,
            createdAt = task.createdAt ?: now,
            claimedAt = now,
            sendingAt = null,
            sendProcessId = null,
            updatedAt = now,
        )
        // 协议第 1 步：claim 成功后先持久化 task_id，之后才允许发送
        dao.upsert(entity)
        AppLog.i("Task", "已领取任务 ${task.taskId.take(8)} 收件人 ${Redact.phone(entity.recipient)} ${Redact.content(entity.content)}")
        return execute(entity)
    }

    /** 对一个本地状态为 claimed 的任务执行：校验 → 上报 sending → 本地 sending → SmsManager。 */
    private suspend fun execute(entity: SendTaskEntity): Boolean {
        val ctx = Graph.context
        val prefs = Graph.prefs
        val dao = Graph.db.tasks()
        val id = entity.taskId

        // 发送前复查 send_paused：保持 claimed，不发送、不失败
        if (prefs.sendPaused) {
            AppLog.i("Task", "发送已暂停，任务 ${id.take(8)} 保持 claimed")
            return false
        }
        val number = PhoneNumber.normalizeForSend(entity.recipient)
            ?: return failBeforeSend(entity, "invalid_number", "收件号码格式无效")
        if (entity.content.isEmpty()) return failBeforeSend(entity, "invalid_content", "短信内容为空")
        if (!Permissions.hasSendSms(ctx)) return failBeforeSend(entity, "permission_denied", "没有「发送短信」权限")

        val slot = entity.simSlot
        if (slot != null) {
            if (!SimHelper.slotUsable(ctx, slot)) {
                return failBeforeSend(entity, "sim_unavailable", "指定的 SIM 卡槽 ${slot + 1} 不可用（未插卡 / 未就绪 / 缺少读取 SIM 权限）")
            }
        } else if (!SimHelper.anySimReady(ctx)) {
            return failBeforeSend(entity, "no_sim", "手机没有可用的 SIM 卡")
        }
        val parts = SmsSender.split(ctx, slot, entity.content)
        if (parts.isNullOrEmpty()) return failBeforeSend(entity, "sim_unavailable", "无法获取该 SIM 的短信服务")

        // 协议第 2 步：先成功上报 sending，之后才允许调用 SmsManager
        val rep = Graph.api.taskResult(
            TaskResultRequest(
                taskId = id, status = TaskStatus.SENDING, partsTotal = parts.size,
                timestamp = System.currentTimeMillis(),
            ),
        )
        when (rep) {
            is ApiResult.Ok -> Unit
            is ApiResult.Err -> {
                if (rep.httpStatus == 409) {
                    // 服务端认为该任务已不在可发送状态（例如已被回收到 queued）。本地从未发送，删除记录，由下一次 poll 决定。
                    AppLog.w("Task", "服务端拒绝任务 ${id.take(8)} 进入 sending(${rep.code})，不发送")
                    dao.delete(id)
                } else {
                    AppLog.w("Task", "上报 sending 失败(${rep.describe()})，不发送，任务保持 claimed")
                }
                return false
            }
            is ApiResult.NetworkError -> {
                AppLog.w("Task", "上报 sending 网络失败，不发送，任务保持 claimed")
                return false
            }
        }

        // 协议第 3 步：本地原子地 claimed→sending；若本地状态已变（并发恢复），绝不发送
        val began = dao.beginSending(id, parts.size, processId, System.currentTimeMillis())
        if (!began) {
            AppLog.w("Task", "任务 ${id.take(8)} 本地状态已不是 claimed，放弃发送")
            return false
        }

        // 协议第 4 步：调用 SmsManager（持有短时 WakeLock）
        val outcome = WakeLocks.withLock(ctx, "send-sms", 60_000L) {
            SmsSender.send(ctx, id, number, slot, parts)
        }
        when (outcome) {
            SmsSender.SendOutcome.Submitted ->
                AppLog.i("Task", "任务 ${id.take(8)} 已提交系统发送，共 ${parts.size} 段，等待回调")
            is SmsSender.SendOutcome.Rejected -> finishLocal(id, TaskStatus.FAILED, outcome.errorCode, outcome.message)
            is SmsSender.SendOutcome.Uncertain -> finishLocal(id, TaskStatus.UNKNOWN, "send_uncertain", outcome.message)
        }
        return true
    }

    /** 校验失败：本地置 failed 并上报（claimed→failed 是合法转移）。从未调用过 SmsManager。 */
    private suspend fun failBeforeSend(entity: SendTaskEntity, code: String, message: String): Boolean {
        AppLog.w("Task", "任务 ${entity.taskId.take(8)} 校验失败：$code")
        val dao = Graph.db.tasks()
        val cur = dao.get(entity.taskId) ?: entity
        if (cur.status != TaskStatus.CLAIMED) return false
        dao.upsert(
            cur.copy(
                status = TaskStatus.FAILED, reportPending = true,
                errorCode = code, errorMessage = message, updatedAt = System.currentTimeMillis(),
            ),
        )
        if (!Reporter.flush()) Scheduler.enqueueUpload(Graph.context)
        return true
    }

    private suspend fun finishLocal(id: String, status: String, code: String, message: String) {
        val dao = Graph.db.tasks()
        val cur = dao.get(id) ?: return
        if (cur.status != TaskStatus.SENDING) return
        dao.upsert(
            cur.copy(
                status = status, reportPending = true,
                errorCode = code, errorMessage = message, updatedAt = System.currentTimeMillis(),
            ),
        )
        if (!Reporter.flush()) Scheduler.enqueueUpload(Graph.context)
    }

    /**
     * 进程启动后的崩溃恢复：所有遗留 `sending` 任务转 `unknown` 并上报，**不重发**。
     * 每个进程只执行一次（此刻不可能有本进程发出的 sending）。
     */
    suspend fun recoverAfterProcessStart() {
        if (recoveredThisProcess) return
        recoveredThisProcess = true
        val n = Graph.db.tasks().recoverSending(System.currentTimeMillis(), keepProcessId = null, staleBeforeMs = 0)
        if (n > 0) {
            AppLog.w("Task", "进程重启后发现 $n 个停留在 sending 的任务，已转为 unknown（不会重发）")
            if (Graph.prefs.isBound) Scheduler.enqueueUpload(Graph.context)
        }
    }

    /**
     * 供 RecoverReceiver / 周期维护使用：本进程仍在等待回调且未超时的 sending 保持不动，其余转 unknown。
     * @return 被转为 unknown 的数量
     */
    suspend fun recoverStale(): Int {
        val now = System.currentTimeMillis()
        val n = Graph.db.tasks().recoverSending(now, processId, now - SENDING_CALLBACK_TIMEOUT_MS)
        if (n > 0) {
            AppLog.w("Task", "$n 个 sending 任务超时/遗留，已转为 unknown（不会重发）")
            if (Graph.prefs.isBound) Scheduler.enqueueUpload(Graph.context)
        }
        return n
    }

    /** 单次 poll（wait=0），用于没有前台服务时的周期 Worker 退化模式。 */
    suspend fun pollOnce(): Boolean {
        val prefs = Graph.prefs
        resumeClaimed()
        return when (val r = Graph.api.pollTasks(0)) {
            is ApiResult.Ok -> {
                prefs.sendPaused = r.data.sendPaused
                prefs.lastServerOkAt = System.currentTimeMillis()
                if (!r.data.sendPaused && r.data.tasks.isNotEmpty()) handlePolled(r.data.tasks)
                true
            }
            else -> false
        }
    }
}
