@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.wakeel.agent.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeel.agent.Hub
import com.wakeel.agent.agent.AppIndex
import com.wakeel.agent.agent.KnowledgeBase
import com.wakeel.agent.agent.Mastery
import com.wakeel.agent.agent.Safety
import com.wakeel.agent.agent.Skill
import com.wakeel.agent.core.Policy
import com.wakeel.agent.core.Text as T
import com.wakeel.agent.service.AgentAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class AppRow(
    val app: AppIndex.App,
    val category: String,
    val hasGuide: Boolean,
    val screens: Int,
    val skills: Int,
    val notes: Int,
    val policy: Policy,
) {
    val score: Int = Mastery.score(hasGuide, screens, skills, notes)
}

@Composable
fun KnowledgeScreen(onRun: (String) -> Unit, onDraft: (String) -> Unit, onGoHome: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    val skillList by Hub.skills.skills.collectAsStateWithLifecycle()
    val mapsVersion by Hub.maps.version.collectAsStateWithLifecycle()

    val rows by produceState(initialValue = emptyList<AppRow>(), mapsVersion, skillList, refresh) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Hub.apps.all().map { a ->
                    AppRow(
                        app = a,
                        category = Hub.apps.categoryOf(a.pkg),
                        hasGuide = KnowledgeBase.guideFor(a.pkg) != null,
                        screens = Hub.maps.screenCount(a.pkg),
                        skills = skillList.count { it.pkg == a.pkg },
                        notes = Hub.notes.get(a.pkg).size,
                        policy = Safety.policy(a.pkg, Hub.settings, Hub.apps),
                    )
                }.sortedWith(compareByDescending<AppRow> { it.score }.thenBy { it.app.label.lowercase() })
            }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("المعرفة", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("تطبيق يعرفه", rows.count { it.score > 0 }.toString(), Modifier.weight(1f))
            StatTile("صفحة متعلَّمة", rows.sumOf { it.screens }.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.tertiary)
            StatTile("مهارة", skillList.size.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.secondary)
        }
        Segmented(listOf("التطبيقات", "المهارات"), tab) { tab = it }

        if (tab == 0) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("ابحث عن تطبيق") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = MaterialTheme.shapes.medium,
            )
            Segmented(listOf("الكل", "يعرفها", "مقيّدة"), filter) { filter = it }
            val q = T.norm(query)
            val shown = rows.filter { r ->
                (q.isBlank() || r.app.norm.contains(q) || r.app.pkg.contains(q) || T.norm(r.category).contains(q)) &&
                    when (filter) { 1 -> r.score > 0; 2 -> r.policy != Policy.ALLOWED; else -> true }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.app.pkg }) { r -> AppRowItem(r) { selected = r.app.pkg } }
                if (shown.isEmpty()) item { EmptyState(Icons.Rounded.School, if (rows.isEmpty()) "جارٍ قراءة التطبيقات…" else "لا توجد نتائج") }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "كل مهمة تنجح تتحوّل إلى مهارة. المهارة «المجانية» تُنفَّذ بلا أي طلب API، و«تحت التجربة» تحتاج إلى طلب تحقق واحد.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(skillList.sortedByDescending { maxOf(it.lastUsed, it.createdAt) }, key = { it.id }) { sk ->
                    SkillItem(sk, onRun = { if (sk.params.isEmpty()) onRun(sk.example) else onDraft(sk.pattern) })
                }
                if (skillList.isEmpty()) item { EmptyState(Icons.Rounded.Bolt, "لا توجد مهارات بعد.\nنفّذ أي مهمة وسيتعلّمها وكيل تلقائياً.") }
            }
        }
    }

    val row = rows.firstOrNull { it.app.pkg == selected }
    if (row != null) {
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                AppDetail(
                    row,
                    onChanged = { refresh++ },
                    onExplore = {
                        selected = null
                        Hub.engine.explore(row.app.pkg)
                        onGoHome()
                    },
                )
            }
        }
    }
}

@Composable
private fun AppRowItem(r: AppRow, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(r.app.pkg, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    when (r.policy) {
                        Policy.BLOCKED -> { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Lock, "محجوب", Modifier.size(15.dp), tint = Brand.Ruby) }
                        Policy.READ_ONLY -> { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Visibility, "قراءة فقط", Modifier.size(15.dp), tint = Brand.Amber) }
                        Policy.ALLOWED -> Unit
                    }
                }
                Meter(
                    r.category,
                    Mastery.level(r.score) + if (r.screens + r.skills > 0) " · ${r.screens} صفحة · ${r.skills} مهارة" else "",
                    r.score / 100f,
                    if (r.score >= 45) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun AppDetail(r: AppRow, onChanged: () -> Unit, onExplore: () -> Unit) {
    val ctx = LocalContext.current
    val s = Hub.settings
    val pkg = r.app.pkg
    var explicit by remember(pkg) { mutableStateOf(s.explicitPolicy(pkg)) }
    val effective = Safety.policy(pkg, s, Hub.apps)
    val screens = remember(pkg, r.screens) { Hub.maps.screens(pkg) }
    val skills = remember(pkg, r.skills) { Hub.skills.forPackage(pkg) }
    val notes = remember(pkg, r.notes) { Hub.notes.get(pkg) }
    val guide = remember(pkg) { KnowledgeBase.guideFor(pkg) }
    val serviceOn by AgentAccessibilityService.connected.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(pkg, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.app.label, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.category + " · " + pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Meter("مستوى معرفة وكيل بالتطبيق", "${Mastery.level(r.score)} · ${r.score}%", r.score / 100f, MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (guide != null) "دليل مدمج: ${guide.name}" else "بلا دليل مدمج", if (guide != null) Brand.Green else MaterialTheme.colorScheme.outline)
            StatusChip("${r.screens} صفحة", MaterialTheme.colorScheme.tertiary)
            StatusChip("${r.skills} مهارة", MaterialTheme.colorScheme.secondary)
        }

        // ---- what the agent may do here
        Text("صلاحية وكيل في هذا التطبيق", style = MaterialTheme.typography.titleSmall)
        val options = listOf<Policy?>(null, Policy.ALLOWED, Policy.READ_ONLY, Policy.BLOCKED)
        Segmented(listOf("تلقائي", "مسموح", "قراءة فقط", "محجوب"), options.indexOf(explicit)) { i ->
            explicit = options[i]
            s.setPolicy(pkg, options[i])
            onChanged()
        }
        Text(
            when (effective) {
                Policy.ALLOWED -> "مسموح: ينفّذ المهام، ويطلب موافقتك قبل الأفعال الحساسة."
                Policy.READ_ONLY -> "قراءة فقط: يتنقّل ويبحث ويحلّل، ويُرفض تلقائياً أي ضغط على شراء أو بيع أو تحويل أو تأكيد."
                Policy.BLOCKED -> "محجوب: لا يفتحه ولا يلمسه، ويخرج منه فوراً إن ظهر."
            } + if (explicit == null) " (محدَّدة تلقائياً من اسم التطبيق)" else "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- learn more, for free
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (!serviceOn) Toast.makeText(ctx, "فعّل خدمة إمكانية الوصول أولاً", Toast.LENGTH_SHORT).show() else onExplore()
                },
                enabled = effective != Policy.BLOCKED,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Explore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("استكشاف آمن", maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    Hub.maps.clear(pkg)
                    Hub.notes.clear(pkg)
                    onChanged()
                },
                enabled = r.screens > 0 || r.notes > 0,
                modifier = Modifier.weight(1f),
            ) { Text("مسح ما تعلّمه", maxLines = 1) }
        }
        Text(
            "الاستكشاف الآمن يفتح التطبيق ويزور تبويباته السفلية فقط ليحفظ صفحاته، دون كتابة أو إرسال ودون أي استهلاك.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (screens.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("الصفحات التي تعلّمها على هاتفك", style = MaterialTheme.typography.titleSmall)
            screens.take(12).forEach { sc ->
                Column {
                    Text("${sc.name}  ·  ${sc.visits} زيارة", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    val els = sc.stableElements(10)
                    if (els.isNotEmpty()) Text(els.joinToString("، "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (skills.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("مهارات هذا التطبيق", style = MaterialTheme.typography.titleSmall)
            skills.forEach { sk ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text(sk.pattern, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (notes.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("ملاحظات تعلّمها", style = MaterialTheme.typography.titleSmall)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                notes.forEach { n -> Text("• $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun SkillItem(sk: Skill, onRun: () -> Unit) {
    val appLabel = remember(sk.pkg) { if (sk.pkg.isBlank()) "" else Hub.apps.byPackage(sk.pkg)?.label.orEmpty() }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sk.pkg.isNotBlank()) {
                AppIcon(sk.pkg, 34.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(sk.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (appLabel.isNotBlank()) Text(appLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sk.trusted) StatusChip("مجانية", Brand.Green) else StatusChip("تحت التجربة", Brand.Amber)
        }
        Text(sk.pattern, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(
            "${sk.steps.size} خطوة · نجحت ${sk.successes} · تعثّرت ${sk.fails}" + if (sk.readout) " · تقرأ معلومات" else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRun) { Text(if (sk.params.isEmpty()) "تشغيل" else "تعبئة وتشغيل") }
            TextButton(onClick = { Hub.skills.remove(sk.id) }) { Text("حذف") }
        }
    }
}
