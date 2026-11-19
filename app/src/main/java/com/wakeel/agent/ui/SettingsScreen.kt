package com.wakeel.agent.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeel.agent.BuildConfig
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.AppIndex
import com.wakeel.agent.core.Presets
import com.wakeel.agent.core.Text as T
import com.wakeel.agent.llm.LlmClient
import com.wakeel.agent.service.AgentAccessibilityService
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val s = Hub.settings
    var provider by remember { mutableStateOf(s.provider) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("الإعدادات", style = MaterialTheme.typography.headlineMedium) }

        // ---------------- Provider & key ----------------
        item {
            SectionCard(title = "مزوّد الذكاء الاصطناعي", icon = Icons.Rounded.Cloud) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Presets.all.forEach { p ->
                        FilterChip(selected = provider == p.id, onClick = { s.provider = p.id; provider = p.id }, label = { Text(p.title) })
                    }
                }
                key(provider) { ProviderFields() }
            }
        }

        // ---------------- Budget ----------------
        item {
            SectionCard(title = "الميزانية والتوفير", icon = Icons.Rounded.Savings) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("حد المهمة ($)", s.taskBudget.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.taskBudget = v } }
                    NumberField("الحد اليومي ($)", s.dailyBudget.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.dailyBudget = v } }
                }
                NumberField("أقصى عدد خطوات للمهمة", s.maxSteps.toString(), Modifier.fillMaxWidth(), decimal = false) { it.toIntOrNull()?.let { v -> s.maxSteps = v } }
                Text("إنفاق اليوم: ${T.money(s.todaySpend())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                var learn by remember { mutableStateOf(s.autoLearn) }
                SwitchRow("التعلّم التلقائي", "يحوّل كل مهمة ناجحة إلى مهارة بمتغيّرات تُنفَّذ لاحقاً محلياً دون استهلاك، ويحفظ ملاحظات عن شكل التطبيقات", learn) { learn = it; s.autoLearn = it }
                val skillList by Hub.skills.skills.collectAsStateWithLifecycle()
                val noteCount by Hub.notes.count.collectAsStateWithLifecycle()
                var showSkills by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("المهارات المتعلَّمة: ${skillList.size}", style = MaterialTheme.typography.bodyMedium)
                        Text("موثوقة (دون أي استهلاك): ${skillList.count { it.trusted }} · ملاحظات التطبيقات: $noteCount", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { showSkills = true }) { Text("إدارة") }
                }
                if (showSkills) SkillsDialog(onDismiss = { showSkills = false })
                var shots by remember { mutableStateOf(s.allowScreenshots) }
                SwitchRow("السماح بلقطات الشاشة عند الحاجة", "تُرسل مصغّرة وفقط حين لا يكفي نص الشاشة", shots) { shots = it; s.allowScreenshots = it }
                var esc by remember { mutableStateOf(s.escalate) }
                SwitchRow("الرفع التلقائي للنموذج الأقوى", "يُستخدم فقط عند التعثّر ثم يعود للنموذج الأرخص", esc) { esc = it; s.escalate = it }
                Text("الأسعار لكل مليون توكن ($)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("سريع: إدخال", s.fastIn.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.fastIn = v } }
                    NumberField("سريع: إخراج", s.fastOut.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.fastOut = v } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("قوي: إدخال", s.strongIn.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.strongIn = v } }
                    NumberField("قوي: إخراج", s.strongOut.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.strongOut = v } }
                }
            }
        }

        // ---------------- Safety ----------------
        item {
            SectionCard(title = "الأمان والصلاحيات", icon = Icons.Rounded.Shield) {
                var confirm by remember { mutableStateOf(s.confirmSensitive) }
                SwitchRow("تأكيد الأفعال الحساسة", "يطلب موافقتك قبل الإرسال والدفع والحذف والشراء والاتصال", confirm) { confirm = it; s.confirmSensitive = it }
                var picker by remember { mutableStateOf<String?>(null) }
                var blocked by remember { mutableStateOf(s.blockedPkgs) }
                var allowed by remember { mutableStateOf(s.allowedPkgs) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("تطبيقات محجوبة", style = MaterialTheme.typography.bodyLarge)
                        Text("${blocked.size} تطبيق — لن يلمسها الوكيل أبداً", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { picker = "blocked" }) { Text("اختيار") }
                }
                var allowMode by remember { mutableStateOf(s.allowlistMode) }
                SwitchRow("وضع القائمة البيضاء", "يعمل الوكيل داخل التطبيقات المختارة فقط", allowMode) { allowMode = it; s.allowlistMode = it }
                if (allowMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${allowed.size} تطبيق مسموح", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { picker = "allowed" }) { Text("اختيار") }
                    }
                }
                var kw by remember { mutableStateOf(s.blockedKeywords) }
                OutlinedTextField(
                    value = kw, onValueChange = { kw = it; s.blockedKeywords = it },
                    label = { Text("كلمات حجب تلقائي في اسم الحزمة") },
                    supportingText = { Text("تحمي تطبيقات البنوك والمحافظ ومديري كلمات المرور تلقائياً") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 4,
                )
                Text(
                    "ثوابت لا تتغيّر: لا يكتب الوكيل في حقول كلمات المرور، ولا يتحكم بتطبيقه نفسه، ولا يعمل إلا حين تعطيه مهمة.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                picker?.let { which ->
                    AppPickerDialog(
                        title = if (which == "blocked") "التطبيقات المحجوبة" else "التطبيقات المسموحة",
                        initial = if (which == "blocked") blocked else allowed,
                        onDismiss = { picker = null },
                        onSave = { set ->
                            if (which == "blocked") { s.blockedPkgs = set; blocked = set } else { s.allowedPkgs = set; allowed = set }
                            picker = null
                        },
                    )
                }
            }
        }

        // ---------------- Interface ----------------
        item {
            SectionCard(title = "الواجهة", icon = Icons.Rounded.Palette) {
                var bubble by remember { mutableStateOf(s.showBubble) }
                SwitchRow("الفقاعة العائمة", "زر فوق كل التطبيقات لإعطاء الأوامر والإيقاف الفوري", bubble) {
                    bubble = it; s.showBubble = it
                    AgentAccessibilityService.instance?.setBubbleVisible(it)
                }
                Text("الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProviderFields() {
    val s = Hub.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val preset = s.preset
    var keyInput by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(s.apiKey) }
    var show by remember { mutableStateOf(false) }
    var base by remember { mutableStateOf(s.baseUrl) }
    var fast by remember { mutableStateOf(s.fastModel) }
    var strong by remember { mutableStateOf(s.strongModel) }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (saved.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = Brand.Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("المفتاح محفوظ ومشفّر  ••••${saved.takeLast(4)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { s.apiKey = ""; saved = "" }) { Text("حذف") }
            }
        }
        OutlinedTextField(
            value = keyInput, onValueChange = { keyInput = it },
            label = { Text(if (saved.isBlank()) "مفتاح API" else "استبدال المفتاح") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { s.apiKey = keyInput; saved = keyInput.trim(); keyInput = "" }, enabled = keyInput.isNotBlank()) { Text("حفظ المفتاح") }
            if (preset.keyUrl.isNotBlank()) {
                OutlinedButton(onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preset.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("احصل على مفتاح") }
            }
        }
        OutlinedTextField(base, { base = it; s.baseUrl = it }, label = { Text("عنوان الخادم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            fast, { fast = it; s.fastModel = it }, label = { Text("النموذج السريع (الافتراضي)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { if (preset.fast.isBlank()) Text("اكتب اسم نموذج يدعم الصور واستدعاء الأدوات") },
        )
        OutlinedTextField(strong, { strong = it; s.strongModel = it }, label = { Text("النموذج الأقوى (عند التعثّر)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    testing = true; testMsg = null
                    scope.launch {
                        testMsg = runCatching {
                            val r = LlmClient.create(s).decide(s.fastModel, "TASK: connection test — call act with a single done action, text \"ok\".\nSCREEN (0 elements):\n", null)
                            if (r.decision.actions.isNotEmpty()) "✓ الاتصال ناجح — ${s.fastModel}" else "⚠ وصل رد لكن دون أوامر صالحة"
                        }.getOrElse { "✗ ${it.message}" }
                        testing = false
                    }
                },
                enabled = !testing && saved.isNotBlank() && fast.isNotBlank(),
            ) { Text("اختبار الاتصال") }
            Spacer(Modifier.width(10.dp))
            if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SkillsDialog(onDismiss: () -> Unit) {
    val list by Hub.skills.skills.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("المهارات المتعلَّمة") },
        text = {
            if (list.isEmpty()) {
                Text("لا توجد مهارات بعد. كل مهمة تنجح يتعلّمها الوكيل تلقائياً.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Box(Modifier.heightIn(max = 420.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list.sortedByDescending { it.lastUsed.coerceAtLeast(it.createdAt) }, key = { it.id }) { sk ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(sk.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                        Spacer(Modifier.width(6.dp))
                                        if (sk.trusted) StatusChip("مجانية", Brand.Green) else StatusChip("تحت التجربة", Brand.Amber)
                                    }
                                    Text(sk.pattern, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        "${sk.steps.size} خطوة · نجحت ${sk.successes} · تعثّرت ${sk.fails}" + if (sk.readout) " · تقرأ معلومات" else "",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { Hub.skills.remove(sk.id) }) { Text("حذف") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
        dismissButton = {
            if (list.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("مسح الكل") }
        },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل ما تعلّمه الوكيل؟") },
            text = { Text("ستُحذف كل المهارات وملاحظات التطبيقات، وسيعود لاستخدام الذكاء الاصطناعي في كل مهمة.") },
            confirmButton = { TextButton(onClick = { Hub.skills.clear(); Hub.notes.clear(); confirmClear = false; onDismiss() }) { Text("مسح") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun NumberField(label: String, initial: String, modifier: Modifier, decimal: Boolean = true, onValue: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; onValue(it) },
        label = { Text(label, maxLines = 1) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}

@Composable
private fun AppPickerDialog(title: String, initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val apps = remember { Hub.apps.all() }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    val shown = remember(query, apps) {
        val q = T.norm(query)
        if (q.isBlank()) apps else apps.filter { it.norm.contains(q) || it.pkg.contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text("بحث") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Box(Modifier.heightIn(max = 380.dp)) {
                    LazyColumn {
                        items(shown, key = { it.pkg }) { a: AppIndex.App ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Checkbox(checked = a.pkg in selected, onCheckedChange = { c -> selected = if (c) selected + a.pkg else selected - a.pkg })
                                Column {
                                    Text(a.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(a.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
