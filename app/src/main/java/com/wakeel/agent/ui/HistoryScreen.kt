package com.wakeel.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("مهام", records.size.toString(), Modifier.weight(1f))
                    StatTile("طلبات API", totalCalls.toString(), Modifier.weight(1f))
                    StatTile("الإنفاق", T.money(totalCost), Modifier.weight(1f), Brand.Teal)
                    StatTile("مجانية", totalLocal.toString(), Modifier.weight(1f), Brand.Green)
                }
            }
            items(records, key = { it.id }) { RecordCard(it) }
        } else {
            item {
                Box(Modifier.fillMaxSize().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.History, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.size(10.dp))
                        Text("لا توجد مهام بعد", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح السجل؟") },
            text = { Text("سيُحذف سجل جميع المهام من هذا الجهاز.") },
            confirmButton = { TextButton(onClick = { Hub.history.clear(); confirmClear = false }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RecordCard(r: TaskRecord) {
    var open by remember { mutableStateOf(false) }
    val (label, color) = when (r.status) {
        TaskStatus.DONE -> "نجحت" to Brand.Green
        TaskStatus.FAILED -> "تعذّرت" to MaterialTheme.colorScheme.error
        TaskStatus.STOPPED -> "أُوقفت" to Brand.Amber
        TaskStatus.RUNNING -> "قيد التنفيذ" to Brand.Teal
    }
    Card(
        onClick = { open = !open },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(label, color)
                Spacer(Modifier.weight(1f))
                Text(dateFmt.format(Date(r.startedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(r.task, style = MaterialTheme.typography.titleSmall, maxLines = if (open) 10 else 2)
            if (r.result.isNotBlank()) Text(r.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (open) 20 else 2)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Meta("التكلفة", T.money(r.cost))
                Meta("طلبات", r.calls.toString())
                Meta("خطوات", r.steps.toString())
                Meta("مجانية", r.localSteps.toString())
                Meta("المدة", "${((r.endedAt - r.startedAt) / 1000).coerceAtLeast(0)}ث")
            }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "توكنز — إدخال: ${T.tokens(r.usage.input)} · من الذاكرة المؤقتة: ${T.tokens(r.usage.cacheRead)} · كتابة مؤقتة: ${T.tokens(r.usage.cacheWrite)} · إخراج: ${T.tokens(r.usage.output)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    r.logs.forEach { LogRow(it) }
                }
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

