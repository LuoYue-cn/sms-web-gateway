package com.smswebgateway.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtTime(ms: Long): String =
    if (ms <= 0) "从未" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

fun fmtAgo(ms: Long, now: Long): String {
    if (ms <= 0) return "从未"
    val s = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s} 秒前"
        s < 3600 -> "${s / 60} 分钟前"
        s < 86400 -> "${s / 3600} 小时前"
        else -> "${s / 86400} 天前"
    }
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun okColor(ok: Boolean): Color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
