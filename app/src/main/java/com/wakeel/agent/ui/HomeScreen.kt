package com.wakeel.agent.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.EngineState
import com.wakeel.agent.core.LogEntry
import com.wakeel.agent.core.LogKind
import com.wakeel.agent.core.Text as T
import com.wakeel.agent.service.AgentAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val templates = listOf(
    "افتح يوتيوب وابحث عن تجارب علوم للأطفال",
    "لخّص لي آخر الإشعارات",
    "افتح الإعدادات وفعّل الوضع الداكن",
    "منبه الساعة 6:00 صباحاً",
    "افتح واتساب وأرسل لـ … رسالة: …",
    "ابحث في جوجل عن الطقس في الدوحة",
    "افتح انستقرام وعدّل النبذة في ملفي إلى: …",
    "افتح انستقرام وأنشئ ريلز من آخر فيديو مع وصف: …",
)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val engine = Hub.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val logs by engine.logs.collectAsStateWithLifecycle()
    val stats by engine.live.collectAsStateWithLifecycle()
    val lastResult by engine.lastResult.collectAsStateWithLifecycle()
    val serviceOn by AgentAccessibilityService.connected.collectAsStateWithLifecycle()
    val hasKey = remember { Hub.settings.apiKey.isNotBlank() }
    var task by rememberSaveable { mutableStateOf("") }
    val running = state !is EngineState.Idle

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            task = if (task.isBlank()) it else "$task $it"
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Hero(serviceOn, hasKey, running) }

        if (!serviceOn || !hasKey) item { SetupCard(serviceOn, hasKey, onOpenSettings) }

        item {
            SectionCard {
                OutlinedTextField(
                    value = task,
                    onValueChange = { task = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ماذا تريد أن أفعل على هاتفك؟") },
                    minLines = 3,
                    maxLines = 6,
                    shape = MaterialTheme.shapes.medium,
                    enabled = !running,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!running) {
                        Button(
                            onClick = { engine.start(task) },
                            enabled = serviceOn && task.isNotBlank(),
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("نفّذ المهمة")
                        }
                    } else {
                        Button(
                            onClick = { engine.stop() },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Red, contentColor = Color.White),
                        ) {
                            Icon(Icons.Rounded.Stop, null)
                            Spacer(Modifier.width(8.dp))
                            Text("إيقاف فوري")
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledTonalIconButton(
                        onClick = {
                            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "قل المهمة…")
                            try {
                                voice.launch(i)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(ctx, "الإدخال الصوتي غير متاح على هذا الجهاز", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = !running,
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.Rounded.Mic, "إدخال صوتي") }
                }
                if (!running) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.forEach { t ->
                            Surface(
                                onClick = { task = t },
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ) {
                                Text(t, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        when (val s = state) {
            is EngineState.Confirm -> item { ConfirmCard(s.question) }
            is EngineState.Ask -> item { AskCard(s.question) }
            is EngineState.Running -> item { RunningCard(s) }
            EngineState.Idle -> Unit
        }

        if (stats.calls > 0 || stats.localSteps > 0 || running) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("طلبات API", stats.calls.toString(), Modifier.weight(1f))
                    StatTile("تكلفة المهمة", T.money(stats.cost), Modifier.weight(1f), Brand.Teal)
                    StatTile("خطوات مجانية", stats.localSteps.toString(), Modifier.weight(1f), Brand.Green)
                }
            }
        }

        if (!running && lastResult != null) item { ResultCard(lastResult!!, logs.lastOrNull()?.kind == LogKind.SUCCESS) }

        if (logs.isNotEmpty()) {
            item { Text("مجريات المهمة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(logs.asReversed()) { LogRow(it) }
        }
    }
}

@Composable
private fun Hero(serviceOn: Boolean, hasKey: Boolean, running: Boolean) {
    val ready = serviceOn && hasKey
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Brand.IndigoDeep, Brand.Indigo, Brand.Teal)),
                RoundedCornerShape(28.dp),
            )
            .padding(22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Text("وكيل", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.18f)) {
                    Text(
                        when { running -> "يعمل الآن"; ready -> "جاهز"; else -> "يحتاج إعداداً" },
                        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = Color.White, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text("مساعدك الذكي الذي يرى الشاشة وينفّذ المهام نيابةً عنك", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
            Text("إنفاق اليوم: ${T.money(Hub.settings.todaySpend())} من ${T.money(Hub.settings.dailyBudget.toDouble())}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SetupCard(serviceOn: Boolean, hasKey: Boolean, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard(title = "خطوتان للبدء", icon = Icons.Rounded.Info) {
        StepRow(
            done = serviceOn,
            title = "فعّل خدمة «وكيل» في إمكانية الوصول",
            body = "الإعدادات ← إمكانية الوصول ← التطبيقات المثبّتة ← وكيل ← تشغيل.",
            icon = Icons.Rounded.AccessibilityNew,
        ) {
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("فتح إعدادات إمكانية الوصول") }
            Text(
                "إن ظهرت رسالة «إعداد محظور»: افتح معلومات التطبيق ← القائمة ⋮ أعلى الشاشة ← «السماح بالإعدادات المحظورة»، ثم ارجع وفعّل الخدمة.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("معلومات التطبيق") }
        }
        StepRow(
            done = hasKey,
            title = "أضف مفتاح API",
            body = "من تبويب الإعدادات. يُحفظ مشفّراً على جهازك فقط.",
            icon = Icons.Rounded.Key,
        ) {
            OutlinedButton(onClick = onOpenSettings) { Text("فتح الإعدادات") }
        }
    }
}

@Composable
private fun StepRow(done: Boolean, title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, actions: @Composable () -> Unit) {
    Row {
        Icon(
            if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
            tint = if (done) Brand.Green else MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp).size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(!done) { Column { actions() } }
        }
    }
}

@Composable
private fun RunningCard(s: EngineState.Running) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = Brand.Teal, modifier = Modifier.size(10.dp).alpha(a)) {}
            Spacer(Modifier.width(10.dp))
            Text("الخطوة ${s.step} من ${s.max}", style = MaterialTheme.typography.titleSmall)
        }
        LinearProgressIndicator(
            progress = { (s.step.toFloat() / s.max).coerceIn(0.02f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(s.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
    }
}

@Composable
private fun ConfirmCard(q: String) {
    SectionCard(title = "مطلوب موافقتك", icon = Icons.Rounded.WarningAmber) {
        Text(q, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Hub.engine.answerConfirm(true) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Green, contentColor = Color.White),
            ) { Text("سماح") }
            Button(
                onClick = { Hub.engine.answerConfirm(false) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Red, contentColor = Color.White),
            ) { Text("رفض") }
        }
    }
}

@Composable
private fun AskCard(q: String) {
    var answer by remember { mutableStateOf("") }
    SectionCard(title = "الوكيل يسألك", icon = Icons.Rounded.Psychology) {
        Text(q, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), placeholder = { Text("اكتب ردّك") })
        Button(onClick = { Hub.engine.answerAsk(answer) }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("إرسال") }
    }
}

@Composable
private fun ResultCard(text: String, success: Boolean) {
    val color = if (success) Brand.Green else MaterialTheme.colorScheme.error
    Surface(shape = MaterialTheme.shapes.large, color = color.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun LogRow(e: LogEntry) {
    val (icon, color) = when (e.kind) {
        LogKind.THINK -> Icons.Rounded.Psychology to MaterialTheme.colorScheme.primary
        LogKind.ACTION -> Icons.Rounded.TouchApp to MaterialTheme.colorScheme.secondary
        LogKind.SUCCESS -> Icons.Rounded.CheckCircle to Brand.Green
        LogKind.WARN -> Icons.Rounded.WarningAmber to Brand.Amber
        LogKind.ERROR -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        LogKind.LOCAL -> Icons.Rounded.Bolt to Brand.Green
        LogKind.INFO -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(timeFmt.format(Date(e.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
