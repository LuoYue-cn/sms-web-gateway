package com.smswebgateway.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.smswebgateway.android.Graph
import com.smswebgateway.android.sms.AppPermission
import com.smswebgateway.android.ui.AppViewModel
import com.smswebgateway.android.ui.SectionCard
import com.smswebgateway.android.ui.UiState
import com.smswebgateway.android.ui.okColor

fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(modifier: Modifier, vm: AppViewModel, state: UiState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshNow() }

    fun request(p: AppPermission) {
        Graph.prefs.markPermissionRequested(p.permission)
        launcher.launch(arrayOf(p.permission))
    }
    /** 已请求过且系统不再弹窗（永久拒绝）时，必须引导去系统设置，不能静默失败。 */
    fun permanentlyDenied(p: AppPermission): Boolean =
        Graph.prefs.permissionRequested(p.permission) && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, p.permission)

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("权限") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val missing = state.missingRequired
            if (missing.isNotEmpty()) {
                Text(
                    "缺少必需权限：" + missing.joinToString("、") { it.title } + "。对应功能将无法工作（不会静默失败，主页会持续提示）。",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = {
                    missing.forEach { Graph.prefs.markPermissionRequested(it.permission) }
                    launcher.launch(missing.map { it.permission }.toTypedArray())
                }, modifier = Modifier.fillMaxWidth()) { Text("一次授予全部必需权限") }
            } else {
                Text("必需权限已全部授予。", color = MaterialTheme.colorScheme.primary)
            }
            AppPermission.values().forEach { p ->
                val granted = state.permissions[p] == true
                SectionCard(p.title + if (p.required) "" else "（可选）") {
                    Text(p.purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (granted) "已授予" else "未授予", color = okColor(granted))
                        if (!granted) {
                            if (permanentlyDenied(p)) {
                                OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("去系统设置开启") }
                            } else {
                                Button(onClick = { request(p) }) { Text("授予") }
                            }
                        }
                    }
                    if (!granted && permanentlyDenied(p)) {
                        Text("系统已不再弹出授权框，请在系统设置 → 权限中手动开启。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            OutlinedButton(onClick = { openAppSettings(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("打开应用系统设置") }
        }
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
