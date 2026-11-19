package com.wakeel.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.wakeel.agent.Hub
import com.wakeel.agent.core.TaskRecord
import com.wakeel.agent.core.TaskStatus
import com.wakeel.agent.core.Text as T

/** Full, formatted result of a finished task — opens automatically for long answers. */
class ResultActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "id"

        fun open(ctx: Context, recordId: Long) {
            val i = Intent(ctx, ResultActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ID, recordId)
            runCatching { ctx.startActivity(i) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val record = Hub.history.records.value.firstOrNull { it.id == id } ?: Hub.history.records.value.firstOrNull()
        if (record == null) { finish(); return }
        setContent {
            WakeelTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ResultSheet(record, onClose = { finish() }, onNew = { finish(); CommandActivity.open(this) })
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@Composable
private fun ResultSheet(r: TaskRecord, onClose: () -> Unit, onNew: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ok = r.status == TaskStatus.DONE
    val color = if (ok) Brand.Green else MaterialTheme.colorScheme.error
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.15f), modifier = Modifier.size(40.dp)) {
                        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = color, modifier = Modifier.padding(8.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (ok) "تمّت المهمة" else "تعذّرت المهمة", style = MaterialTheme.typography.titleLarge)
                        Text(r.task, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "إغلاق") }
                }

                // Body
                Box(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                    RichText(r.result.ifBlank { "—" }, Modifier.fillMaxWidth())
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Stats
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("التكلفة", T.money(r.cost), Modifier.weight(1f))
                    Stat("طلبات", r.calls.toString(), Modifier.weight(1f))
                    Stat("مجانية", r.localSteps.toString(), Modifier.weight(1f), Brand.Green)
                    Stat("المدة", "${((r.endedAt - r.startedAt) / 1000).coerceAtLeast(0)}ث", Modifier.weight(1f))
                }

                // Actions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("result", r.result))
                        Toast.makeText(ctx, "نُسخت النتيجة", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Rounded.ContentCopy, "نسخ") }
                    FilledTonalIconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r.result)
                        runCatching { ctx.startActivity(Intent.createChooser(send, "مشاركة النتيجة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Icon(Icons.Rounded.Share, "مشاركة") }
                    Button(onClick = onNew, modifier = Modifier.weight(1f)) { Text("مهمة جديدة", maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleSmall, color = accent, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
