@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.EngineState
import com.wakeel.agent.agent.KnowledgeBase
import com.wakeel.agent.core.DeviceInfo
import com.wakeel.agent.core.DeviceSnapshot
import com.wakeel.agent.core.LogKind
import com.wakeel.agent.core.Text as T
import com.wakeel.agent.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val templates = listOf(
    "شخّص الهاتف",
    "ابحث عن سبب امتلاء مساحة التخزين واقترح حلولاً",
    "ابحث في يوتيوب عن تجارب علوم للأطفال",
    "افتح انستقرام وعدّل النبذة في ملفي إلى: …",
    "لخّص لي آخر الإشعارات",
    "شغّل الواي فاي",
    "منبه الساعة 6:00 صباحاً",
    "كم البطارية؟",
)

private val deepChecks = listOf(
    "ابحث عن سبب امتلاء مساحة التخزين واقترح حلولاً",
    "لماذا تنفد البطارية بسرعة؟ افحص واقترح حلولاً",
    "الهاتف بطيء، افحص الأسباب واقترح حلولاً",
    "الإنترنت ضعيف، افحص السبب واقترح حلولاً",
)

@Composable
fun HomeScreen(task: String, onTask: (String) -> Unit, onOpenSettings: () -> Unit, onOpenKnowledge: () -> Unit) {
    val ctx = LocalContext.current
    val engine = Hub.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val logs by engine.logs.collectAsStateWithLifecycle()
    val stats by engine.live.collectAsStateWithLifecycle()
    val lastResult by engine.lastResult.collectAsStateWithLifecycle()
    val serviceOn by AgentAccessibilityService.connected.collectAsStateWithLifecycle()
    val skillList by Hub.skills.skills.collectAsStateWithLifecycle()
    val records by Hub.history.records.collectAsStateWithLifecycle()
    val mapsVersion by Hub.maps.version.collectAsStateWithLifecycle()
    val hasKey = remember { Hub.settings.apiKey.isNotBlank() }
    val running = state !is EngineState.Idle
    var showDevice by remember { mutableStateOf(false) }

    val snap by produceState<DeviceSnapshot?>(initialValue = null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { DeviceInfo.snapshot(ctx) }.getOrNull() }
            delay(20_000)
        }
    }
    val known by produceState(initialValue = 0 to 0, mapsVersion) {
        value = withContext(Dispatchers.IO) {
            runCatching { Hub.apps.all().count { KnowledgeBase.guideFor(it.pkg) != null } to Hub.maps.totalScreens() }.getOrDefault(0 to 0)
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            onTask(if (task.isBlank()) it else "$task $it")
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- wordmark and status
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("وكيل", fontFamily = ArefRuqaa, fontWeight = FontWeight.Bold, fontSize = 42.sp, lineHeight = 50.sp, color = MaterialTheme.colorScheme.primary)
                    Text("ينجز مهام هاتفك، ويتعلّم ليستهلك أقل", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val ready = serviceOn && hasKey
                StatusChip(
                    when { running -> "يعمل الآن"; ready -> "جاهز"; serviceOn -> "أوامر محلية فقط"; else -> "يحتاج إعداداً" },
                    when { running -> MaterialTheme.colorScheme.tertiary; ready -> Brand.Green; else -> Brand.Amber },
                )
            }
        }

        if (!serviceOn || !hasKey) item { SetupPanel(serviceOn, hasKey, onOpenSettings) }

        // ---------- command
        item {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.khatam(MaterialTheme.colorScheme.secondary.copy(alpha = 0.07f)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("ماذا أنجز لك؟", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = task,
                        onValueChange = onTask,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("اكتب المهمة أو قلها…") },
                        minLines = 2,
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
                                colors = ButtonDefaults.buttonColors(containerColor = Brand.Ruby, contentColor = Color.White),
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
                }
            }
        }

        // ---------- suggestions: learned skills first (free), then examples
        if (!running) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(skillList.sortedByDescending { maxOf(it.lastUsed, it.createdAt) }.take(6), key = { "s" + it.id }) { sk ->
                    Suggestion(sk.title, learned = true) { onTask(if (sk.params.isEmpty()) sk.example else sk.pattern) }
                }
                items(templates) { t -> Suggestion(t, learned = false) { onTask(t) } }
            }
        }

        // ---------- live mission
        when (val s = state) {
            is EngineState.Confirm -> item { ConfirmPanel(s.question) }
            is EngineState.Ask -> item { AskPanel(s.question) }
            is EngineState.Running -> item { RunningPanel(s) }
            EngineState.Idle -> Unit
        }
        if (stats.calls > 0 || stats.localSteps > 0 || running) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("طلبات API", stats.calls.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
                    StatTile("تكلفة المهمة", T.money(stats.cost), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
                    StatTile("خطوات مجانية", stats.localSteps.toString(), Modifier.weight(1f), Brand.Green)
                }
            }
        }
        val result = lastResult
        if (!running && result != null) {
            item {
                val ok = logs.lastOrNull()?.kind == LogKind.SUCCESS
                val color = if (ok) Brand.Green else MaterialTheme.colorScheme.error
                Panel(accent = color, onClick = { records.firstOrNull()?.let { ResultActivity.open(ctx, it.id) } }) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = color)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(result.lines().first().take(160), style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (result.length > 160 || result.contains('\n')) {
                                Text("اضغط لعرض النتيجة كاملة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        // ---------- overview: device health and what the agent knows
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel(Modifier.weight(1f), onClick = { showDevice = true }) {
                    PanelHeader("صحة الجهاز", Icons.Rounded.HealthAndSafety, MaterialTheme.colorScheme.tertiary)
                    val s = snap
                    if (s == null) {
                        Text("جارٍ القراءة…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Meter("البطارية", "${s.batteryPct}%", s.batteryPct / 100f, if (s.batteryPct <= 15) Brand.Ruby else Brand.Green)
                        Meter("التخزين", "${s.storageUsedPct}%", s.storageUsedPct / 100f, level(s.storageUsedPct, 82, 92))
                        Meter("الذاكرة", "${s.ramUsedPct}%", s.ramUsedPct / 100f, level(s.ramUsedPct, 80, 90))
                        val issues = DeviceInfo.findings(s).size
                        Text(
                            if (issues == 0) "لا ملاحظات · ${DeviceInfo.networkAr(s)}" else "$issues ملاحظة · اضغط للتفاصيل",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (issues == 0) MaterialTheme.colorScheme.onSurfaceVariant else Brand.Amber,
                        )
                    }
                }
                Panel(Modifier.weight(1f), onClick = onOpenKnowledge) {
                    PanelHeader("ما يعرفه", Icons.Rounded.School, MaterialTheme.colorScheme.secondary)
                    Fact(known.first.toString(), "تطبيقاً بدليل مدمج")
                    Fact(known.second.toString(), "صفحة تعلّمها هنا")
                    Fact(skillList.size.toString(), "مهارة (${skillList.count { it.trusted }} مجانية)")
                    Text("اضغط لاستعراض المعرفة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("مهام منجزة", records.size.toString(), Modifier.weight(1f))
                StatTile("خطوات بلا تكلفة", records.sumOf { it.localSteps }.toString(), Modifier.weight(1f), Brand.Green)
                StatTile("إنفاق اليوم", T.money(Hub.settings.todaySpend()), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
            }
        }

        // ---------- log of the current / last mission
        if (logs.isNotEmpty()) {
            item { Text("مجريات المهمة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp)) }
            items(logs.asReversed()) { LogRow(it) }
        }
    }

    val s = snap
    if (showDevice && s != null) {
        ModalBottomSheet(onDismissRequest = { showDevice = false }) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("صحة الجهاز", style = MaterialTheme.typography.titleLarge)
                    Text("قراءة محلية فورية، دون أي استهلاك.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RichText(DeviceInfo.report(s), Modifier.fillMaxWidth())
                    Text("فحص أعمق يجريه وكيل داخل الإعدادات", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        deepChecks.forEach { q ->
                            Suggestion(q, learned = false) {
                                showDevice = false
                                engine.start(q)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun level(pct: Int, warn: Int, bad: Int): Color = when {
    pct >= bad -> Brand.Ruby
    pct >= warn -> Brand.Amber
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun Fact(value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Suggestion(text: String, learned: Boolean, onClick: () -> Unit) {
    val tint = if (learned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (learned) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.25f)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (learned) {
                Icon(Icons.Rounded.Bolt, null, tint = tint, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SetupPanel(serviceOn: Boolean, hasKey: Boolean, onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    Panel(accent = Brand.Amber) {
        PanelHeader("خطوتان للبدء", Icons.Rounded.Info, Brand.Amber)
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
                "إن ظهرت رسالة «إعداد محظور»: معلومات التطبيق ← ⋮ أعلى الشاشة ← «السماح بالإعدادات المقيّدة»، ثم ارجع وفعّل الخدمة.",
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
            body = "من تبويب الإعدادات. يُحفظ مشفّراً على جهازك فقط. الأوامر المحلية تعمل بدونه.",
            icon = Icons.Rounded.Key,
        ) {
            OutlinedButton(onClick = onOpenSettings) { Text("فتح الإعدادات") }
        }
    }
}

@Composable
private fun StepRow(done: Boolean, title: String, body: String, icon: ImageVector, actions: @Composable () -> Unit) {
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
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedVisibility(!done) { Column { actions() } }
        }
    }
}

@Composable
private fun RunningPanel(s: EngineState.Running) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Panel(accent = MaterialTheme.colorScheme.tertiary) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(10.dp).alpha(a)) {}
            Spacer(Modifier.width(10.dp))
            Text("المرحلة ${s.step} من ${s.max}", style = MaterialTheme.typography.titleSmall)
        }
        LinearProgressIndicator(
            progress = { (s.step.toFloat() / s.max).coerceIn(0.03f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(s.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConfirmPanel(q: String) {
    Panel(accent = Brand.Amber) {
        PanelHeader("مطلوب موافقتك", Icons.Rounded.WarningAmber, Brand.Amber)
        Text(q, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { Hub.engine.answerConfirm(true) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Green, contentColor = Color.White),
            ) { Text("سماح") }
            Button(
                onClick = { Hub.engine.answerConfirm(false) }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Brand.Ruby, contentColor = Color.White),
            ) { Text("رفض") }
        }
    }
}

@Composable
private fun AskPanel(q: String) {
    var answer by remember { mutableStateOf("") }
    Panel(accent = MaterialTheme.colorScheme.secondary) {
        PanelHeader("الوكيل يسألك", Icons.Rounded.Psychology, MaterialTheme.colorScheme.secondary)
        Text(q, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), placeholder = { Text("اكتب ردّك") })
        Button(onClick = { Hub.engine.answerAsk(answer) }, enabled = answer.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("إرسال") }
    }
}
