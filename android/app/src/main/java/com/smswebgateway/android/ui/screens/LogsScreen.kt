package com.smswebgateway.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smswebgateway.android.Graph
import com.smswebgateway.android.ui.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(modifier: Modifier, onBack: () -> Unit) {
    val logs by Graph.db.logs().recentFlow(300).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("日志") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            actions = { TextButton(onClick = { scope.launch(Dispatchers.IO) { Graph.db.logs().clear() } }) { Text("清空") } },
        )
        Text("已脱敏：不含完整短信内容、完整号码或令牌。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (logs.isEmpty()) item { Text("暂无日志") }
            items(logs, key = { it.id }) {
                val color = when (it.level) { "E" -> MaterialTheme.colorScheme.error; "W" -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurface }
                Text("${fmtTime(it.ts)} ${it.level}/${it.tag}  ${it.message}", style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
    }
}
