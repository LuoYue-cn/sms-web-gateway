package com.smswebgateway.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smswebgateway.android.Graph
import com.smswebgateway.android.data.db.SendTaskEntity
import com.smswebgateway.android.ui.AppViewModel
import com.smswebgateway.android.ui.KeyValueRow
import com.smswebgateway.android.ui.SectionCard
import com.smswebgateway.android.ui.UiState
import com.smswebgateway.android.ui.fmtAgo
import com.smswebgateway.android.ui.fmtTime
import com.smswebgateway.android.ui.okColor
import com.smswebgateway.android.util.Redact

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier, vm: AppViewModel, s: UiState, onSettings: () -> Unit, onPermissions: () -> Unit, onLogs: () -> Unit) {
    val activeTasks by Graph.db.tasks().activeCountFlow().collectAsState(initial = 0)
    val recentTasks by Graph.db.tasks().recentFlow(5).collectAsState(initial = emptyList())
    val outbox by Graph.db.outbox().countFlow().collectAsState(initial = 0)
    val logs by Graph.db.logs().recentFlow(6).collectAsState(initial = emptyList())

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("短信网关") },
            actions = {
                TextButton(onClick = onLogs) { Text("日志") }
                TextButton(onClick = onSettings) { Text("设置") }
            },
        )
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.missingRequired.isNotEmpty()) {
                SectionCard("缺少权限") {
                    Text(
                        s.missingRequired.joinToString("、") { it.title } + " 未授予，对应的同步 / 收发功能无法工作。",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onPermissions) { Text("去授权") }
                }
            }
            if (s.syncDelayed) {
                SectionCard("同步延迟") {
                    Text(
                        when {
                            s.fgsTimedOut -> "系统限制了前台服务运行时长，已退化为每 15 分钟的周期同步。点击下方「启动服务」恢复实时模式。"
                            s.userStopped -> "你已停止前台服务，新短信和发送任务只会在周期同步（约 15 分钟）时处理。"
                            !s.serviceRunning -> "前台服务未运行，当前依靠周期同步（约 15 分钟），可能有延迟。"
                            else -> "超过 2 分钟没有成功的心跳，可能网络或服务器不可达。"
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard("连接状态") {
                val connected = s.lastHeartbeatAt > 0 && s.now - s.lastHeartbeatAt < 120_000
                KeyValueRow("服务器", s.serverUrl)
                KeyValueRow("设备", "${s.deviceName} (#${s.deviceId})")
                KeyValueRow("连接", if (connected) "已连接" else "未连接", okColor(connected))
                KeyValueRow("前台服务", if (s.serviceRunning) "运行中" else "已停止", okColor(s.serviceRunning))
                KeyValueRow("最近心跳", if (s.lastHeartbeatAt > 0) fmtAgo(s.lastHeartbeatAt, s.now) else "从未")
                KeyValueRow("最近同步", fmtTime(s.lastSyncAt))
                s.lastHeartbeatError?.let { if (!connected) Text("最近错误：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (s.sendPaused) Text("服务器已紧急暂停发送，任务不会被领取。", color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.serviceRunning) OutlinedButton(onClick = { vm.stopService() }) { Text("停止服务") }
                    else Button(onClick = { vm.startService() }) { Text("启动服务") }
                }
            }

            SectionCard("历史短信同步") {
                val label = when (s.syncStatus) {
                    "running" -> "同步中"; "done" -> "已完成"; "error" -> "出错（将自动续传）"; "idle" -> "空闲"; else -> "尚未开始"
                }
                KeyValueRow("状态", label, if (s.syncStatus == "error") MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                KeyValueRow("已同步 / 总数", "${s.syncSynced} / ${s.syncTotal}")
                if (s.syncTotal > 0) {
                    LinearProgressIndicator(progress = { (s.syncSynced.toFloat() / s.syncTotal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
                s.syncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                KeyValueRow("待上传的新短信", "$outbox 条")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.syncNow() }) { Text("立即同步") }
                    OutlinedButton(onClick = { vm.fullResync() }) { Text("重新完整同步") }
                }
            }

            SectionCard("发送任务") {
                KeyValueRow("进行中 / 待上报", "$activeTasks 个")
                if (recentTasks.isEmpty()) Text("暂无发送任务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                recentTasks.forEach { TaskRow(it) }
            }

            SectionCard("开关") {
                SwitchRow("自动增量同步（监听短信库变化）", s.autoIncrementalSync) { vm.setAutoSync(it) }
                SwitchRow("读取联系人姓名", s.useContacts) { vm.setUseContacts(it) }
            }

            SectionCard("最近日志") {
                if (logs.isEmpty()) Text("暂无日志", style = MaterialTheme.typography.bodySmall)
                logs.forEach {
                    Text("${fmtTime(it.ts)} [${it.level}] ${it.message}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                TextButton(onClick = onLogs) { Text("查看全部日志") }
            }
        }
    }
}

@Composable
private fun TaskRow(t: SendTaskEntity) {
    val label = mapOf(
        "claimed" to "已领取", "sending" to "发送中", "sent" to "已发送", "delivered" to "已送达", "failed" to "失败", "unknown" to "结果未知",
    )[t.status] ?: t.status
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${t.taskId.take(8)} → ${Redact.phone(t.recipient)}", style = MaterialTheme.typography.bodySmall)
        Text(label + if (t.reportPending) "（待上报）" else "", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
