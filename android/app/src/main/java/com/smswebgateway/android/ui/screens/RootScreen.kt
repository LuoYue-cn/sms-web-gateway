package com.smswebgateway.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smswebgateway.android.root.RootManager
import com.smswebgateway.android.ui.AppViewModel
import com.smswebgateway.android.ui.KeyValueRow
import com.smswebgateway.android.ui.SectionCard
import com.smswebgateway.android.ui.UiState
import com.smswebgateway.android.ui.fmtTime
import com.smswebgateway.android.ui.okColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(modifier: Modifier, vm: AppViewModel, s: UiState, busy: Boolean, onLogs: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmEnable by remember { mutableStateOf(false) }
    var wdLog by remember { mutableStateOf<String?>(null) }
    val enhanced = s.rootEnabled && s.rootGranted

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Root 保活管理") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("状态") {
                KeyValueRow("保活模式", if (enhanced) "Root 增强" else "普通")
                KeyValueRow("Root 权限", if (s.rootGranted) "已授权" else "未授权", okColor(s.rootGranted))
                KeyValueRow("Root 管理器", s.rootManager ?: s.managerHint ?: "未检测到")
                KeyValueRow("电池优化白名单", if (s.batteryWhitelisted) "已加入" else "未加入", okColor(s.batteryWhitelisted))
                KeyValueRow("前台服务", if (s.serviceRunning) "运行中" else "已停止", okColor(s.serviceRunning))
                KeyValueRow(
                    "Root Watchdog",
                    when (s.watchdogStatus) { "running" -> "运行中"; "stopped" -> "已停止"; else -> "未安装" },
                    okColor(s.watchdogStatus == "running"),
                )
                KeyValueRow("开机自启", "已启用（BOOT_COMPLETED）")
                KeyValueRow("最近成功同步", fmtTime(s.lastSyncAt))
                KeyValueRow("最近服务器心跳", fmtTime(s.lastHeartbeatAt))
                KeyValueRow("最近自动恢复", fmtTime(s.lastRecoveryAt))
                KeyValueRow("自动恢复次数", s.recoveryCount.toString())
                KeyValueRow(
                    "保护状态",
                    when (s.protectionState) { "cooldown" -> "冷却中"; "protected" -> "保护中（已停止自动恢复）"; else -> "正常" },
                    if (s.protectionState == "none") androidx.compose.ui.graphics.Color.Unspecified else MaterialTheme.colorScheme.error,
                )
            }

            SectionCard("Root 增强") {
                Text(
                    "默认关闭。启用后会：申请 Root 权限、把本应用加入电池优化白名单（记录原始状态，关闭时还原；原本就在白名单的不会被移除）、启用已安装的 Watchdog 模块。不会 force-stop 任何应用、不改 SELinux、不修改系统分区。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (enhanced) {
                    OutlinedButton(onClick = { vm.disableRoot() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("禁用 Root 增强（停止守护并还原设置）") }
                } else {
                    Button(onClick = { confirmEnable = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("启用 Root 增强") }
                }
                if (!s.rootGranted && s.rootEnabled.not() && s.rootManager != null) {
                    Text("提示：Root 权限已失效或未授予，已处于普通模式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            SectionCard("Watchdog 模块") {
                if (!s.moduleBundled) {
                    Text("此 APK 构建时未找到 ../root-module/，没有内置 Watchdog。可重新构建，或手动刷入 smsgate-watchdog.zip。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { vm.installWatchdog() }, enabled = !busy && s.rootGranted && s.moduleBundled, modifier = Modifier.fillMaxWidth()) { Text("安装 Watchdog 模块") }
                Row2 {
                    OutlinedButton(onClick = { vm.setWatchdog(true) }, enabled = !busy && s.rootGranted && s.watchdogStatus != "not_installed") { Text("启用") }
                    OutlinedButton(onClick = { vm.setWatchdog(false) }, enabled = !busy && s.rootGranted && s.watchdogStatus != "not_installed") { Text("禁用") }
                }
                Text("模块安装后需重启手机，由 Root 管理器加载并启动守护。Watchdog 不联网、不接受服务器命令。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("维护") {
                OutlinedButton(onClick = { vm.checkBackground() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("检查后台运行状态") }
                OutlinedButton(onClick = { vm.testRecovery() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("测试自动恢复") }
                OutlinedButton(onClick = {
                    scope.launch { wdLog = withContext(Dispatchers.IO) { RootManager.readWatchdogLog() } }
                }, enabled = !busy && s.rootGranted, modifier = Modifier.fillMaxWidth()) { Text("查看保活日志（Watchdog）") }
                OutlinedButton(onClick = onLogs, modifier = Modifier.fillMaxWidth()) { Text("查看应用日志") }
                if (s.protectionState == "protected" || s.protectionState == "cooldown") {
                    OutlinedButton(onClick = { vm.resetProtection() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("重置保护状态") }
                }
                OutlinedButton(onClick = { vm.restoreOriginal() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("恢复系统原始设置") }
            }
        }
    }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("启用 Root 增强？") },
            text = { Text("接下来会请求超级用户权限。Magisk 会弹窗询问；KernelSU / APatch 不弹窗，需要你先在管理器的「超级用户」列表里开启本应用。仅执行预定义的白名单命令，所有操作都会写入审计日志。") },
            confirmButton = { TextButton(onClick = { confirmEnable = false; vm.enableRoot() }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("取消") } },
        )
    }
    wdLog?.let { log ->
        AlertDialog(
            onDismissRequest = { wdLog = null },
            title = { Text("Watchdog 日志") },
            text = { Text(log, style = MaterialTheme.typography.bodySmall, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { wdLog = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun Row2(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
