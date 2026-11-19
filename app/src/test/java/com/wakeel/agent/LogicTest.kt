package com.wakeel.agent

import com.wakeel.agent.agent.Cmd
import com.wakeel.agent.agent.Commands
import com.wakeel.agent.agent.KnowledgeBase
import com.wakeel.agent.agent.Learner
import com.wakeel.agent.agent.LocalRouter
import com.wakeel.agent.agent.MacroStep
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
