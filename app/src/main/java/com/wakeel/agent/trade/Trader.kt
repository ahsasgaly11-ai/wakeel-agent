package com.wakeel.agent.trade

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.wakeel.agent.Hub
import com.wakeel.agent.core.Settings
import com.wakeel.agent.llm.Http
import com.wakeel.agent.llm.LlmClient
import com.wakeel.agent.llm.Prompt
import com.wakeel.agent.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate

/** Where prices and headlines come from. The on-device tests put a scripted market here. */
interface MarketSource {
    suspend fun candles(symbol: String, interval: String, limit: Int): List<Candle>
    suspend fun headlines(query: String): List<String>

    companion object {
        @Volatile
        var override: MarketSource? = null
    }
}

/** Public market data: no account, no key, nothing that can place an order. */
object LiveMarket : MarketSource {
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", "Wakeel").build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    override suspend fun candles(symbol: String, interval: String, limit: Int): List<Candle> {
        val mexc = if (interval == "1h") "60m" else interval
        val sources = listOf(
            "https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit",
            "https://api.mexc.com/api/v3/klines?symbol=$symbol&interval=$mexc&limit=$limit",
        )
        var last: Exception? = null
        for (u in sources) {
            try {
                val c = MarketText.parseKlines(get(u))
                if (c.size >= 30) return c
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: java.io.IOException("no market data for $symbol")
    }

    override suspend fun headlines(query: String): List<String> =
        MarketText.parseRss(get("https://news.google.com/rss/search?q=" + URLEncoder.encode(query, "UTF-8") + "&hl=en-US&gl=US&ceid=US:en"))
}

data class JournalEntry(val time: Long, val symbol: String, val action: String, val confidence: Int, val reason: String, val outcome: String, val changed: Boolean)

data class TradeState(
    val cash: Double = 0.0,
    val equity: Double = 0.0,
    val dayPnl: Double = 0.0,
    val positions: List<Position> = emptyList(),
    val prices: Map<String, Double> = emptyMap(),
    val closed: List<ClosedTrade> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val stats: Stats = Stats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, null),
    val running: Boolean = false,
    val lastRun: Long = 0,
    val nextRun: Long = 0,
    val lastNote: String = "",
    val outlook: String = "",
    val tradesToday: Int = 0,
    val startBalance: Double = 0.0,
)

/**
 * The trading mode: every cycle reads the market, lets the model decide, and carries the decision out in a
 * paper account kept on this phone. It has no way to reach an exchange account — by construction, not by a setting.
 */
class Trader(private val ctx: Context, private val settings: Settings) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = File(ctx.filesDir, "trade.json")
    private val lock = Mutex()
    private var acc = Account(settings.tradeStart.toDouble())
    private val journal = ArrayList<JournalEntry>()
    private val prices = HashMap<String, Double>()
    private var lastRun = 0L
    private var nextRun = 0L
    private var lastNote = ""
    private var outlook = ""
    private var timer: Job? = null

    private val _state = MutableStateFlow(TradeState())
    val state: StateFlow<TradeState> = _state.asStateFlow()

    init {
        load()
        publish(false)
    }

    fun limits() = Limits(
        startBalance = settings.tradeStart.toDouble(), maxPositionUsd = settings.tradeMaxPosition.toDouble(),
        riskPct = settings.tradeRiskPct.toDouble(), maxSlPct = settings.tradeMaxSlPct.toDouble(),
        maxTradesPerDay = settings.tradeMaxPerDay, dailyLossPct = settings.tradeDailyLossPct.toDouble(),
        minConfidence = settings.tradeMinConfidence, allowShort = settings.tradeShort,
    )

    fun symbols(): List<String> = MarketText.symbols(settings.tradeSymbols).ifEmpty { listOf("BTCUSDT") }

    private fun publish(running: Boolean) {
        val eq = Paper.equity(acc, prices)
        _state.value = TradeState(
            acc.cash, eq, eq - acc.dayStartEquity, acc.positions.toList(), HashMap(prices), acc.closed.take(40), journal.take(60),
            Paper.stats(acc), running, lastRun, if (settings.tradeAuto) nextRun else 0, lastNote, outlook, acc.tradesToday, settings.tradeStart.toDouble(),
        )
    }

    private fun note(now: Long, symbol: String, action: String, confidence: Int, reason: String, outcome: Applied) {
        journal.add(0, JournalEntry(now, symbol, action, confidence, reason.take(300), outcome.text, outcome.changed))
        while (journal.size > 200) journal.removeAt(journal.size - 1)
    }

    /** One full cycle: stops and targets, analysis, decision, execution. Returns a line for the owner. */
    suspend fun cycle(): String = lock.withLock {
        publish(true)
        val line = try {
            runCycle()
        } catch (e: CancellationException) {
            publish(false)
            throw e
        } catch (e: Exception) {
            "تعذّرت الدورة: " + (e.message ?: e.javaClass.simpleName)
        }
        lastRun = System.currentTimeMillis()
        lastNote = line
        save()
        publish(false)
        line
    }

    private suspend fun runCycle(): String {
        val now = System.currentTimeMillis()
        val lim = limits()
        val interval = settings.tradeInterval
        val higher = Analysis.higherOf(interval)
        val src = MarketSource.override ?: LiveMarket
        val briefs = ArrayList<Brief>()
        val candles = HashMap<String, List<Candle>>()
        // Positions on symbols the owner has since removed from the list are still looked after
        val wanted = (symbols() + acc.positions.map { it.symbol }).distinct()
        for (s in wanted) {
            val c = try {
                src.candles(s, interval, 150)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (c.size < 30) continue
            val h = try {
                src.candles(s, higher, 80)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            candles[s] = c
            prices[s] = c.last().close
            Analysis.brief(s, interval, c, higher, h)?.let { briefs += it }
        }
        if (briefs.isEmpty()) return "تعذّر جلب أسعار السوق. تحقق من الإنترنت ومن رموز العملات."

        // Stops and targets the market reached since the last look
        var swept = 0
        for (p in acc.positions.toList()) {
            val c = candles[p.symbol] ?: continue
            Paper.sweep(acc, lim, p, c, now)?.let { t ->
                swept++
                note(now, t.symbol, "close", 100, if (t.why == "sl") "بلغ السعر وقف الخسارة" else "بلغ السعر الهدف", Applied(Paper.describe(t), true))
            }
        }
        Paper.rollDay(acc, LocalDate.now().toString(), Paper.equity(acc, prices))

        if (LlmClient.override == null && (settings.apiKey.isBlank() || settings.fastModel.isBlank())) {
            return "أضف مفتاح API واسم النموذج من الإعدادات ليقرر الوكيل. فُحصت أوامر الوقف والهدف فقط."
        }
        if (settings.todaySpend() >= settings.dailyBudget) return "بلغتَ حد الإنفاق اليومي على النموذج — لا قرارات جديدة اليوم. فُحصت أوامر الوقف والهدف."

        val news = if (!settings.tradeNews) emptyList() else try {
            src.headlines("crypto market " + briefs.joinToString(" ") { it.symbol.removeSuffix("USDT") })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val model = if (settings.tradeStrong && settings.strongModel.isNotBlank()) settings.strongModel else settings.fastModel
        val user = TradeText.user(Prompt.now(), acc, lim, prices, briefs, news, now)
        val res = LlmClient.create(settings).callTool(model, TradeText.SYSTEM, user, null, tool(), maxTokens = 900)
        settings.addSpend(settings.cost(model, res.usage))
        val o = res.input ?: return "لم يُعطِ النموذج قراراً هذه الدورة."
        outlook = o.optString("outlook").take(300)
        val arr = o.optJSONArray("decisions") ?: JSONArray()
        var acted = 0
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            val symbol = d.optString("symbol").uppercase()
            val b = briefs.firstOrNull { it.symbol == symbol } ?: continue
            if (!seen.add(symbol)) continue
            fun num(k: String) = if (d.has(k) && !d.isNull(k)) d.optDouble(k).takeIf { !it.isNaN() && it > 0 } else null
            val call = Call(symbol, d.optString("action", "hold"), num("sl"), num("tp"), d.optInt("confidence", 0).coerceIn(0, 100), d.optString("reason"))
            val out = Paper.apply(acc, lim, call, b.price, b.atr, now, Paper.equity(acc, prices))
            if (out.changed) acted++
            note(now, symbol, call.action.lowercase(), call.confidence, call.reason, out)
        }
        val eq = Paper.equity(acc, prices)
        return buildString {
            append(if (acted + swept == 0) "لا صفقات هذه الدورة" else "${acted + swept} إجراء هذه الدورة")
            append(" · الرصيد ").append(Fmt.money(eq)).append(" USDT")
            append(" · اليوم ").append(Fmt.signed(eq - acc.dayStartEquity))
        }
    }

    fun runNow() {
        scope.launch { runCatching { cycle() } }
    }

    /** The owner closes a position by hand, at the last known price. */
    fun close(symbol: String) {
        scope.launch {
            lock.withLock {
                val p = acc.positions.firstOrNull { it.symbol == symbol } ?: return@withLock
                val now = System.currentTimeMillis()
                val price = try {
                    (MarketSource.override ?: LiveMarket).candles(symbol, "15m", 30).last().close
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    prices[symbol] ?: p.entry
                }
                prices[symbol] = price
                val t = Paper.close(acc, limits(), p, price, now, "manual")
                note(now, symbol, "close", 100, "أغلقتَها بيدك", Applied(Paper.describe(t), true))
                save()
                publish(false)
            }
        }
    }

    /** A fresh paper account with the starting balance from the settings. */
    fun reset() {
        scope.launch { resetNow() }
    }

    suspend fun resetNow() = lock.withLock {
        acc = Account(settings.tradeStart.toDouble())
        journal.clear()
        prices.clear()
        lastNote = ""
        outlook = ""
        lastRun = 0
        save()
        publish(false)
    }

    // ------------------------------------------------------------------ the automatic cycle

    fun setAuto(on: Boolean) {
        settings.tradeAuto = on
        if (!on) {
            arm()
            return
        }
        // Switched on: the first cycle runs now, and the clock for the next one starts when it ends
        scope.launch {
            runCatching { cycle() }
            arm()
        }
    }

    /** Sets the alarm (and a timer while the app is awake) for the next cycle. Called at start-up too. */
    @Synchronized
    fun arm() {
        timer?.cancel()
        timer = null
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, TradeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (!settings.tradeAuto) {
            runCatching { am?.cancel(pi) }
            publish(_state.value.running)
            return
        }
        val now = System.currentTimeMillis()
        val every = settings.tradeEveryMin * 60_000L
        nextRun = (if (lastRun > 0) lastRun + every else now + every).coerceAtLeast(now + 15_000)
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun, pi)
        }.onFailure { runCatching { am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextRun, pi) } }
        val wait = nextRun - now
        timer = scope.launch {
            delay(wait)
            onAlarm()
        }
        publish(_state.value.running)
    }

    @Volatile
    private var pending = false

    @Synchronized
    fun onAlarm() {
        if (!settings.tradeAuto) return
        // The alarm and the timer both come here: only the first one starts the cycle
        if (pending || System.currentTimeMillis() < nextRun - 20_000 || lock.isLocked) return
        pending = true
        // The phone may be asleep: it is kept awake for the minute a cycle takes
        val wake = runCatching {
            ctx.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakeel:trade").apply { acquire(120_000) }
        }.getOrNull()
        scope.launch {
            try {
                cycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // cycle() reports its own failures; nothing more to do here
            } finally {
                runCatching { if (wake?.isHeld == true) wake.release() }
                pending = false
                arm()
            }
        }
    }

    // ------------------------------------------------------------------ storage

    private fun tool() = ToolSpec(
        "trade", "Give this cycle's decision for every symbol.",
        JSONObject(
            """{"type":"object","properties":{"outlook":{"type":"string","description":"One Arabic sentence on the market as a whole."},"decisions":{"type":"array","items":{"type":"object","properties":{"symbol":{"type":"string"},"action":{"type":"string","enum":["buy","sell","close","hold"]},"sl":{"type":"number","description":"Stop-loss price (buy/sell)."},"tp":{"type":"number","description":"Take-profit price (buy/sell)."},"confidence":{"type":"integer","description":"0-100"},"reason":{"type":"string","description":"One or two short Arabic sentences."}},"required":["symbol","action","confidence","reason"]}}},"required":["outlook","decisions"]}""",
        ),
    )

    private fun save() {
        runCatching {
            val o = JSONObject()
                .put("cash", acc.cash).put("day", acc.dayKey).put("dayStart", acc.dayStartEquity).put("today", acc.tradesToday)
                .put("lastRun", lastRun).put("note", lastNote).put("outlook", outlook)
            o.put("positions", JSONArray().also { a ->
                acc.positions.forEach { p ->
                    a.put(
                        JSONObject().put("s", p.symbol).put("d", p.side.name).put("q", p.qty).put("e", p.entry).put("sl", p.sl).put("tp", p.tp)
                            .put("t", p.openedAt).put("r", p.reason).put("f", p.fee).put("c", p.checkedAt),
                    )
                }
            })
            o.put("closed", JSONArray().also { a ->
                acc.closed.forEach { t ->
                    a.put(
                        JSONObject().put("s", t.symbol).put("d", t.side.name).put("q", t.qty).put("e", t.entry).put("x", t.exit)
                            .put("t", t.openedAt).put("c", t.closedAt).put("p", t.pnl).put("w", t.why).put("r", t.reason),
                    )
                }
            })
            o.put("journal", JSONArray().also { a ->
                journal.forEach { j ->
                    a.put(JSONObject().put("t", j.time).put("s", j.symbol).put("a", j.action).put("c", j.confidence).put("r", j.reason).put("o", j.outcome).put("x", j.changed))
                }
            })
            o.put("prices", JSONObject().also { m -> prices.forEach { (k, v) -> m.put(k, v) } })
            file.writeText(o.toString())
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            fun side(s: String) = if (s == "SHORT") Side.SHORT else Side.LONG
            val a = Account(o.optDouble("cash", settings.tradeStart.toDouble()))
            a.dayKey = o.optString("day")
            a.dayStartEquity = o.optDouble("dayStart", a.cash)
            a.tradesToday = o.optInt("today")
            o.optJSONArray("positions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    a.positions += Position(
                        p.getString("s"), side(p.optString("d")), p.getDouble("q"), p.getDouble("e"), p.getDouble("sl"), p.getDouble("tp"),
                        p.getLong("t"), p.optString("r"), p.optDouble("f", 0.0), p.optLong("c", p.getLong("t")),
                    )
                }
            }
            o.optJSONArray("closed")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    a.closed += ClosedTrade(
                        t.getString("s"), side(t.optString("d")), t.getDouble("q"), t.getDouble("e"), t.getDouble("x"),
                        t.getLong("t"), t.getLong("c"), t.getDouble("p"), t.optString("w"), t.optString("r"),
                    )
                }
            }
            journal.clear()
            o.optJSONArray("journal")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    journal += JournalEntry(j.getLong("t"), j.getString("s"), j.optString("a"), j.optInt("c"), j.optString("r"), j.optString("o"), j.optBoolean("x"))
                }
            }
            prices.clear()
            o.optJSONObject("prices")?.let { m -> m.keys().forEach { k -> prices[k] = m.optDouble(k) } }
            lastRun = o.optLong("lastRun")
            lastNote = o.optString("note")
            outlook = o.optString("outlook")
            acc = a
        }
    }
}

class TradeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Hub.trader.onAlarm() }
    }
}
