package com.wakeel.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CandlestickChart
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeel.agent.Hub
import com.wakeel.agent.trade.ClosedTrade
import com.wakeel.agent.trade.Fmt
import com.wakeel.agent.trade.JournalEntry
import com.wakeel.agent.trade.Paper
import com.wakeel.agent.trade.Position
import com.wakeel.agent.trade.Side
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val tradeTime = SimpleDateFormat("d/M HH:mm", Locale.US)
private val clock = SimpleDateFormat("HH:mm", Locale.US)

private fun actionWord(a: String) = when (a) {
    "buy" -> "شراء"
    "sell" -> "بيع"
    "close" -> "إغلاق"
    else -> "انتظار"
}

/** The trading mode: a paper account the agent trades by itself, and everything the owner needs to judge it. */
@Composable
fun TradeScreen(onDraft: (String) -> Unit) {
    val st by Hub.trader.state.collectAsStateWithLifecycle()
    val s = Hub.settings
    var auto by remember { mutableStateOf(s.tradeAuto) }
    var confirmReset by remember { mutableStateOf(false) }
    val good = Brand.Green
    val bad = Brand.Ruby
    fun tone(v: Double): Color = if (v >= 0) good else bad

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("التداول", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                StatusChip("حساب تجريبي — لا مال حقيقي", Brand.Gold)
            }
        }

        // ---------------- The account ----------------
        item {
            Panel {
                PanelHeader("الحساب التجريبي", Icons.Rounded.CandlestickChart)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("الرصيد USDT", Fmt.money(st.equity), Modifier.weight(1f))
                    StatTile("اليوم", Fmt.signed(st.dayPnl), Modifier.weight(1f), accent = tone(st.dayPnl))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val total = st.equity - st.startBalance
                    StatTile("منذ البداية", Fmt.signed(total), Modifier.weight(1f), accent = tone(total))
                    StatTile("صفقات رابحة", if (st.stats.trades == 0) "—" else "${st.stats.wins} من ${st.stats.trades}", Modifier.weight(1f))
                }
                SwitchRow(
                    "التداول التلقائي",
                    if (auto && st.nextRun > 0) "دورة كل ${s.tradeEveryMin} دقيقة · القادمة ${clock.format(Date(st.nextRun))}" else "يحلل ويقرر وينفّذ وحده كل ${s.tradeEveryMin} دقيقة",
                    auto,
                ) {
                    auto = it
                    Hub.trader.setAuto(it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { Hub.trader.runNow() }, enabled = !st.running) { Text(if (st.running) "يحلل السوق…" else "دورة الآن") }
                    if (st.running) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (st.lastRun > 0) Text("آخر دورة ${tradeTime.format(Date(st.lastRun))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.lastNote.isNotBlank()) Text(st.lastNote, style = MaterialTheme.typography.bodyMedium)
                if (st.outlook.isNotBlank()) Text("نظرة الوكيل: ${st.outlook}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------------- Open positions ----------------
        item {
            Panel {
                PanelHeader("الصفقات المفتوحة (${st.positions.size})", Icons.Rounded.CandlestickChart, tint = Brand.Sapphire)
                if (st.positions.isEmpty()) Text("لا صفقات مفتوحة الآن.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                st.positions.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider()
                    PositionRow(p, st.prices[p.symbol] ?: p.entry, good, bad, onDraft)
                }
            }
        }

        // ---------------- The journal ----------------
        item {
            Panel {
                PanelHeader("قرارات الوكيل", Icons.Rounded.History, tint = Brand.Gold)
                if (st.journal.isEmpty()) {
                    Text("لم يقرر الوكيل شيئاً بعد. اضغط «دورة الآن» أو شغّل التداول التلقائي.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                st.journal.take(30).forEachIndexed { i, j ->
                    if (i > 0) HorizontalDivider()
                    JournalRow(j, good)
                }
            }
        }

        // ---------------- Closed trades ----------------
        if (st.closed.isNotEmpty()) {
            item {
                Panel {
                    PanelHeader("الصفقات المغلقة (${st.stats.trades})", Icons.Rounded.History)
                    Text(
                        "المجموع ${Fmt.signed(st.stats.pnl)} USDT · أفضل صفقة ${Fmt.signed(st.stats.best)} · أسوأ صفقة ${Fmt.signed(st.stats.worst)}" +
                            (st.stats.profitFactor?.let { " · معامل الربح ${String.format(Locale.US, "%.2f", it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    st.closed.take(20).forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider()
                        ClosedRow(t, good, bad)
                    }
                }
            }
        }

        // ---------------- Limits ----------------
        item {
            Panel {
                PanelHeader("الضوابط", Icons.Rounded.Shield, tint = Brand.Ruby)
                Text(
                    "هذه الحدود يطبّقها التطبيق بعد قرار النموذج، فلا يستطيع تجاوزها: الحجم يُحسب من وقف الخسارة، والوقف إلزامي.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("أقصى حجم للصفقة USDT", s.tradeMaxPosition.toInt().toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.tradeMaxPosition = v } }
                    Num("مخاطرة الصفقة %", s.tradeRiskPct.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.tradeRiskPct = v } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("أبعد وقف خسارة %", s.tradeMaxSlPct.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.tradeMaxSlPct = v } }
                    Num("حد خسارة اليوم %", s.tradeDailyLossPct.toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.tradeDailyLossPct = v } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("صفقات في اليوم", s.tradeMaxPerDay.toString(), Modifier.weight(1f), decimal = false) { it.toIntOrNull()?.let { v -> s.tradeMaxPerDay = v } }
                    Num("أدنى ثقة للقرار", s.tradeMinConfidence.toString(), Modifier.weight(1f), decimal = false) { it.toIntOrNull()?.let { v -> s.tradeMinConfidence = v } }
                }
                var short by remember { mutableStateOf(s.tradeShort) }
                SwitchRow("البيع على المكشوف", "يفتح صفقات بيع حين يتوقع هبوطاً، كما في العقود الآجلة", short) { short = it; s.tradeShort = it }
            }
        }

        // ---------------- What it trades and how often ----------------
        item {
            Panel {
                PanelHeader("ما يتداوله", Icons.Rounded.Tune)
                var symbols by remember { mutableStateOf(s.tradeSymbols) }
                OutlinedTextField(
                    value = symbols, onValueChange = { symbols = it; s.tradeSymbols = it },
                    label = { Text("الرموز (مثل BTCUSDT, ETHUSDT)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                val frames = listOf("15m", "1h", "4h")
                var frame by remember { mutableIntStateOf(frames.indexOf(s.tradeInterval).coerceAtLeast(0)) }
                Text("حجم الشمعة التي يقرر عليها", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Segmented(listOf("15 دقيقة", "ساعة", "4 ساعات"), frame, Modifier.fillMaxWidth()) { frame = it; s.tradeInterval = frames[it] }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Num("دورة كل (دقيقة)", s.tradeEveryMin.toString(), Modifier.weight(1f), decimal = false) {
                        it.toIntOrNull()?.let { v -> s.tradeEveryMin = v; if (s.tradeAuto) Hub.trader.arm() }
                    }
                    Num("رصيد البداية USDT", s.tradeStart.toInt().toString(), Modifier.weight(1f)) { it.toFloatOrNull()?.let { v -> s.tradeStart = v } }
                }
                var news by remember { mutableStateOf(s.tradeNews) }
                SwitchRow("عناوين الأخبار", "تُعرض على النموذج خلفيةً فقط، لا سبباً للقرار", news) { news = it; s.tradeNews = it }
                var strong by remember { mutableStateOf(s.tradeStrong) }
                SwitchRow("القرار بالنموذج الأقوى", "أدق وأغلى — طلب واحد في كل دورة في الحالين", strong) { strong = it; s.tradeStrong = it }
                OutlinedButton(onClick = { confirmReset = true }) { Text("تصفير الحساب التجريبي") }
                Text(
                    "الأسعار من بيانات السوق العامة (بلا حساب ولا مفتاح). لا يملك هذا الوضع أي طريق إلى حساب تداول حقيقي. " +
                        "نتائج الحساب التجريبي لا تضمن شيئاً في السوق الحقيقي، وهذه ليست نصيحة مالية. " +
                        "حين ينام الهاتف نوماً عميقاً قد تتأخر الدورة التلقائية حتى يستيقظ.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("تصفير الحساب التجريبي؟") },
            text = { Text("تُمحى الصفقات والسجل ويعود الرصيد إلى ${Fmt.money(s.tradeStart.toDouble())} USDT.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; Hub.trader.reset() }) { Text("صفّر") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun PositionRow(p: Position, price: Double, good: Color, bad: Color, onDraft: (String) -> Unit) {
    val pnl = Paper.unrealized(p, price)
    val long = p.side == Side.LONG
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            StatusChip(if (long) "شراء" else "بيع", if (long) good else bad)
            Spacer(Modifier.weight(1f))
            Text(Fmt.signed(pnl) + " USDT", style = MaterialTheme.typography.titleMedium, color = if (pnl >= 0) good else bad)
        }
        Text(
            "الكمية ${Fmt.qty(p.qty)} · الدخول ${Fmt.price(p.entry)} · الآن ${Fmt.price(price)}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "وقف الخسارة ${Fmt.price(p.sl)} · الهدف ${Fmt.price(p.tp)} · فُتحت ${tradeTime.format(Date(p.openedAt))}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (p.reason.isNotBlank()) Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Hub.trader.close(p.symbol) }) { Text("إغلاق الآن") }
            TextButton(onClick = {
                onDraft(
                    "افتح موقع التداول التجريبي في المتصفح ونفّذ هناك أمر ${if (long) "شراء" else "بيع"} ${p.symbol} بالكمية ${Fmt.qty(p.qty)} " +
                        "مع وقف خسارة ${Fmt.price(p.sl)} وهدف ${Fmt.price(p.tp)}، وتأكد قبل التنفيذ أن الصفحة حساب تجريبي",
                )
            }) { Text("أرسلها إلى موقع تجريبي") }
        }
    }
}

@Composable
private fun JournalRow(j: JournalEntry, good: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(j.symbol, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            StatusChip(actionWord(j.action) + if (j.action == "buy" || j.action == "sell") " · ثقة ${j.confidence}" else "", if (j.changed) good else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(tradeTime.format(Date(j.time)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (j.reason.isNotBlank()) Text(j.reason, style = MaterialTheme.typography.bodySmall)
        Text(j.outcome, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ClosedRow(t: ClosedTrade, good: Color, bad: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${t.symbol} · ${if (t.side == Side.LONG) "شراء" else "بيع"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                "${Fmt.price(t.entry)} ← ${Fmt.price(t.exit)} · " + when (t.why) {
                    "sl" -> "وقف الخسارة"
                    "tp" -> "الهدف"
                    "manual" -> "بيدك"
                    else -> "قرار الوكيل"
                } + " · ${tradeTime.format(Date(t.closedAt))}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(Fmt.signed(t.pnl), style = MaterialTheme.typography.titleMedium, color = if (t.pnl >= 0) good else bad)
    }
}

@Composable
private fun Num(label: String, initial: String, modifier: Modifier, decimal: Boolean = true, onValue: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; onValue(it) },
        label = { Text(label, maxLines = 1) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
    )
}
