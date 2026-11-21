package com.wakeel.agent

import com.wakeel.agent.agent.AppMap
import com.wakeel.agent.agent.AppMapStore
import com.wakeel.agent.agent.Cmd
import com.wakeel.agent.agent.Commands
import com.wakeel.agent.agent.Demo
import com.wakeel.agent.agent.Dial
import com.wakeel.agent.agent.KnowledgeBase
import com.wakeel.agent.agent.Learner
import com.wakeel.agent.agent.LocalRouter
import com.wakeel.agent.agent.MacroStep
import com.wakeel.agent.agent.MapEdge
import com.wakeel.agent.agent.MapScreen
import com.wakeel.agent.agent.Mastery
import com.wakeel.agent.agent.Safety
import com.wakeel.agent.agent.Schedule
import com.wakeel.agent.agent.SettingsCatalog
import com.wakeel.agent.agent.Templates
import com.wakeel.agent.core.Text
import com.wakeel.agent.core.Usage
import com.wakeel.agent.trade.Account
import com.wakeel.agent.trade.Analysis
import com.wakeel.agent.trade.Call
import com.wakeel.agent.trade.Candle
import com.wakeel.agent.trade.Indicators
import com.wakeel.agent.trade.Limits
import com.wakeel.agent.trade.MarketText
import com.wakeel.agent.trade.Paper
import com.wakeel.agent.trade.Side
import com.wakeel.agent.trade.TradeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Checks the parts that must be exact: Arabic command parsing, skill generalization, safety guards. */
class LogicTest {
    private fun url(task: String): String? = (Commands.parse(task) as? Cmd.Url)?.url

    @Test
    fun `a page reading is handed over whole and says when it was cut`() {
        val lines = (1..40).map { "result $it · " + "x".repeat(60) }
        val all = com.wakeel.agent.service.ScreenReader.joinPage(lines, 9000)
        assertTrue(all.startsWith("result 1 ·") && all.contains("result 40 ·") && !all.contains("the page continues"))
        val cut = com.wakeel.agent.service.ScreenReader.joinPage(lines, 600)
        assertTrue(cut.length <= 700 && cut.contains("result 1 ·") && !cut.contains("result 40 ·") && cut.endsWith("for the rest)"))
    }

    @Test
    fun `web requests go by link and the model is told the date`() {
        val link = "https://www.google.com/travel/flights?q=Flights+from+MCT+to+EBL+on+2026-12-16+through+2026-12-27"
        assertEquals(link, LocalRouter.encodeLoose(LocalRouter.normalizeUrl(link)!!))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \(\w+\) \d{2}:\d{2}""").matches(com.wakeel.agent.llm.Prompt.now()))
        val system = com.wakeel.agent.llm.Prompt.SYSTEM
        assertTrue(system.contains("`read`") && system.contains("travel/flights?q=") && system.contains("NOW"))
        assertEquals(com.wakeel.agent.core.ActionType.READ, com.wakeel.agent.core.ActionType.of("read"))
    }

    // ------------------------------------------------------------------ trading mode

    private fun candle(t: Long, o: Double, h: Double, l: Double, c: Double, v: Double = 10.0) = Candle(t, o, h, l, c, v)

    @Test
    fun `indicators are computed exactly`() {
        assertEquals(4.0, Indicators.ema(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 3)!!, 1e-9)
        assertNull(Indicators.ema(listOf(1.0, 2.0), 3))
        assertEquals(100.0, Indicators.rsi((1..30).map { it.toDouble() })!!, 1e-9)
        assertEquals(50.0, Indicators.rsi(List(30) { 7.0 })!!, 1e-9)
        // The textbook series for Wilder's RSI: 70.5 after fourteen changes
        val book = listOf(44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28)
        assertEquals(70.5, Indicators.rsi(book)!!, 0.2)
        assertNull(Indicators.rsi(book.take(14)))
        val flat = (0 until 30).map { candle(it * 3_600_000L, 100.0, 101.0, 99.0, 100.0) }
        assertEquals(2.0, Indicators.atr(flat)!!, 1e-9)
        val bb = Indicators.bollinger(List(20) { 5.0 })!!
        assertEquals(5.0, bb.first, 1e-9)
        assertEquals(5.0, bb.third, 1e-9)
        assertNull(Indicators.macdHist((1..30).map { it.toDouble() }))
        // A steady climb: the two averages rise in step, so the histogram settles at nothing
        assertEquals(0.0, Indicators.macdHist((1..200).map { it.toDouble() })!!, 1e-6)
    }

    @Test
    fun `market replies are read into candles and headlines`() {
        val json = """[[1499644800000,"0.016","0.80","0.015","0.0157","148976.1",1499648399999,"2434.19",308,"1756.8","28.4","0"],[1499040000000,"0.0163","0.8","0.0157","0.0158","100.5",1499043599999,"1.0"]]"""
        val c = MarketText.parseKlines(json)
        assertEquals(2, c.size)
        assertEquals(1499040000000L, c[0].time)
        assertEquals(0.0158, c[0].close, 1e-12)
        assertEquals(0.80, c[1].high, 1e-12)
        assertEquals(148976.1, c[1].volume, 1e-6)
        assertTrue(MarketText.parseKlines("""{"code":-1121,"msg":"Invalid symbol."}""").isEmpty())
        val rss = "<rss><channel><title>feed</title><item><title>Bitcoin climbs &amp; holds - Site</title><link>x</link></item><item><title><![CDATA[Ether slips]]></title></item></channel></rss>"
        assertEquals(listOf("Bitcoin climbs & holds - Site", "Ether slips"), MarketText.parseRss(rss))
        assertEquals(listOf("BTCUSDT", "ETHUSDT"), MarketText.symbols("btc/usdt، ETHUSDT xx btcusdt"))
    }

    @Test
    fun `the size of a trade follows from its stop and stays inside the limits`() {
        val lim = Limits()
        val a = Account(10_000.0)
        // Risk 1% = 100 USDT, stop 2 away → 50 units, but the position cap of 2000 USDT allows only 20
        val r = Paper.open(a, lim, "BTCUSDT", Side.LONG, 100.0, 98.0, 104.0, null, 1000L, "سبب", 10_000.0)
        assertTrue(r.text, r.changed)
        val p = a.positions.single()
        assertEquals(20.0, p.qty, 1e-9)
        assertEquals(98.0, p.sl, 1e-9)
        assertEquals(104.0, p.tp, 1e-9)
        assertEquals(9_999.0, a.cash, 1e-9)                     // the entry fee: 0.05% of 2000
        assertEquals(1, a.tradesToday)
        assertFalse("one position per symbol", Paper.open(a, lim, "BTCUSDT", Side.LONG, 100.0, 98.0, 104.0, null, 1000L, "", 10_000.0).changed)

        // A small account: now the risk rule is the tighter one
        val small = Account(1_000.0)
        Paper.open(small, lim, "ETHUSDT", Side.LONG, 100.0, 99.0, null, null, 0L, "", 1_000.0)
        assertEquals(10.0, small.positions.single().qty, 1e-9)   // 1% of 1000 = 10 USDT over a stop 1 away
        assertEquals(102.0, small.positions.single().tp, 1e-9)   // no target given: twice the stop distance
    }

    @Test
    fun `a stop is mandatory and never further than the limit`() {
        val lim = Limits()
        var a = Account(10_000.0)
        val far = Paper.open(a, lim, "BTCUSDT", Side.LONG, 100.0, 90.0, null, null, 0L, "", 10_000.0)
        assertEquals(97.0, a.positions.single().sl, 1e-9)
        assertTrue(far.text.contains("قُرّب"))
        a = Account(10_000.0)
        Paper.open(a, lim, "BTCUSDT", Side.LONG, 100.0, null, null, 1.0, 0L, "", 10_000.0)
        assertEquals(98.5, a.positions.single().sl, 1e-9)        // 1.5 x ATR
        assertEquals(103.0, a.positions.single().tp, 1e-9)
        a = Account(10_000.0)
        val wrong = Paper.open(a, lim, "BTCUSDT", Side.LONG, 100.0, 105.0, 95.0, null, 0L, "", 10_000.0)
        assertEquals("a stop on the wrong side is replaced", 97.0, a.positions.single().sl, 1e-9)
        assertEquals("a target on the wrong side is replaced", 106.0, a.positions.single().tp, 1e-9)
        assertTrue(wrong.text.contains("تلقائياً"))
        a = Account(10_000.0)
        Paper.open(a, lim, "BTCUSDT", Side.SHORT, 100.0, 102.0, 96.0, null, 0L, "", 10_000.0)
        assertEquals(Side.SHORT, a.positions.single().side)
        assertEquals(102.0, a.positions.single().sl, 1e-9)
        assertFalse(Paper.open(Account(10_000.0), lim.copy(allowShort = false), "BTCUSDT", Side.SHORT, 100.0, 102.0, 96.0, null, 0L, "", 10_000.0).changed)
    }

    @Test
    fun `profit and loss are exact, fees included`() {
        val lim = Limits()
        val a = Account(10_000.0)
        Paper.open(a, lim, "BTCUSDT", Side.LONG, 100.0, 98.0, 104.0, null, 0L, "", 10_000.0)
        assertEquals(10_039.0, Paper.equity(a, mapOf("BTCUSDT" to 102.0)), 1e-9)
        val t = Paper.close(a, lim, a.positions.single(), 104.0, 5L, "agent")
        assertEquals(77.96, t.pnl, 1e-9)                         // 80 gross − 1.00 entry fee − 1.04 exit fee
        assertEquals(10_077.96, a.cash, 1e-9)
        assertTrue(a.positions.isEmpty())

        val b = Account(10_000.0)
        Paper.open(b, lim, "BTCUSDT", Side.SHORT, 100.0, 102.0, 96.0, null, 0L, "", 10_000.0)
        val loss = Paper.close(b, lim, b.positions.single(), 102.0, 5L, "sl")
        assertEquals(-42.02, loss.pnl, 1e-9)                     // −40 gross − 1.00 − 1.02
        val s = Paper.stats(a)
        assertEquals(1, s.wins)
        assertEquals(100.0, s.winRate, 1e-9)
    }

    @Test
    fun `stops and targets are found in the candles since the trade was opened`() {
        val lim = Limits()
        val h = 3_600_000L
        val t0 = 100 * h
        fun fresh(): Account = Account(10_000.0).also { Paper.open(it, lim, "BTCUSDT", Side.LONG, 100.0, 98.0, 104.0, null, t0 + 60_000, "", 10_000.0) }

        // The candle the trade was opened in dipped to 90 before the entry: that is not a stop-out
        var a = fresh()
        val quiet = listOf(candle(t0, 95.0, 101.0, 90.0, 100.0), candle(t0 + h, 100.0, 103.0, 99.0, 102.0), candle(t0 + 2 * h, 102.0, 103.5, 101.0, 103.0))
        assertNull(Paper.sweep(a, lim, a.positions.single(), quiet, t0 + 2 * h))
        assertEquals("finished candles are not looked at twice", t0 + h, a.positions.single().checkedAt)

        a = fresh()
        val up = quiet + candle(t0 + 3 * h, 103.0, 104.5, 102.5, 104.2)
        val won = Paper.sweep(a, lim, a.positions.single(), up, t0 + 3 * h)!!
        assertEquals("tp", won.why)
        assertEquals(104.0, won.exit, 1e-9)

        // One candle spans both: the stop is assumed — the worse case
        a = fresh()
        val both = listOf(candle(t0, 95.0, 101.0, 90.0, 100.0), candle(t0 + h, 100.0, 105.0, 97.0, 101.0))
        val lost = Paper.sweep(a, lim, a.positions.single(), both, t0 + h)!!
        assertEquals("sl", lost.why)
        assertEquals(98.0, lost.exit, 1e-9)
        assertTrue(lost.pnl < 0)

        // No later candle yet, but the price now is under the stop
        a = fresh()
        assertEquals("sl", Paper.sweep(a, lim, a.positions.single(), listOf(candle(t0, 100.0, 101.0, 96.0, 97.0)), t0)!!.why)
    }

    @Test
    fun `the daily limits stop new trades and nothing else`() {
        val lim = Limits(maxTradesPerDay = 2)
        val a = Account(10_000.0)
        Paper.rollDay(a, "2026-11-20", 10_000.0)
        assertTrue(Paper.open(a, lim, "AAAUSDT", Side.LONG, 100.0, 98.0, null, null, 0L, "", 10_000.0).changed)
        assertTrue(Paper.open(a, lim, "BBBUSDT", Side.LONG, 100.0, 98.0, null, null, 0L, "", 10_000.0).changed)
        val third = Paper.open(a, lim, "CCCUSDT", Side.LONG, 100.0, 98.0, null, null, 0L, "", 10_000.0)
        assertFalse(third.changed)
        assertTrue(third.text.contains("حد الصفقات"))
        // Closing is always allowed
        assertTrue(Paper.apply(a, lim, Call("AAAUSDT", "close", null, null, 0, ""), 101.0, null, 1L, 10_000.0).changed)
        // A new day starts clean
        Paper.rollDay(a, "2026-11-21", 10_000.0)
        assertEquals(0, a.tradesToday)

        val b = Account(10_000.0)
        Paper.rollDay(b, "2026-11-20", 10_000.0)
        val blocked = Paper.open(b, Limits(), "AAAUSDT", Side.LONG, 100.0, 98.0, null, null, 0L, "", 9_600.0)
        assertFalse("after losing 4% of the day's opening balance no trade is opened", blocked.changed)
        assertTrue(blocked.text.contains("خسارة اليوم"))
    }

    @Test
    fun `the model's decisions are carried out inside the rules`() {
        val lim = Limits()
        val a = Account(10_000.0)
        assertFalse(Paper.apply(a, lim, Call("BTCUSDT", "buy", 98.0, 104.0, 40, "ضعيف"), 100.0, null, 0L, 10_000.0).changed)
        assertFalse(Paper.apply(a, lim, Call("BTCUSDT", "hold", null, null, 90, ""), 100.0, null, 0L, 10_000.0).changed)
        assertFalse(Paper.apply(a, lim, Call("BTCUSDT", "close", null, null, 90, ""), 100.0, null, 0L, 10_000.0).changed)
        assertTrue(Paper.apply(a, lim, Call("BTCUSDT", "BUY", 98.0, 104.0, 80, "اتجاه صاعد"), 100.0, null, 0L, 10_000.0).changed)
        assertFalse("buying again leaves the position alone", Paper.apply(a, lim, Call("BTCUSDT", "buy", 98.0, 104.0, 80, ""), 101.0, null, 0L, 10_000.0).changed)
        // "sell" while long closes the long; it does not flip into a short in the same breath
        assertTrue(Paper.apply(a, lim, Call("BTCUSDT", "sell", 103.0, 97.0, 80, ""), 101.0, null, 0L, 10_000.0).changed)
        assertTrue(a.positions.isEmpty())
        assertEquals("agent", a.closed.first().why)
        assertFalse(Paper.apply(a, lim, Call("BTCUSDT", "dance", null, null, 99, ""), 100.0, null, 0L, 10_000.0).changed)
    }

    @Test
    fun `the model is shown numbers computed on the phone`() {
        val h = 3_600_000L
        val up = (0 until 150).map { i -> val c = 100.0 * (1 + 0.001 * i); candle(i * h, c * 0.999, c * 1.002, c * 0.998, c, 50.0) }
        val b = Analysis.brief("BTCUSDT", "1h", up, "4h", up.takeLast(80))!!
        assertEquals("up", b.higherTrend)
        assertTrue(b.rsi!! > 70 && b.ema20!! > b.ema50!! && b.price > b.ema20!!)
        assertEquals(2.13, b.change24h!!, 0.05)
        assertNull(Analysis.brief("BTCUSDT", "1h", up.take(20), "4h", up))
        val a = Account(10_000.0)
        Paper.rollDay(a, "d", 10_000.0)
        Paper.open(a, Limits(), "BTCUSDT", Side.LONG, b.price, null, null, b.atr, 0L, "اتجاه صاعد", 10_000.0)
        val text = TradeText.user("2026-11-20 (Friday) 17:40", a, Limits(), mapOf("BTCUSDT" to b.price), listOf(b), listOf("Bitcoin climbs"), 60_000L)
        for (part in listOf("NOW: 2026-11-20", "ACCOUNT: equity", "OPEN POSITIONS:", "BTCUSDT LONG", "MARKET:", "RSI14", "EMA20", "ATR14", "4h trend: up", "HEADLINES", "Bitcoin climbs", "trades today 1/6")) {
            assertTrue("missing «$part» in:\n$text", text.contains(part))
        }
        assertTrue(TradeText.SYSTEM.contains("PAPER") && TradeText.SYSTEM.contains("hold"))
    }

    @Test
    fun normalization() {
        assertEquals("ارسل رساله الي احمد", Text.norm("أَرسِلْ رسالةً إلى أحمد"))
        assertEquals("12", Text.norm("١٢"))
    }

    @Test
    fun textIgnoresInvisibleMarks() {
        // Android writes "Wi‑Fi" with a non-breaking hyphen, and Arabic screens wrap mixed text in direction marks
        assertEquals("wi-fi", Text.norm("Wi\u2011Fi"))
        assertEquals("الاعدادات", Text.norm("\u200Fالإعدادات\u200E"))
    }

    @Test
    fun demonstrationBecomesAPattern() {
        val steps = listOf(
            MacroStep("open_app", text = "com.whatsapp", label = "WhatsApp"),
            MacroStep("tap", "LinearLayout||أحمد", label = "أحمد"),
            MacroStep("type", "EditText|entry|", text = "مرحبا"),
            MacroStep("tap", "ImageButton|send|إرسال", label = "إرسال", confirm = true),
        )
        // The owner wrote the name without the hamza; the row in the app has it
        val task = "أرسل واتساب إلى احمد: مرحبا"
        val learned = Demo.generalize(task, steps, listOf("أحمد"))
        assertEquals("أرسل واتساب إلى {p1}: {p2}", learned.pattern)
        assertEquals("احمد", learned.params["p1"])
        assertEquals("مرحبا", learned.params["p2"])
        val skill = Learner.newSkill(task, learned, steps, "تم", "com.whatsapp")
        assertEquals(listOf("p1", "p2"), skill.params)
        assertEquals("{p1}", skill.steps[1].label)
        assertEquals("{p2}", skill.steps[2].text)
        assertEquals("سارة", Templates.match(skill.pattern, "أرسل واتساب إلى سارة: أنا في الطريق")?.get("p1"))
        // Nothing typed or picked appears in the phrase: a fixed routine, no slots
        val fixed = Demo.generalize("اعرض صفحة البطارية", listOf(MacroStep("tap", "x||Battery", label = "Battery")), listOf("Battery"))
        assertEquals("اعرض صفحة البطارية", fixed.pattern)
        assertTrue(fixed.params.isEmpty())
        // Only whole words become slots
        assertTrue(Demo.generalize("افتح الإعدادات", listOf(MacroStep("type", "e||", text = "ال")), emptyList()).params.isEmpty())
    }

    @Test
    fun unfinishedDemonstrationsAreRecognised() {
        val open = MacroStep("open_app", text = "com.whatsapp", label = "WhatsApp")
        val pick = MacroStep("tap", "LinearLayout||أحمد", label = "أحمد")
        val type = MacroStep("type", "EditText|entry|", text = "مرحبا")
        val send = MacroStep("tap", "ImageButton|send|إرسال", label = "إرسال")
        val task = "أرسل واتساب إلى أحمد: مرحبا"
        assertNull(Demo.incomplete(task, listOf(open, pick, type, send)))
        assertNotNull(Demo.incomplete(task, listOf(open)))               // only opened the app
        assertNotNull(Demo.incomplete(task, listOf(open, pick, type)))   // typed, never sent
        assertNotNull(Demo.incomplete(task, listOf(open, pick)))         // the text the phrase promises was never typed
        assertNull(Demo.incomplete("افتح محادثة أحمد", listOf(open, pick)))   // nothing more was promised
        assertNull(Demo.incomplete("منبه الساعة 6:30", listOf(open, pick)))   // a colon inside a time promises nothing
        // A search is submitted from the keyboard; the recorder adds that step itself
        assertNull(Demo.incomplete("ابحث عن قطط", listOf(open, MacroStep("type", "EditText|search|", text = "قطط"), MacroStep("enter"))))
    }

    @Test
    fun mapFindsTheWayToAPage() {
        fun scr(key: String, tab: String = "", title: String = "") = MapScreen(key, "MainActivity", tab, title, 0, 0, LinkedHashMap(), 1, 0L)
        val m = AppMap("com.example")
        m.screens["home"] = scr("home", tab = "Home")
        m.screens["profile"] = scr("profile", tab = "Profile")
        m.screens["edit"] = scr("edit", title = "Edit profile")
        m.screens["prefs"] = scr("prefs", title = "Preferences")
        m.edges += MapEdge("home", "Profile", "profile", 3, "tab_profile")
        m.edges += MapEdge("profile", "Edit profile", "edit", 1)
        m.edges += MapEdge("home", "#btn_prefs", "prefs", 1, "btn_prefs")
        m.edges += MapEdge("home", AppMapStore.ITEM, "edit", 9)
        assertNull(m.findPath("Profile"))   // the app's first page is not known yet
        m.root = "home"
        assertEquals(listOf("Profile"), m.findPath("profile")?.map { it.label })
        assertEquals(listOf("Profile", "Edit profile"), m.findPath("Edit profile")?.map { it.label })
        // An icon without text is found through the title of the page it opens
        assertEquals(listOf("#btn_prefs"), m.findPath("Preferences")?.map { it.label })
        assertEquals(emptyList<String>(), m.findPath("Home")?.map { it.label })
        assertNull(m.findPath("Wallet"))
        // The same first page recorded again under another key (one more row was visible): old paths still count
        m.screens["home2"] = scr("home2", tab = "Home")
        m.root = "home2"
        assertEquals(listOf("Profile", "Edit profile"), m.findPath("Edit profile")?.map { it.label })
    }

    @Test
    fun navigationFactsAndHealth() {
        assertEquals(Cmd.Global("back", "تم الرجوع"), Commands.parse("ارجع"))
        assertEquals(Cmd.Info("battery"), Commands.parse("كم البطارية؟"))
        assertEquals(Cmd.Info("storage"), Commands.parse("كم المساحة المتبقية"))
        assertEquals(Cmd.Info("network"), Commands.parse("هل النت شغال"))
        assertEquals(Cmd.Health, Commands.parse("شخّص الهاتف"))
        assertNull(Commands.parse("كم متابع في انستقرام"))
    }

    @Test
    fun torchVolumeMedia() {
        assertEquals(Cmd.Torch(true), Commands.parse("شغل الكشاف"))
        assertEquals(Cmd.Torch(false), Commands.parse("أطفئ الفلاش"))
        assertEquals(Cmd.Volume(1), Commands.parse("ارفع الصوت"))
        assertEquals(Cmd.Media("next"), Commands.parse("التالي"))
    }

    @Test
    fun directSearches() {
        assertEquals("https://www.youtube.com/results?search_query=" + Commands.enc("تجارب علوم للأطفال"), url("ابحث في يوتيوب عن تجارب علوم للأطفال"))
        assertEquals("https://www.youtube.com/results?search_query=" + Commands.enc("تجارب علوم"), url("ابحث عن تجارب علوم في يوتيوب"))
        assertEquals("https://www.google.com/search?q=" + Commands.enc("الطقس في الدوحة"), url("ابحث في جوجل عن الطقس في الدوحة"))
        assertEquals("geo:0,0?q=" + Commands.enc("مطعم"), url("ابحث عن مطعم في الخرائط"))
        // A phone problem is not a web search: it goes to the diagnostic playbook
        assertNull(Commands.parse("ابحث عن سبب امتلاء مساحة التخزين للهاتف"))
    }

    @Test
    fun messagesAndCalls() {
        assertEquals("tel:55512345", url("اتصل ب 55512345"))
        assertEquals("https://wa.me/97455512345?text=" + Commands.enc("مرحبا بك"), url("أرسل واتساب إلى +97455512345: مرحبا بك"))
        assertEquals("sms:+97455512345?body=" + Commands.enc("أنا في الطريق"), url("ارسل رسالة نصية إلى +97455512345 أنا في الطريق"))
    }

    @Test
    fun alarmsAndTimers() {
        assertEquals(Cmd.Alarm(6, 0), Commands.parse("منبه الساعة 6:00 صباحاً"))
        assertEquals(Cmd.Alarm(18, 30), Commands.parse("نبهني الساعة ٦:٣٠ مساءً"))
        assertEquals(600, (Commands.parse("مؤقت 10 دقائق") as Cmd.Timer).seconds)
    }

    @Test
    fun settingsPagesAndSwitches() {
        assertEquals(Cmd.SettingsPage("battery"), Commands.parse("افتح إعدادات البطارية"))
        assertEquals(Cmd.SettingsPage("wifi"), Commands.parse("افتح الواي فاي"))
        assertEquals(Cmd.SettingsPage("main"), Commands.parse("افتح الإعدادات"))
        assertEquals(Cmd.SettingsPage("notifications"), Commands.parse("إعدادات الإشعارات"))
        assertEquals(Cmd.Global("notifications", "تم فتح الإشعارات"), Commands.parse("افتح الإشعارات"))
        assertEquals(Cmd.Toggle("wifi", true), Commands.parse("شغل الواي فاي"))
        assertEquals(Cmd.Toggle("bluetooth", false), Commands.parse("أطفئ البلوتوث"))
        assertEquals(Cmd.Toggle("airplane", true), Commands.parse("فعّل وضع الطيران"))
        // Apps and multi-step tasks are not settings commands
        assertNull(Commands.parse("افتح الكاميرا"))
        assertNull(Commands.parse("افتح انستقرام وعدل النبذة"))
    }

    @Test
    fun builtInKnowledge() {
        assertEquals("Instagram", KnowledgeBase.guideFor("com.instagram.android")?.name)
        assertTrue(KnowledgeBase.guideFor("com.instagram.android")!!.ar.contains("تعديل الملف الشخصي"))
        assertTrue(KnowledgeBase.guideFor("com.android.settings")!!.ar.contains("العناية بالجهاز"))
        assertTrue(KnowledgeBase.guideFor("com.mexcpro.client")!!.ar.contains("الأسواق"))
        assertEquals("WhatsApp", KnowledgeBase.guideFor("com.whatsapp")?.name)
        assertEquals("Crypto exchange", KnowledgeBase.guideFor("com.mexcpro.client")?.name)
        assertEquals("Settings", KnowledgeBase.guideFor("com.android.settings")?.name)
        assertNull(KnowledgeBase.guideFor("com.google.android.gms"))
        assertEquals("storage", KnowledgeBase.playbookFor("ابحث عن سبب امتلاء مساحة التخزين للهاتف")?.id)
        assertEquals("battery", KnowledgeBase.playbookFor("لماذا تنفد البطارية بسرعة؟")?.id)
        assertEquals("network", KnowledgeBase.playbookFor("الواي فاي لا يعمل")?.id)
        assertNull(KnowledgeBase.playbookFor("افتح انستقرام وعدل النبذة"))
        assertNotNull(SettingsCatalog.page("storage"))
        assertEquals("display", SettingsCatalog.keyForWords(Text.norm("الوضع الداكن")))
        assertEquals(100, Mastery.score(true, 10, 5, 5))
        assertEquals("جديد", Mastery.level(0))
    }

    @Test
    fun skillPatterns() {
        val p = "أرسل رسالة واتساب إلى {contact}: {message}"
        val m = Templates.match(p, "ارسل رساله واتساب الى سارة: كيف حالك؟")
        assertEquals("سارة", m?.get("contact"))
        assertEquals("كيف حالك", m?.get("message"))
        assertNull(Templates.match(p, "افتح يوتيوب"))
        assertEquals("{q}", Templates.templatize("قطط", mapOf("q" to "قطط")))
        val link = "https://www.youtube.com/results?search_query=" + Commands.enc("قطط مضحكة")
        assertEquals("https://www.youtube.com/results?search_query={q}", Templates.templatize(link, mapOf("q" to "قطط مضحكة")))
    }

    @Test
    fun skillGeneralizationIsSafe() {
        val task = "أرسل واتساب إلى أحمد: مرحبا"
        val route = listOf(
            MacroStep("open_app", text = "com.whatsapp", label = "WhatsApp"),
            MacroStep("tap", "TextView|x|أحمد", label = "أحمد"),
            MacroStep("type", "EditText|entry|", text = "مرحبا"),
        )
        val learned = Learner.Learned(
            "واتساب", "أرسل واتساب إلى {contact}: {message}",
            linkedMapOf("contact" to "أحمد", "message" to "مرحبا"), false, true, emptyList(), Usage(),
        )
        val ok = Learner.newSkill(task, learned, route, "تم", "com.whatsapp")
        assertEquals(listOf("contact", "message"), ok.params)
        assertEquals("{message}", ok.steps[2].text)
        assertEquals("com.whatsapp", ok.steps[0].text)
        // A slot that drives no step must not survive, or a replay would reuse the old value
        val bad = Learner.newSkill(task, learned, route.take(2), "تم", "com.whatsapp")
        assertTrue(bad.params.isEmpty())
        assertEquals(task, bad.pattern)
    }

    @Test
    fun links() {
        assertEquals("https://example.com/a", LocalRouter.normalizeUrl("example.com/a"))
        assertNull(LocalRouter.normalizeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(LocalRouter.normalizeUrl("javascript:alert(1)"))
        assertEquals("geo:0,0?q=%D8%A7", LocalRouter.encodeLoose("geo:0,0?q=ا"))
        assertEquals("a%20b", LocalRouter.encodeLoose("a b"))
    }

    @Test
    fun readOnlyGuards() {
        // The owner's own case: reported as "done" while saying nothing was executed
        assertTrue(Safety.saysReadOnlyStoppedIt("لم أنفذ أي صفقة: تطبيق TradingView مضبوط للقراءة فقط، والتداول التجريبي (Paper Trading) لا يمكن تنفيذه من خلالي."))
        assertTrue(Safety.saysReadOnlyStoppedIt("لم أشترِ: التطبيق للقراءة فقط."))
        assertTrue(Safety.saysReadOnlyStoppedIt("I could not place the order: this app is read-only for me."))
        assertFalse(Safety.saysReadOnlyStoppedIt("سعر BTC الآن 58,977 والاتجاه هابط. RSI عند 41."))
        assertFalse("a finished analysis that only mentions the mode is still done", Safety.saysReadOnlyStoppedIt("التحليل: الاتجاه صاعد. التطبيق في وضع القراءة فقط."))
        assertTrue(Safety.isTradeLabel("Buy BTC"))
        assertTrue(Safety.isTradeLabel("شراء"))
        assertTrue(Safety.isTradeLabel("تأكيد الطلب"))
        assertFalse(Safety.isTradeLabel("Order book"))
        assertFalse(Safety.isTradeLabel("15m"))
        assertFalse(Safety.isTradeLabel("الأسواق"))
        assertTrue(Safety.isSensitiveLabel("Send"))
        assertTrue(Safety.isSearchField("Search coin", ""))
    }

    @Test
    fun `tasks for later are recognised and their time phrase removed`() {
        val zone = TimeZone.getTimeZone("Asia/Qatar")
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance(zone).apply { clear(); set(y, mo - 1, d, h, mi, 0) }.timeInMillis
        val now = at(2026, 11, 20, 3, 10)
        fun p(s: String) = Schedule.parse(s, now, zone)

        // What the owner asked on the phone
        val asked = p("افتح الواتساب وارسل رسالة الي 70776067 بفتح خط قطر وارسل له رساله بعد دقيقة (هلا)")!!
        assertEquals(now + 60_000, asked.at)
        assertEquals("افتح الواتساب وارسل رسالة الي 70776067 بفتح خط قطر وارسل له رساله (هلا)", asked.task)
        assertEquals("بعد دقيقة", asked.phrase)

        // After a while
        assertEquals(now + 600_000, p("بعد ١٠ دقائق افتح يوتيوب")!!.at)
        assertEquals("افتح يوتيوب", p("بعد ١٠ دقائق افتح يوتيوب")!!.task)
        assertEquals("افتح يوتيوب", p("افتح يوتيوب بعد 5 دقائق.")!!.task)
        assertEquals(now + 90 * 60_000, p("بعد ساعة ونصف شغل الواي فاي")!!.at)
        assertEquals(now + 30 * 60_000, p("شغل البلوتوث بعد نصف ساعة")!!.at)
        assertEquals(now + 2 * 3600_000, p("بعد ساعتين من الآن افتح الكاميرا")!!.at)
        assertEquals("افتح الكاميرا", p("بعد ساعتين من الآن افتح الكاميرا")!!.task)
        assertEquals(now + 20 * 60_000, p("بعد عشرين دقيقة اطفئ الواي فاي")!!.at)
        assertEquals(now + 5 * 60_000, p("بعد خمس دقائق افتح الاعدادات")!!.at)
        assertEquals(now + 8_000, p("بعد 8 ثواني اعرض محفظة Sandbox")!!.at)
        assertEquals(now + 300_000, p("in 5 minutes open youtube")!!.at)
        assertEquals("open youtube", p("in 5 minutes open youtube")!!.task)

        // At a time on the clock
        assertEquals(at(2026, 11, 20, 20, 0), p("الساعة 8 مساءً ارسل تقرير الاسبوع")!!.at)
        assertEquals("ارسل تقرير الاسبوع", p("الساعة 8 مساءً ارسل تقرير الاسبوع")!!.task)
        assertEquals(at(2026, 11, 21, 7, 0), p("افتح الكاميرا غدا الساعة 7")!!.at)
        assertEquals("افتح الكاميرا", p("افتح الكاميرا غدا الساعة 7")!!.task)
        assertEquals(at(2026, 11, 20, 14, 0), p("افتح الإعدادات الساعة 2")!!.at)          // 2 at night has passed: the next 2 o'clock
        assertEquals(at(2026, 11, 20, 17, 30), p("ارسلها الساعة 5:30 م")!!.at)
        assertEquals(at(2026, 11, 20, 17, 30), p("افتح الخرائط الساعة الخامسة والنصف عصراً")!!.at)
        assertEquals(at(2026, 11, 20, 8, 45), p("الساعة 9 إلا ربع صباحا افتح البريد")!!.at)
        assertEquals(at(2026, 11, 21, 2, 0), p("الساعة 2 ص افتح الكاميرا")!!.at)          // already past today
        assertEquals(at(2026, 11, 20, 17, 30), p("open maps at 5:30 pm")!!.at)

        // A time inside the message is the message, not a schedule
        assertNull(p("أرسل إلى أحمد: سأصل بعد 5 دقائق"))
        assertNull(p("أرسل «سأصل بعد ساعة» إلى أحمد"))
        val quoted = p("ارسل «سأصل بعد ساعة» الى احمد بعد نصف ساعة")!!
        assertEquals(now + 30 * 60_000, quoted.at)
        assertEquals("ارسل «سأصل بعد ساعة» الى احمد", quoted.task)

        // Not schedules at all
        assertNull(p("اضبط منبه الساعة 7 صباحا"))
        assertNull(p("ذكرني بعد 10 دقائق بالدواء"))
        assertNull(p("ارسل له بعد ذلك صورة"))
        assertNull(p("كم الساعة الآن"))
        assertNull(p("افتح الإعدادات"))
        assertNull(p("بعد 5 دقائق"))

        assertEquals("بعد دقيقة (الساعة 3:11 ص)", Schedule.describe(now + 60_000, now, zone))
        assertEquals("بعد 10 دقيقة (الساعة 3:20 ص)", Schedule.describe(now + 600_000, now, zone))
        assertEquals("اليوم الساعة 8:00 م", Schedule.describe(at(2026, 11, 20, 20, 0), now, zone))
        assertEquals("غداً الساعة 7:00 ص", Schedule.describe(at(2026, 11, 21, 7, 0), now, zone))
    }

    @Test
    fun `send buttons are told apart from other sensitive buttons`() {
        assertTrue(Safety.isSendLabel("إرسال"))
        assertTrue(Safety.isSendLabel("Send"))
        assertTrue(Safety.isSendLabel("نشر"))
        assertFalse(Safety.isSendLabel("حذف"))
        assertFalse(Safety.isSendLabel("البريد الوارد"))
        assertFalse(Safety.isSendLabel("المزيد"))
        assertFalse(Safety.isSendLabel(""))
    }

    @Test
    fun `a local number gets the country code of the SIM in chat links`() {
        assertEquals("97470776067", Dial.international("70776067", "974"))
        assertEquals("97470776067", Dial.international("+97470776067", "974"))
        assertEquals("97470776067", Dial.international("0097470776067", "974"))
        assertEquals("97470776067", Dial.international("97470776067", "974"))     // already complete
        assertEquals("966555123456", Dial.international("0555123456", "966"))     // the leading zero of a local number goes
        assertEquals("70776067", Dial.international("70776067", null))             // no SIM country known: left alone
        assertEquals("974", Dial.code("QA"))
        assertNull(Dial.code(""))

        assertEquals("https://wa.me/97470776067?text=hi", Dial.waLink("https://wa.me/70776067?text=hi", "974"))
        assertEquals("https://wa.me/97470776067", Dial.waLink("https://wa.me/+97470776067", "974"))
        assertEquals("https://example.com/70776067", Dial.waLink("https://example.com/70776067", "974"))

        val local = (Commands.parse("أرسل واتساب إلى 70776067: هلا", "974") as? Cmd.Url)?.url
        assertEquals("https://wa.me/97470776067?text=" + Commands.enc("هلا"), local)
        assertEquals("https://wa.me/97455512345?text=" + Commands.enc("مرحبا بك"), url("أرسل واتساب إلى +97455512345: مرحبا بك"))
    }
}
