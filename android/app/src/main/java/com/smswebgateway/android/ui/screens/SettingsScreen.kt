package com.smswebgateway.android.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.ui.AppViewModel
import com.smswebgateway.android.ui.KeyValueRow
import com.smswebgateway.android.ui.SectionCard
import com.smswebgateway.android.ui.UiState
import com.smswebgateway.android.ui.okColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier, vm: AppViewModel, s: UiState, busy: Boolean,
    onRoot: () -> Unit, onLogs: () -> Unit, onPermissions: () -> Unit, onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var confirmUnbind by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("设置") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("后台运行（普通模式）") {
                KeyValueRow("前台服务", if (s.serviceRunning) "运行中" else "已停止", okColor(s.serviceRunning))
                KeyValueRow("电池优化白名单", if (s.batteryWhitelisted) "已加入" else "未加入", okColor(s.batteryWhitelisted))
                if (!s.batteryWhitelisted) {
                    Text(
                        "系统可能在息屏后限制本应用。建议允许「不受电池优化限制」，否则前台服务和同步可能被推迟。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { requestIgnoreBattery(ctx) }) { Text("去设置电池优化") }
                }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) {
                    Text("打开电池优化列表")
                }
            }
            SectionCard("权限") {
                val miss = s.missingRequired
                Text(if (miss.isEmpty()) "必需权限已全部授予" else "缺少：" + miss.joinToString("、") { it.title }, color = okColor(miss.isEmpty()))
                OutlinedButton(onClick = onPermissions) { Text("权限管理") }
            }
            SectionCard("Root 保活") {
                KeyValueRow("模式", if (s.rootEnabled && s.rootGranted) "Root 增强" else "普通（默认）")
                Text("默认关闭。Root 只做增强与恢复，短信收发不依赖 Root。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onRoot) { Text("Root 保活管理") }
            }
            SectionCard("日志") {
                OutlinedButton(onClick = onLogs) { Text("查看日志") }
                Text("日志不记录完整短信内容或令牌，号码仅显示部分位。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard("服务器") {
                KeyValueRow("地址", s.serverUrl)
                KeyValueRow("设备", "${s.deviceName} (#${s.deviceId})")
                KeyValueRow("版本", BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) "（debug）" else "")
                Button(
                    onClick = { confirmUnbind = true }, enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("解绑并清除本地数据") }
            }
        }
    }
    if (confirmUnbind) {
        AlertDialog(
            onDismissRequest = { confirmUnbind = false },
            title = { Text("确认解绑？") },
            text = { Text("将清除设备令牌、同步游标、待上传队列和本地日志，并停止后台服务；如已启用 Root 增强，也会停止守护并还原电池白名单。手机里的短信不受影响。") },
            confirmButton = { TextButton(onClick = { confirmUnbind = false; vm.unbind() }) { Text("解绑") } },
            dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } },
        )
    }
}

/** 优先弹出系统的「忽略电池优化」确认框，不支持时退回设置页。 */
fun requestIgnoreBattery(ctx: android.content.Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(direct)
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
