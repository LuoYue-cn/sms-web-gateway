package com.smswebgateway.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smswebgateway.android.BuildConfig
import com.smswebgateway.android.ui.AppViewModel
import com.smswebgateway.android.ui.UiState

@Composable
fun UnboundScreen(modifier: Modifier, state: UiState, onScan: () -> Unit, onManual: () -> Unit, onPermissions: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("短信网关", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("尚未绑定服务器", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "在网页「设备管理 → 添加设备」生成绑定二维码，然后扫码；也可以手动输入服务器地址和配对令牌。",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("扫描二维码") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("手动输入服务器地址") }
        Spacer(Modifier.height(24.dp))
        if (state.missingRequired.isNotEmpty()) {
            Text(
                "还有 ${state.missingRequired.size} 项必需权限未授予：" + state.missingRequired.joinToString("、") { it.title },
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
        }
        TextButton(onClick = onPermissions) { Text("权限设置") }
        if (BuildConfig.DEBUG) {
            Text(
                "调试版：允许 http 连接本机 / 局域网（如 adb reverse 后的 http://127.0.0.1:8080）。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairScreen(
    modifier: Modifier, vm: AppViewModel, initial: Pair<String, String>?, busy: Boolean,
    onScan: () -> Unit, onDone: () -> Unit, onBack: () -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(initial?.first ?: "") }
    var token by rememberSaveable { mutableStateOf(initial?.second ?: "") }
    LaunchedEffect(initial) {
        if (initial != null) { server = initial.first; token = initial.second }
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("绑定服务器") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = server, onValueChange = { server = it }, label = { Text("服务器地址") },
                placeholder = { Text(if (BuildConfig.DEBUG) "https://sms.example.com 或 http://127.0.0.1:8080" else "https://sms.example.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it.trim() }, label = { Text("配对令牌（5 分钟内有效，只能用一次）") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            Button(
                onClick = { vm.pair(server, token) { ok -> if (ok) onDone() } },
                enabled = !busy && server.isNotBlank() && token.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "正在绑定…" else "绑定") }
            OutlinedButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("改为扫描二维码") }
            Text(
                "正式使用必须通过 HTTPS，并校验服务器证书；证书错误时连接会直接失败，不提供忽略选项。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
