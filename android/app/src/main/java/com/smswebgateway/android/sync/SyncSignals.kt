package com.smswebgateway.android.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** 进程内信号：前台服务运行时，新短信 / 回调 / 短信库变化直接触发对应循环，不必等 WorkManager。 */
object SyncSignals {
    private val upload = Channel<Unit>(Channel.CONFLATED)
    private val history = Channel<Unit>(Channel.CONFLATED)

    val uploadKicks: Flow<Unit> = upload.receiveAsFlow()
    val historyKicks: Flow<Unit> = history.receiveAsFlow()

    fun kickUpload() { upload.trySend(Unit) }
    fun kickReports() { upload.trySend(Unit) }
    fun kickHistory() { history.trySend(Unit) }
    fun kickAll() { kickUpload(); kickHistory() }
}
