@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wakeel.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeel.agent.Hub
import com.wakeel.agent.core.TaskRecord
import com.wakeel.agent.core.TaskStatus
import com.wakeel.agent.core.Text as T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("d/M  HH:mm", Locale.US)

@Composable
fun HistoryScreen() {
    val records by Hub.history.records.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val totalCost = records.sumOf { it.cost }
    val totalCalls = records.sumOf { it.calls }
    val totalLocal = records.sumOf { it.localSteps }
    val free = records.count { it.calls == 0 && it.status == TaskStatus.DONE }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("السجل", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (records.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "مسح السجل") }
            }
        }
        if (records.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("مهام", records.size.toString(), Modifier.weight(1f))
                    StatTile("بلا أي طلب", free.toString(), Modifier.weight(1f), Brand.Green)
                    StatTile("طلبات API", totalCalls.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                    StatTile("الإنفاق", T.money(totalCost), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                }
            }
            item {
                Text(
                    "نُفّذت $totalLocal خطوة محلياً دون تكلفة.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(records, key = { it.id }) { RecordCard(it) }
        } else {
            item { EmptyState(Icons.Rounded.History, "لا توجد مهام بعد") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح السجل؟") },
            text = { Text("سيُحذف سجل جميع المهام من هذا الجهاز. المهارات والمعرفة لا تتأثر.") },
            confirmButton = { TextButton(onClick = { Hub.history.clear(); confirmClear = false }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun RecordCard(r: TaskRecord) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val (label, color) = when (r.status) {
        TaskStatus.DONE -> "نجحت" to Brand.Green
        TaskStatus.FAILED -> "تعذّرت" to MaterialTheme.colorScheme.error
        TaskStatus.STOPPED -> "أُوقفت" to Brand.Amber
        TaskStatus.RUNNING -> "قيد التنفيذ" to MaterialTheme.colorScheme.tertiary
    }
    Panel(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(label, color)
            if (r.calls == 0 && r.status == TaskStatus.DONE) {
                Spacer(Modifier.padding(start = 6.dp))
                StatusChip("مجانية", MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.weight(1f))
            Text(dateFmt.format(Date(r.startedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(r.task, style = MaterialTheme.typography.titleSmall, maxLines = if (open) 10 else 2, overflow = TextOverflow.Ellipsis)
        if (r.result.isNotBlank()) {
            Text(
                r.result.lines().first(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Meta("التكلفة", T.money(r.cost))
            Meta("طلبات", r.calls.toString())
            Meta("خطوات", r.steps.toString())
            Meta("مجانية", r.localSteps.toString())
            Meta("المدة", "${((r.endedAt - r.startedAt) / 1000).coerceAtLeast(0)}ث")
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(onClick = { ResultActivity.open(ctx, r.id) }) { Text("عرض النتيجة كاملة") }
                Text(
                    "توكنز — إدخال: ${T.tokens(r.usage.input)} · من الذاكرة المؤقتة: ${T.tokens(r.usage.cacheRead)} · كتابة مؤقتة: ${T.tokens(r.usage.cacheWrite)} · إخراج: ${T.tokens(r.usage.output)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r.logs.forEach { LogRow(it) }
            }
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
