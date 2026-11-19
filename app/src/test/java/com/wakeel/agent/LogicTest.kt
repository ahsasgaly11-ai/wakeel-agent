package com.wakeel.agent

import com.wakeel.agent.agent.AppMap
import com.wakeel.agent.agent.AppMapStore
import com.wakeel.agent.agent.Cmd
import com.wakeel.agent.agent.Commands
import com.wakeel.agent.agent.Demo
import com.wakeel.agent.agent.KnowledgeBase
import com.wakeel.agent.agent.Learner
import com.wakeel.agent.agent.LocalRouter
import com.wakeel.agent.agent.MacroStep
import com.wakeel.agent.agent.MapEdge
import com.wakeel.agent.agent.MapScreen
import com.wakeel.agent.agent.Mastery
import com.wakeel.agent.agent.Safety
import com.wakeel.agent.agent.SettingsCatalog
import com.wakeel.agent.agent.Templates
import com.wakeel.agent.core.Text
import com.wakeel.agent.core.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the parts that must be exact: Arabic command parsing, skill generalization, safety guards. */
class LogicTest {
    private fun url(task: String): String? = (Commands.parse(task) as? Cmd.Url)?.url

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
        assertTrue(Safety.isTradeLabel("Buy BTC"))
        assertTrue(Safety.isTradeLabel("شراء"))
        assertTrue(Safety.isTradeLabel("تأكيد الطلب"))
        assertFalse(Safety.isTradeLabel("Order book"))
        assertFalse(Safety.isTradeLabel("15m"))
        assertFalse(Safety.isTradeLabel("الأسواق"))
        assertTrue(Safety.isSensitiveLabel("Send"))
        assertTrue(Safety.isSearchField("Search coin", ""))
    }
}
