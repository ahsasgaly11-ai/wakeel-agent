package com.wakeel.agent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.wakeel.agent.agent.EngineState
import com.wakeel.agent.agent.MacroStep
import com.wakeel.agent.agent.Skill
import com.wakeel.agent.core.Policy
import com.wakeel.agent.core.Shot
import com.wakeel.agent.core.TaskRecord
import com.wakeel.agent.core.TaskStatus
import com.wakeel.agent.core.Usage
import com.wakeel.agent.llm.LlmClient
import com.wakeel.agent.llm.ToolResult
import com.wakeel.agent.llm.ToolSpec
import com.wakeel.agent.service.AgentAccessibilityService
import com.wakeel.agent.service.Screen
import com.wakeel.agent.service.UiNode
import com.wakeel.agent.trade.Analysis
import com.wakeel.agent.trade.Candle
import com.wakeel.agent.trade.LiveMarket
import com.wakeel.agent.trade.MarketSource
import com.wakeel.agent.trade.Side
import com.wakeel.agent.trade.Trader
import com.wakeel.agent.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

private const val TAG = "WakeelTest"
private const val SANDBOX = "com.wakeel.sandbox"
private const val SETTINGS_APP = "com.android.settings"

/**
 * Stands in for the AI model: answers with the plans a test scripted, and nothing else.
 * Everything around it is real — the accessibility service, the taps, the typing, the apps on the emulator.
 */
private object FakeModel : LlmClient {
    val plans = ArrayDeque<String>()

    @Volatile
    var learn: JSONObject? = null

    @Volatile
    var lastKnowledge: String? = null

    /** How many times the model was asked to plan. */
    @Volatile
    var calls = 0

    /** What the model was last shown. */
    @Volatile
    var lastUser = ""

    /** What the model answers when asked to conclude a task that used up its requests (null = nothing usable). */
    @Volatile
    var finish: JSONObject? = null

    /** What the model was shown when asked to conclude. */
    @Volatile
    var lastFinishUser = ""

    /** The trading mode's decision for the next cycle, and what the model was shown to make it. */
    @Volatile
    var trade: JSONObject? = null

    @Volatile
    var lastTradeUser = ""

    @Volatile
    var tradeCalls = 0

    private const val NO_PLAN = """{"thought":"لا خطة","actions":[{"type":"fail","text":"انتهت الخطط المجهّزة للاختبار."}],"sensitive":false}"""

    override suspend fun callTool(
        model: String, system: String, user: String, shot: Shot?, tool: ToolSpec, maxTokens: Int, extraSystem: String?,
    ): ToolResult {
        Log.i(TAG, "   ◇ model asked (${tool.name}): " + user.lineSequence().firstOrNull().orEmpty().take(120))
        return when (tool.name) {
            "act" -> {
                calls++
                lastUser = user
                lastKnowledge = extraSystem
                val p = synchronized(plans) { plans.removeFirstOrNull() } ?: NO_PLAN
                ToolResult(JSONObject(p), Usage(900, 60))
            }
            "trade" -> {
                tradeCalls++
                lastTradeUser = user
                ToolResult(trade ?: JSONObject().put("outlook", "").put("decisions", JSONArray()), Usage(1500, 120))
            }
            "finish" -> {
                lastFinishUser = user
                ToolResult(finish ?: JSONObject(), Usage(900, 80))
            }
            "learn" -> {
                val task = user.lineSequence().firstOrNull().orEmpty().removePrefix("TASK: ")
                ToolResult(
                    learn ?: JSONObject().put("title", task.take(30)).put("pattern", task).put("params", JSONArray())
                        .put("readout", false).put("reusable", true),
                    Usage(300, 40),
                )
            }
            else -> ToolResult(JSONObject().put("skill_id", JSONObject.NULL), Usage(100, 5))
        }
    }
}

/**
 * Runs on an Android emulator in every CI build. Order matters (later tests reuse what earlier ones taught),
 * so the methods are numbered.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceTest {

    companion object {
        private val inst get() = InstrumentationRegistry.getInstrumentation()
        private val ctx get() = inst.targetContext
        private val report by lazy { File(ctx.filesDir, "device-report.txt") }
        private var uiAutomation: UiAutomation? = null

        fun note(s: String) {
            Log.i(TAG, s)
            runCatching { report.appendText(s + "\n") }
        }

        fun waitUntil(ms: Long, step: Long = 200, cond: () -> Boolean): Boolean {
            val end = SystemClock.uptimeMillis() + ms
            while (SystemClock.uptimeMillis() < end) {
                if (runCatching(cond).getOrDefault(false)) return true
                Thread.sleep(step)
            }
            return runCatching(cond).getOrDefault(false)
        }

        /** A shell on the emulator (keeps other accessibility services, ours included, running). */
        fun shell(cmd: String): String {
            val ui = uiAutomation ?: inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also { uiAutomation = it }
            return ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).use { it.readBytes().toString(Charsets.UTF_8) }
        }

        @BeforeClass
        @JvmStatic
        fun switchServiceOn() {
            runCatching { report.delete() }
            val service = ctx.packageName + "/" + AgentAccessibilityService::class.java.name
            // The way a user's toggle ends up in the system: the service is listed as enabled
            val direct = runCatching {
                Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, service)
                Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            if (direct.isFailure) {
                note("direct enable failed (${direct.exceptionOrNull()}); using the shell")
                shell("settings put secure enabled_accessibility_services $service")
                shell("settings put secure accessibility_enabled 1")
            }
            val ok = waitUntil(40_000) { AgentAccessibilityService.instance != null }
            note("accessibility service connected: $ok")

            LlmClient.override = { FakeModel }
            val s = Hub.settings
            s.provider = "custom"
            s.apiKey = "test-key"
            s.fastModel = "scripted-model"
            s.strongModel = ""
            s.maxSteps = 6
            s.confirmSensitive = true
            Hub.skills.clear()
            Hub.notes.clear()
            Hub.maps.clearAll()
            Hub.history.clear()
        }

        @AfterClass
        @JvmStatic
        fun done() {
            LlmClient.override = null
            note("END OF DEVICE TESTS")
        }
    }

    // ------------------------------------------------------------------ helpers

    private var confirmed = false
    private var confirmCount = 0
    private val questions = ArrayList<String>()

    private fun svc(): AgentAccessibilityService =
        AgentAccessibilityService.instance ?: throw AssertionError("the accessibility service is not connected")

    private fun screen(): Screen = svc().readScreen()

    private fun find(label: String, ms: Long = 8000): UiNode? {
        var n: UiNode? = null
        waitUntil(ms) { n = screen().match(label); n != null }
        return n
    }

    private fun byId(id: String, ms: Long = 8000): UiNode? {
        var n: UiNode? = null
        waitUntil(ms) { n = screen().nodes.firstOrNull { it.viewId == id }; n != null }
        return n
    }

    /** The sandbox app writes what was done to it in this line. */
    private fun status(): String = screen().nodes.firstOrNull { it.viewId == "status" }?.label.orEmpty()

    private fun dump(title: String) {
        val s = screen()
        note("── $title: app=${s.pkg} activity=${s.activity} elements=${s.nodes.size}")
        s.compact().lineSequence().take(45).forEach { if (it.isNotBlank()) note("     $it") }
        if (s.nodes.isEmpty()) {
            // Nothing readable: say which windows Android reports, to tell "still loading" from "cannot be read"
            runCatching {
                svc().windows.forEach { w ->
                    note("     window type=${w.type} active=${w.isActive} focused=${w.isFocused} title=${w.title} app=${runCatching { w.root?.packageName }.getOrNull()}")
                }
            }
        }
    }

    private fun goHome() {
        svc().global(AccessibilityService.GLOBAL_ACTION_HOME)
        waitUntil(8000) { screen().pkg in Hub.apps.launcherPackages() }
        Thread.sleep(600)
    }

    /** A result window of our own may cover the app under test: close it. */
    private fun closeOwnWindows(waitMs: Long) {
        if (!waitUntil(waitMs) { screen().pkg == ctx.packageName }) return
        svc().global(AccessibilityService.GLOBAL_ACTION_BACK)
        waitUntil(5000) { screen().pkg.let { it.isNotEmpty() && it != ctx.packageName } }
        Thread.sleep(400)
    }

    /** [answer] decides each question from its text; without it every question gets [allow]. */
    private fun await(
        before: Long?, what: String, allow: Boolean, timeoutMs: Long, always: Boolean = false, answer: ((String) -> Boolean)? = null,
    ): TaskRecord {
        confirmed = false
        confirmCount = 0
        questions.clear()
        var asking = false
        val finished = waitUntil(timeoutMs, 150) {
            val st = Hub.engine.state.value
            if (st is EngineState.Confirm) {
                if (!asking) {
                    confirmCount++
                    questions += st.question
                    note("     ? ${st.question.replace("\n", " ⏎ ")}  [${st.yes} / ${st.no}]")
                }
                asking = true
                confirmed = true
                Hub.engine.answerConfirm(answer?.invoke(st.question) ?: allow, always)
            } else {
                asking = false
            }
            val top = Hub.history.records.value.firstOrNull()
            top != null && top.id != before && !Hub.engine.isRunning
        }
        if (!finished) Hub.engine.stop()
        val rec = Hub.history.records.value.firstOrNull()
        rec?.logs?.forEach { note("     [${it.kind}] ${it.text.take(220)}") }
        note("◀ ${rec?.status} · model calls=${rec?.calls} · local steps=${rec?.localSteps} · steps=${rec?.steps} · ${rec?.result?.lineSequence()?.firstOrNull().orEmpty().take(160)}")
        assertTrue("did not finish in time: $what", finished && rec != null && rec.id != before)
        // Failures and long answers open our result window; short ones are a toast
        val opensWindow = rec!!.status == TaskStatus.FAILED || rec.result.length >= 90 || rec.result.contains('\n')
        closeOwnWindows(if (opensWindow) 5000 else 900)
        return rec
    }

    private fun run(
        task: String, allow: Boolean = true, timeoutMs: Long = 120_000, always: Boolean = false, answer: ((String) -> Boolean)? = null,
    ): TaskRecord {
        val before = Hub.history.records.value.firstOrNull()?.id
        note("▶ TASK: $task")
        Hub.engine.start(task)
        return await(before, task, allow, timeoutMs, always, answer)
    }

    private fun plan(actions: String, sensitive: Boolean = false): String =
        """{"thought":"خطة الاختبار","actions":$actions,"sensitive":$sensitive}"""

    /** A real touch on the screen, as the owner's finger would do. */
    private fun touch(n: UiNode) {
        val ok = runBlocking { svc().tap(n.bounds.exactCenterX(), n.bounds.exactCenterY()) }
        assertTrue("touch gesture was not delivered", ok)
    }

    @Before
    fun fresh() {
        synchronized(FakeModel.plans) { FakeModel.plans.clear() }
        FakeModel.learn = null
        if (AgentAccessibilityService.instance != null) goHome()
    }

    // ------------------------------------------------------------------ the app itself

    @Test
    fun t01_serviceConnects() {
        assertNotNull("the accessibility service did not connect", AgentAccessibilityService.instance)
        assertTrue(AgentAccessibilityService.connected.value)
        assertTrue(AgentAccessibilityService.isEnabledInSettings(ctx))
        val launchers = Hub.apps.launcherPackages()
        note("launchers: $launchers · apps on device: ${Hub.apps.all().size}")
        assertTrue("no launcher found", launchers.isNotEmpty())
        assertFalse("Settings must not be treated as a home screen", SETTINGS_APP in launchers)
        dump("home screen")
    }

    @Test
    fun t02_mainScreenAndTabs() {
        svc().startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("Wakeel's main screen did not appear", waitUntil(20_000) { screen().pkg == ctx.packageName })
        assertNotNull("command box missing", find("ماذا أنجز لك؟", 10_000))
        dump("Wakeel · القيادة")
        val expect = linkedMapOf("المعرفة" to "التطبيقات", "التداول" to "الحساب التجريبي", "السجل" to "لا توجد مهام بعد", "الإعدادات" to "مزوّد الذكاء الاصطناعي", "القيادة" to "ماذا أنجز لك؟")
        for ((tab, marker) in expect) {
            val node = screen().nodes.lastOrNull { it.label.trim() == tab && it.clickable }
                ?: screen().nodes.lastOrNull { it.label.trim() == tab }
            assertNotNull("tab «$tab» missing", node)
            var info: AccessibilityNodeInfo? = node!!.info
            var clicked = false
            repeat(4) {
                val c = info
                if (!clicked && c != null) {
                    clicked = c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    info = c.parent
                }
            }
            if (!clicked) touch(node)
            val shown = waitUntil(8000) { screen().nodes.any { it.label.contains(marker) } }
            dump("Wakeel · $tab")
            assertTrue("tab «$tab» did not show «$marker»", shown)
            assertEquals("Wakeel left the foreground on tab «$tab»", ctx.packageName, screen().pkg)
        }
    }

    @Test
    fun t03_localAnswersCostNothing() {
        val battery = run("كم البطارية؟")
        assertEquals(TaskStatus.DONE, battery.status)
        assertEquals(0, battery.calls)
        assertTrue(battery.result.contains("%"))

        val storage = run("كم المساحة المتبقية")
        assertEquals(TaskStatus.DONE, storage.status)
        assertEquals(0, storage.calls)

        val health = run("شخّص الهاتف")
        assertEquals(TaskStatus.DONE, health.status)
        assertEquals(0, health.calls)
        assertTrue("the health report is too short", health.result.length > 120)
    }

    @Test
    fun t04_settingsPageOpensDirectly() {
        val r = run("افتح إعدادات البطارية")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals(0, r.calls)
        assertTrue("the Settings app did not come to the front", waitUntil(10_000) { screen().pkg == SETTINGS_APP })
        dump("battery settings")
    }

    // ------------------------------------------------------------------ a third-party app (the sandbox)

    @Test
    fun t05_seesOtherAppsAndReadsTheirScreens() {
        val app = Hub.apps.exact("Sandbox")
        assertNotNull("the sandbox app is not installed or not visible", app)
        assertEquals(SANDBOX, app!!.pkg)
        assertTrue(Hub.apps.launch(SANDBOX, fresh = true))
        assertTrue(waitUntil(15_000) { screen().pkg == SANDBOX })
        assertNotNull(byId("search_box"))
        dump("sandbox home")
        val s = screen()
        assertNotNull("label lookup failed", s.match("Contacts"))
        assertNotNull("id lookup failed", s.match("#btn_prefs"))
        assertTrue("the text field was not recognised as editable", s.nodes.first { it.viewId == "search_box" }.editable)
        assertEquals("status: idle", status())
    }

    @Test
    fun t06_planAheadTypesAndLearns() {
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Contacts"},{"type":"tap","find":"Sara"},
                {"type":"type","find":"Message","text":"مرحبا"},{"type":"tap","find":"Send"},
                {"type":"done","text":"أرسلتُ «مرحبا» إلى Sara."}]""",
            sensitive = true,
        )
        FakeModel.learn = JSONObject()
            .put("title", "رسالة في Sandbox").put("pattern", "أرسل في Sandbox إلى {contact}: {message}")
            .put("params", JSONArray().put(JSONObject().put("name", "contact").put("value", "Sara")).put(JSONObject().put("name", "message").put("value", "مرحبا")))
            .put("readout", false).put("reusable", true)
        val r = run("أرسل في Sandbox إلى Sara: مرحبا")
        dump("after the first message")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("one planning call for five screens' worth of actions, plus one learning call", 2, r.calls)
        assertTrue("sending must wait for the owner's confirmation", confirmed)
        assertEquals("sent: مرحبا to Sara", status())
        val skill = Hub.skills.skills.value.single()
        note("learned: ${skill.pattern} · ${skill.steps.joinToString(" → ") { it.type + ":" + (it.label ?: it.text.orEmpty()) }}")
        assertEquals(listOf("contact", "message"), skill.params)
        assertEquals(SANDBOX, skill.pkg)
    }

    @Test
    fun t07_learnedSkillRunsFreeWithNewValues() {
        // Second time, other values: replayed locally, one small call confirms the result
        FakeModel.plans += plan("""[{"type":"done","text":"تم الإرسال."}]""")
        val second = run("أرسل في Sandbox إلى Omar: كيف الحال")
        assertEquals(TaskStatus.DONE, second.status)
        assertEquals("sent: كيف الحال to Omar", status())
        assertEquals(1, second.calls)
        assertTrue(confirmed)

        // Saying no to the confirmation stops the task: nothing is sent, and no model call is spent on another way
        val before = Hub.skills.skills.value.single()
        val denied = run("أرسل في Sandbox إلى Layla: لا ترسل", allow = false)
        note("denied run: ${denied.status} · ${status()}")
        assertFalse("a denied message was sent anyway", status().contains("لا ترسل"))
        assertEquals(TaskStatus.STOPPED, denied.status)
        assertEquals(0, denied.calls)
        assertEquals("a refusal is not a failure of the skill", before.fails, Hub.skills.skills.value.single().fails)

        // Third time: no model at all — and the contact is far down the list, so it has to scroll to find it.
        // This time the owner answers "always allow for this skill".
        val third = run("أرسل في Sandbox إلى Saeed: تمام", always = true)
        dump("after the free replay")
        assertEquals(TaskStatus.DONE, third.status)
        assertEquals("sent: تمام to Saeed", status())
        assertEquals("a trusted skill must not call the model", 0, third.calls)
        assertEquals("أرسلتُ «تمام» إلى Saeed.", third.result)
        assertTrue(confirmed)

        // From now on this skill sends without asking
        val fourth = run("أرسل في Sandbox إلى Yousef: بلا سؤال")
        assertEquals(TaskStatus.DONE, fourth.status)
        assertEquals("sent: بلا سؤال to Yousef", status())
        assertEquals(0, fourth.calls)
        assertFalse("the skill was allowed for good, yet it asked again", confirmed)
    }

    @Test
    fun t08_readOnlyAppRefusesToBuy() {
        Hub.settings.setPolicy(SANDBOX, Policy.READ_ONLY)
        try {
            FakeModel.plans += plan(
                """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Wallet"},{"type":"type","find":"Search coin","text":"BTC"},
                    {"type":"tap","find":"Buy BTC"},{"type":"done","text":"اشتريت."}]""",
                sensitive = true,
            )
            FakeModel.plans += plan("""[{"type":"done","text":"لم أشترِ: التطبيق للقراءة فقط."}]""")
            val r = run("اشترِ BTC من Sandbox")
            dump("wallet after the refused purchase")
            assertTrue("the Buy tap was not refused", r.logs.any { it.text.contains("رُفضت") })
            assertEquals("status: idle", status())
            assertEquals("searching is allowed in a read-only app", "BTC", screen().nodes.first { it.viewId == "coin_search" }.label)
            assertTrue("nothing may be learned from a refused route", Hub.skills.skills.value.none { it.example.contains("BTC") })
        } finally {
            Hub.settings.setPolicy(SANDBOX, null)
        }
    }

    @Test
    fun t09_passwordFieldsAreNeverTyped() {
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Profile"},{"type":"tap","find":"Security"},
                {"type":"type","find":"PIN","text":"1234"},{"type":"done","text":"كتبتُ الرمز."}]"""
        )
        FakeModel.plans += plan("""[{"type":"fail","text":"لا أكتب كلمات المرور."}]""")
        val r = run("اكتب الرمز 1234 في Sandbox")
        dump("security page")
        assertEquals(TaskStatus.FAILED, r.status)
        assertEquals("pin length: 0", byId("pin_len")?.label)
    }

    @Test
    fun t10_exploringAnAppMakesItsPagesFree() {
        Hub.maps.clear(SANDBOX)
        val before = Hub.history.records.value.firstOrNull()?.id
        note("▶ EXPLORE: $SANDBOX")
        Hub.engine.explore(SANDBOX)
        val explored = await(before, "explore", true, 120_000)
        assertEquals(TaskStatus.DONE, explored.status)
        assertEquals(0, explored.calls)
        note("map: ${Hub.maps.screenCount(SANDBOX)} screens, ${Hub.maps.pathCount(SANDBOX)} paths\n" + Hub.maps.describe(SANDBOX))
        assertTrue("the tabs were not learned", Hub.maps.screenCount(SANDBOX) >= 4)
        assertNotNull("no path to the Wallet tab", Hub.maps.findPath(SANDBOX, "Wallet"))

        goHome()
        val r = run("افتح Wallet في Sandbox")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("a page the map knows must open without the model", 0, r.calls)
        assertNotNull("the Wallet page is not showing", find("Buy BTC"))
    }

    @Test
    fun t11_iconsWithoutTextAreFoundById() {
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"#btn_prefs"},{"type":"tap","find":"Dark mode"},
                {"type":"done","text":"فعّلتُ الوضع الداكن."}]"""
        )
        val r = run("فعّل الوضع الداكن في Sandbox")
        dump("preferences")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("dark: on", status())

        goHome()
        val again = run("افتح Preferences في Sandbox")
        assertEquals(TaskStatus.DONE, again.status)
        assertEquals(0, again.calls)
        assertNotNull("the Preferences page is not showing", find("Dark mode"))
    }

    @Test
    fun t12_learnsFromTheOwnersOwnHands() {
        Hub.skills.clear()
        assertTrue("recording did not start", Hub.recorder.start("راسل Omar وقل له hello"))
        Thread.sleep(2000)   // returns to the home screen, then starts listening
        // The owner opens the app from the home screen…
        assertTrue(Hub.apps.launch(SANDBOX))
        assertTrue(waitUntil(15_000) { screen().pkg == SANDBOX })
        assertNotNull("the recorder should restart the app on its first page", byId("search_box", 10_000))
        Thread.sleep(800)
        // …and performs the task with real touches and real key presses
        touch(find("Contacts")!!)
        touch(find("Omar")!!)
        val field = byId("message")
        assertNotNull("the chat did not open", field)
        touch(field!!)
        Thread.sleep(800)
        shell("input text hello")
        assertTrue("typing did not reach the field", waitUntil(8000) { screen().nodes.any { it.viewId == "message" && it.label == "hello" } })
        assertNotNull("the service must survive the test's shell", AgentAccessibilityService.instance)
        touch(find("Send")!!)
        assertTrue(waitUntil(8000) { status() == "sent: hello to Omar" })
        Thread.sleep(600)
        note("recorder saw: ${Hub.recorder.state.value}")
        Hub.recorder.trace.forEach { note("     · $it") }
        // Back on the home screen: after a still moment the skill saves itself, nothing to press
        svc().global(AccessibilityService.GLOBAL_ACTION_HOME)
        // The recorder switches itself off a moment before it publishes what became of the recording
        assertTrue("the recording did not end by itself", waitUntil(15_000) { !Hub.recorder.active && Hub.recorder.lastOutcome != null })
        val outcome = Hub.recorder.lastOutcome
        note("ended by itself: saved=${outcome?.skill != null} reason=${outcome?.reason}")
        val skill = outcome?.skill
        assertNotNull("the finished demonstration was not saved: ${outcome?.reason}", skill)
        assertEquals(skill!!.id, Hub.skills.skills.value.single().id)
        note("taught: ${skill.pattern} · ${skill.steps.joinToString(" → ") { it.type + ":" + (it.label ?: it.text.orEmpty()) }}")
        assertEquals("راسل {p1} وقل له {p2}", skill.pattern)
        assertTrue("a taught skill is trusted from the start", skill.trusted)

        goHome()
        val r = run("راسل Sara وقل له salam")
        dump("after replaying the taught skill")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("a taught skill must not call the model", 0, r.calls)
        assertEquals("sent: salam to Sara", status())
    }

    @Test
    fun t16_unfinishedDemonstrationIsNotSaved() {
        val before = Hub.skills.skills.value.map { it.id }
        assertTrue("recording did not start", Hub.recorder.start("اكتب إلى Sara ملاحظة draft"))
        Thread.sleep(2000)
        assertTrue(Hub.apps.launch(SANDBOX))
        assertTrue(waitUntil(15_000) { screen().pkg == SANDBOX })
        assertNotNull(byId("search_box", 10_000))
        Thread.sleep(800)
        touch(find("Contacts")!!)
        touch(find("Sara")!!)
        val field = byId("message")
        assertNotNull("the chat did not open", field)
        touch(field!!)
        Thread.sleep(800)
        shell("input text draft")
        assertTrue("typing did not reach the field", waitUntil(8000) { screen().nodes.any { it.viewId == "message" && it.label == "draft" } })
        // The owner leaves without sending: text was typed and nothing was pressed after it
        svc().global(AccessibilityService.GLOBAL_ACTION_HOME)
        // The recorder switches itself off a moment before it publishes what became of the recording
        assertTrue("the recording did not end by itself", waitUntil(15_000) { !Hub.recorder.active && Hub.recorder.lastOutcome != null })
        val outcome = Hub.recorder.lastOutcome
        note("unfinished demonstration: saved=${outcome?.skill != null} reason=${outcome?.reason}")
        assertNull("an unfinished demonstration must not become a skill", outcome?.skill)
        assertNotNull(outcome?.reason)
        assertEquals(before, Hub.skills.skills.value.map { it.id })
    }

    @Test
    fun t17_taughtSkillThatCannotCompleteIsDeleted() {
        // A hand-taught skill whose steps do not exist on screen (it was recorded unfinished or wrongly)
        val task = "افتح الصفحة المفقودة في العرض"
        Hub.skills.put(
            Skill(
                id = "broken1", title = "صفحة مفقودة", pattern = task, params = emptyList(), example = task,
                steps = listOf(
                    MacroStep("open_app", text = SANDBOX, label = "Sandbox"),
                    MacroStep("tap", "Button|nope|No such button", label = "No such button"),
                ),
                doneTpl = "تم", readout = false, successes = 2, pkg = SANDBOX, taught = true,
            )
        )
        FakeModel.plans += plan("""[{"type":"done","text":"الزر غير موجود."}]""")
        val r = run(task)
        assertTrue("the owner was not told why", r.logs.any { it.text.contains("ناقصة") })
        assertNull("the unfinished skill is still stored", Hub.skills.byId("broken1"))
    }

    @Test
    fun t19_sameMessageIsNeverSentTwice() {
        // What happened on a real phone: the message went out, the model was not sure, and it sent it again.
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Contacts"},{"type":"tap","find":"Mariam"},
                {"type":"type","find":"Message","text":"مرة واحدة فقط"},{"type":"tap","find":"Send"}]""",
            sensitive = true,
        )
        // The unsure model tries the very same message again…
        FakeModel.plans += plan(
            """[{"type":"type","find":"Message","text":"مرة واحدة فقط"},{"type":"tap","find":"Send"},{"type":"done","text":"أرسلتُها مرة أخرى."}]""",
            sensitive = true,
        )
        val r = run("أبلغ Mariam في Sandbox: مرة واحدة فقط")
        dump("after the guarded send")
        assertEquals("the task is done — the message did go out", TaskStatus.DONE, r.status)
        assertEquals("the second attempt ends the task instead of costing more calls", 2, r.calls)
        assertEquals("the message must have gone out exactly once", "messages sent to Mariam: 1", byId("sent_count")?.label)
        assertEquals("the owner must be asked once, not once per attempt", 1, confirmCount)
        assertTrue("the second attempt was not stopped", r.logs.any { it.text.contains("منعتُ") })
        assertTrue("the model was not told the message went out", FakeModel.lastUser.contains("SENT"))
    }

    @Test
    fun t20_manyItemsAreSelectedAndMovedInOnePlan() {
        // A gallery-like page: 360 thumbnails, far more on one screen than can be listed, and a grid that
        // accepts the accessibility long-press and ignores it — as real galleries do. Only a real touch selects.
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Photos"},{"type":"long_press","find":"Photo 1"},
                {"type":"tap","find":"All"},{"type":"tap","find":"More"},{"type":"tap","find":"Move to album"},
                {"type":"tap","find":"Create album"},{"type":"type","find":"Album name","text":"صور الهند"},{"type":"tap","find":"Create"},
                {"type":"done","text":"نقلتُ الصور إلى ألبوم «صور الهند»."}]"""
        )
        val r = run("انقل كل صور Sandbox إلى ألبوم جديد اسمه صور الهند")
        dump("after moving the photos")
        val s = screen()
        note("dense grid: ${s.nodes.size} elements read, ${s.nodes.count { it.inList }} of them thumbnails")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("one planning call for the whole job, plus one learning call", 2, r.calls)
        assertEquals("the bars around a dense grid must stay readable", "moved 360 photos to album صور الهند", status())
        assertTrue("the grid must not use up the whole element list", s.nodes.count { it.inList } <= 110)
        assertNotNull("the tab bar under the grid was crowded out", s.nodes.firstOrNull { it.viewId == "tab_wallet" })
    }

    @Test
    fun t21_aRepeatedStepIsNamedWhenTheTaskStops() {
        FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Wallet"}]""")
        // The model keeps pressing something that does nothing
        repeat(3) { FakeModel.plans += plan("""[{"type":"tap","find":"Balance: 100 USDT"}]""") }
        val r = run("اضغط على الرصيد في محفظة Sandbox حتى يتغيّر")
        assertEquals(TaskStatus.FAILED, r.status)
        assertEquals("the third identical step must end the task", 4, r.calls)
        assertTrue("the owner is not told which step kept repeating: ${r.result}", r.result.contains("Balance: 100 USDT"))
        assertTrue("the owner is not told where it happened: ${r.result}", r.result.contains("Sandbox"))
        assertTrue("no way forward is offered: ${r.result}", r.result.contains("علّمني مهمة"))
    }

    @Test
    fun t22_aTaskForLaterWaitsForItsTime() {
        Hub.scheduler.clear()
        // "after 8 seconds": the owner is asked once, and the task is kept on the phone — the model is not involved
        val asked = FakeModel.calls
        val r = run("بعد 8 ثواني اعرض محفظة Sandbox")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("scheduling must not call the model", asked, FakeModel.calls)
        assertTrue("the owner was not asked whether to schedule", questions.any { it.contains("اعرض محفظة Sandbox") })
        assertTrue("unexpected answer: ${r.result}", r.result.contains("جُدولت"))
        val item = Hub.scheduler.items.value.single()
        assertEquals("the time phrase must be taken out of the task", "اعرض محفظة Sandbox", item.task)
        assertTrue("the time is wrong: ${item.at - System.currentTimeMillis()} ms from now", item.at - System.currentTimeMillis() in 2_000L..9_000L)

        // Nothing happens until then; at its time the task starts by itself and runs like any other
        FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Wallet"},{"type":"done","text":"عرضتُ المحفظة في موعدها."}]""")
        val fired = await(r.id, "the scheduled task", true, 60_000)
        dump("after the scheduled task")
        assertEquals(TaskStatus.DONE, fired.status)
        assertEquals("اعرض محفظة Sandbox", fired.task)
        assertTrue("it started ${item.at - fired.startedAt} ms before its time", fired.startedAt >= item.at - 500)
        assertTrue("the run does not say it was scheduled", fired.logs.any { it.text.contains("مجدولة") })
        assertNotNull("the Wallet page is not showing", find("Buy BTC"))
        assertTrue("a task that ran must leave the list", Hub.scheduler.items.value.isEmpty())

        // "Run now": the whole task goes ahead at once, and nothing is kept for later
        goHome()
        FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Profile"},{"type":"done","text":"فتحتُ الملف الشخصي."}]""")
        val now = run("بعد ساعة افتح ملف Sandbox الشخصي", allow = false)
        assertEquals(TaskStatus.DONE, now.status)
        assertNotNull("the task did not run at once", byId("bio"))
        assertTrue(Hub.scheduler.items.value.isEmpty())

        // A scheduled task can be cancelled
        val later = Hub.scheduler.add("افتح الكاميرا", System.currentTimeMillis() + 3600_000)
        assertEquals(1, Hub.scheduler.items.value.size)
        Hub.scheduler.cancel(later.id)
        assertTrue(Hub.scheduler.items.value.isEmpty())
    }

    @Test
    fun t23_anOldDraftIsNeverSentUnasked() {
        // What happened on a real phone: an unsent text was still in the chat's input field,
        // and a later task pressed Send on it instead of writing its own message.
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Contacts"},{"type":"tap","find":"Noor"},
                {"type":"type","find":"Message","text":"مسودة قديمة"},{"type":"done","text":"كتبتُها ولم أرسلها."}]"""
        )
        assertEquals(TaskStatus.DONE, run("اكتب مسودة قديمة عند Noor في Sandbox دون إرسال").status)

        goHome()
        // A new task. The model finds the field already filled and presses Send…
        FakeModel.plans += plan(
            """[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Contacts"},{"type":"tap","find":"Noor"},
                {"type":"tap","find":"Send"},{"type":"done","text":"أرسلتُ الرسالة."}]""",
            sensitive = true,
        )
        // …and, once told that it is not its text, writes the real message.
        FakeModel.plans += plan(
            """[{"type":"type","find":"Message","text":"هلا"},{"type":"tap","find":"Send"},{"type":"done","text":"أرسلتُ «هلا»."}]""",
            sensitive = true,
        )
        val r = run("حيِّ Noor داخل Sandbox", answer = { q -> !q.contains("لم أكتبه") })
        dump("after the old draft was refused")
        assertTrue("the owner was not shown the old draft", questions.any { it.contains("لم أكتبه") && it.contains("مسودة قديمة") })
        assertTrue("the approval must show the text that goes out", questions.any { it.contains("النص: «هلا»") })
        assertTrue("the model was not told why nothing was sent", r.logs.any { it.text.contains("لم يُرسل النص القديم") })
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("only the real message may go out", "messages sent to Noor: 1", byId("sent_count")?.label)
        assertEquals("sent: هلا to Noor", status())
    }

    @Test
    fun t24_aWebPageIsReadWholeInOneRequest() {
        // Thirty results, one long line each, the cheapest far below the screen. Scrolling to it screen by screen
        // would cost a request per screen; reading the page costs none.
        FakeModel.learn = JSONObject().put("title", "x").put("pattern", "x").put("params", JSONArray()).put("readout", true).put("reusable", false)
        FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Flights"},{"type":"read"}]""")
        FakeModel.plans += plan("""[{"type":"done","text":"أرخص رحلة: FlyErbil بسعر 41 OMR ذهاباً وعودة."}]""")
        val before = FakeModel.calls
        val r = run("ما أرخص رحلة في صفحة Flights في Sandbox")
        dump("the web page")
        val shown = FakeModel.lastUser
        val page = shown.substringAfter("PAGE TEXT", "")
        note("page text handed to the model: ${page.length} characters, ${page.lines().size} lines")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("opening the page and reading it is one request, answering is the second", 2, FakeModel.calls - before)
        assertTrue("the model was not given the page text", page.isNotEmpty())
        assertTrue("the result far below the screen is missing from the page text", page.contains("FlyErbil") && page.contains("41 OMR round trip"))
        assertTrue("the end of the page is missing", page.contains("End of results"))
        assertTrue("a long result line was cut short", page.contains("1 stop in Dubai, 2 h 40 min between flights"))
        assertTrue("the model was not told today's date", shown.contains("NOW: 20"))
        val s = screen()
        assertTrue("the page was not recognised as a web page", s.isWeb)
        assertTrue("web results are still cut at 50 characters on screen", s.nodes.any { it.web && it.label.length > 80 })
    }

    @Test
    fun t25_aTaskThatRunsOutOfRequestsStillAnswers() {
        val s = Hub.settings
        s.maxSteps = 3
        try {
            // The model wanders and never says "done" — the results are on the page all the same
            FakeModel.finish = JSONObject().put("status", "done").put("text", "أرخص رحلة ظاهرة: FlyErbil بسعر 41 OMR.")
            FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Flights"},{"type":"wait"}]""")
            FakeModel.plans += plan("""[{"type":"scroll","dir":"down"}]""")
            var r = run("افتح Flights في Sandbox وابحث عن أرخص رحلة")
            assertEquals("the last request must give the answer that is on the page", TaskStatus.DONE, r.status)
            assertTrue(r.result.contains("41 OMR"))
            assertEquals("the conclusion is the last allowed request, not an extra one", 3, r.calls)
            assertTrue("the conclusion was not given the page", FakeModel.lastFinishUser.contains("PAGE TEXT") && FakeModel.lastFinishUser.contains("FlyErbil"))

            // And when even that gives nothing, the owner is told plainly
            FakeModel.finish = null
            FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Flights"},{"type":"wait"}]""")
            FakeModel.plans += plan("""[{"type":"scroll","dir":"down"}]""")
            r = run("افتح Flights في Sandbox وابحث عن أرخص رحلة")
            assertEquals(TaskStatus.FAILED, r.status)
            assertTrue(r.result.contains("الحد الأقصى"))
        } finally {
            s.maxSteps = 6
            FakeModel.finish = null
        }
    }

    /** A market that goes where the test says: a steady climb, then (when told) a jump. */
    private class ScriptedMarket : MarketSource {
        @Volatile var jump = 0.0
        @Volatile var hoursLater = 0
        @Volatile var asked = 0

        override suspend fun candles(symbol: String, interval: String, limit: Int): List<Candle> {
            asked++
            val step = Analysis.minutes(interval) * 60_000L
            val end = System.currentTimeMillis() / step * step
            val out = ArrayList<Candle>()
            for (i in 0 until limit) {
                val c = 100.0 * (1 + 0.001 * i)
                out += Candle(end - (limit - 1 - i) * step, c * 0.999, c * 1.002, c * 0.998, c, 50.0)
            }
            // Later candles, after the trade was opened
            var last = out.last().close
            for (k in 1..hoursLater) {
                val c = last * (1 + jump / hoursLater)
                out += Candle(end + k * step, last, maxOf(last, c) * 1.001, minOf(last, c) * 0.999, c, 80.0)
                last = c
            }
            return out
        }

        override suspend fun headlines(query: String): List<String> = listOf("Bitcoin climbs as markets steady - Example News")
    }

    @Test
    fun t26_tradingModeDecidesAndExecutesOnItsPaperAccount() {
        val s = Hub.settings
        val market = ScriptedMarket()
        MarketSource.override = market
        s.tradeAuto = false
        s.tradeSymbols = "BTCUSDT"
        s.tradeInterval = "1h"
        s.tradeNews = true
        try {
            s.tradeStart = 10_000f
            runBlocking { Hub.trader.resetNow() }
            assertTrue(Hub.trader.state.value.let { it.cash == 10_000.0 && it.positions.isEmpty() && it.journal.isEmpty() })
            val price = 100.0 * (1 + 0.001 * 149)

            // Cycle 1 — the model wants to buy, with a stop far beyond what the owner allows
            FakeModel.trade = JSONObject().put("outlook", "السوق في اتجاه صاعد").put(
                "decisions", JSONArray().put(
                    JSONObject().put("symbol", "BTCUSDT").put("action", "buy").put("sl", price * 0.80).put("tp", price * 1.04)
                        .put("confidence", 82).put("reason", "السعر فوق المتوسطين والاتجاه الأعلى صاعد"),
                ),
            )
            val calls = FakeModel.tradeCalls
            val line = runBlocking { Hub.trader.cycle() }
            var st = Hub.trader.state.value
            note("cycle 1: $line")
            st.journal.forEach { note("     · ${it.symbol} ${it.action} (${it.confidence}) — ${it.outcome}") }
            assertEquals("one request per cycle", 1, FakeModel.tradeCalls - calls)
            val p = st.positions.single()
            assertEquals(Side.LONG, p.side)
            assertEquals("the stop was pulled in to the 3% limit", price * 0.97, p.sl, 1e-6)
            assertEquals("the position is capped at 2000 USDT", 2000.0, p.qty * p.entry, 1e-6)
            assertEquals(price, p.entry, 1e-9)
            val shown = FakeModel.lastTradeUser
            for (part in listOf("NOW: 20", "ACCOUNT: equity 10,000.00", "MARKET:", "BTCUSDT (1h) price", "RSI14", "4h trend: up", "HEADLINES", "Bitcoin climbs")) {
                assertTrue("the model was not shown «$part»", shown.contains(part))
            }

            // Cycle 2 — hours later the market is 6% higher: the target was reached, whatever the model says now
            market.hoursLater = 3
            market.jump = 0.06
            FakeModel.trade = JSONObject().put("outlook", "").put("decisions", JSONArray().put(JSONObject().put("symbol", "BTCUSDT").put("action", "hold").put("confidence", 50).put("reason", "انتظار")))
            note("cycle 2: " + runBlocking { Hub.trader.cycle() })
            st = Hub.trader.state.value
            st.journal.take(2).forEach { note("     · ${it.symbol} ${it.action} — ${it.outcome}") }
            assertTrue("the position must be closed at its target", st.positions.isEmpty())
            val t = st.closed.first()
            assertEquals("tp", t.why)
            assertEquals(price * 1.04, t.exit, 1e-6)
            assertTrue("a trade closed at its target is a profit", t.pnl > 70 && st.equity > 10_070)
            assertEquals(st.cash, st.equity, 1e-9)

            // Cycle 3 — a decision below the owner's minimum confidence opens nothing
            FakeModel.trade = JSONObject().put("outlook", "").put("decisions", JSONArray().put(JSONObject().put("symbol", "BTCUSDT").put("action", "sell").put("confidence", 30).put("reason", "ظن")))
            runBlocking { Hub.trader.cycle() }
            assertTrue(Hub.trader.state.value.positions.isEmpty())

            // The account survives a restart of the app
            val again = Trader(ctx, s).state.value
            assertEquals(1, again.closed.size)
            assertEquals(st.cash, again.cash, 1e-9)
            assertTrue(again.journal.size >= 3)

            // And the owner sees it
            svc().startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(waitUntil(15_000) { screen().pkg == ctx.packageName })
            val tab = find("التداول", 8000)
            assertNotNull("the trading tab is missing", tab)
            touch(tab!!)
            assertTrue("the paper account is not shown", waitUntil(8000) { screen().nodes.any { it.label.contains("الحساب التجريبي") } })
            dump("Wakeel · التداول")
            goHome()
        } finally {
            MarketSource.override = null
            FakeModel.trade = null
            runBlocking { Hub.trader.resetNow() }
        }
    }

    @Test
    fun t27_liveMarketDataCanBeRead() {
        // Not a pass/fail of the app: the emulator's network decides. The report says whether real prices arrived.
        val r = runCatching { runBlocking { LiveMarket.candles("BTCUSDT", "1h", 60) } }
        val c = r.getOrNull()
        note(if (c != null) "live market data: ${c.size} candles, last BTCUSDT close ${c.last().close}" else "live market data: NOT reachable from the emulator (${r.exceptionOrNull()})")
        val n = runCatching { runBlocking { LiveMarket.headlines("bitcoin market") } }.getOrNull()
        note("live headlines: " + (n?.take(2)?.joinToString(" | ") ?: "NOT reachable"))
        if (c != null) assertTrue(c.size >= 30 && c.all { it.high >= it.low && it.close > 0 })
    }

    @Test
    fun t18_lockedScreenPausesAndRunningTaskKeepsScreenOn() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        fun wake() {
            shell("input keyevent 224")   // wake up
            shell("wm dismiss-keyguard")
            waitUntil(10_000) { pm.isInteractive && !km.isKeyguardLocked }
            Thread.sleep(1000)
        }
        try {
            // 1) The screen is off: the task waits — no model call on a dark screen — and carries on once it is back
            shell("input keyevent 223")   // sleep
            assertTrue("could not turn the emulator screen off", waitUntil(10_000) { !pm.isInteractive })
            FakeModel.plans += plan("""[{"type":"open_app","text":"Sandbox"},{"type":"tap","find":"Contacts"},{"type":"done","text":"فتحتُ جهات الاتصال."}]""")
            val before = Hub.history.records.value.firstOrNull()?.id
            val asked = FakeModel.calls
            note("▶ TASK (screen off): اعرض جهات الاتصال التجريبية")
            Hub.engine.start("اعرض جهات الاتصال التجريبية")
            Thread.sleep(8000)
            note("while the screen is off: running=${Hub.engine.isRunning} · ${Hub.engine.state.value} · model calls=${FakeModel.calls - asked}")
            assertTrue("the task should be waiting, not over", Hub.engine.isRunning)
            assertEquals("no model call may be spent on a dark screen", asked, FakeModel.calls)
            wake()
            val resumed = await(before, "resume after the screen came back", true, 90_000)
            assertEquals(TaskStatus.DONE, resumed.status)
            assertTrue("the pause was not explained", resumed.logs.any { it.text.contains("مطفأة") })
            assertNotNull("the task did not carry on after the screen came back", find("Ahmed"))

            // 2) Nobody touches the phone: on its own the screen times out, but not while a task is running
            shell("settings put global stay_on_while_plugged_in 0")
            shell("settings put system screen_off_timeout 10000")
            goHome()
            Thread.sleep(17_000)
            val timesOut = !pm.isInteractive
            note("idle emulator screen turns itself off after the timeout: $timesOut")
            wake()
            FakeModel.plans += plan(
                """[{"type":"open_app","text":"Sandbox"},{"type":"wait"},{"type":"wait"},{"type":"wait"},{"type":"wait"},{"type":"wait"},
                    {"type":"wait"},{"type":"wait"},{"type":"wait"},{"type":"tap","find":"Wallet"},{"type":"done","text":"فتحتُ المحفظة بعد انتظار طويل."}]"""
            )
            val before2 = Hub.history.records.value.firstOrNull()?.id
            val started = SystemClock.uptimeMillis()
            note("▶ TASK (nobody touching the phone): انتظر طويلاً ثم اعرض المحفظة التجريبية")
            Hub.engine.start("انتظر طويلاً ثم اعرض المحفظة التجريبية")
            var wentDark = false
            waitUntil(100_000, 400) {
                if (!pm.isInteractive) wentDark = true
                val top = Hub.history.records.value.firstOrNull()
                top != null && top.id != before2 && !Hub.engine.isRunning
            }
            val long = await(before2, "long task", true, 5_000)
            note("the task ran for ${(SystemClock.uptimeMillis() - started) / 1000}s with nobody touching the phone · screen went dark: $wentDark")
            assertEquals(TaskStatus.DONE, long.status)
            assertTrue("the control did not work: the emulator screen never times out, so this proves nothing", timesOut)
            assertFalse("the screen turned itself off in the middle of a task", wentDark)
        } finally {
            shell("settings put system screen_off_timeout 1800000")
            wake()
        }
    }

    // ------------------------------------------------------------------ the phone's own Settings app

    @Test
    fun t13_realSettingsRouteIsLearnedThenFree() {
        Hub.maps.clear(SETTINGS_APP)
        FakeModel.plans += plan("""[{"type":"tap","find":"Network & internet|Network and internet|Connections"},{"type":"done","text":"فتحتُ صفحة الشبكة."}]""")
        val task = "ادخل قسم الشبكة والإنترنت من الإعدادات"
        val first = run(task)
        dump("settings · network")
        assertEquals(TaskStatus.DONE, first.status)
        assertEquals(SETTINGS_APP, screen().pkg)
        note("settings map:\n" + Hub.maps.describe(SETTINGS_APP))
        assertNotNull("the row must be remembered by its title", Hub.maps.findPath(SETTINGS_APP, "Network & internet"))

        goHome()
        FakeModel.plans += plan("""[{"type":"done","text":"فتحتُ صفحة الشبكة."}]""")
        val second = run(task)
        assertEquals(TaskStatus.DONE, second.status)
        assertEquals(1, second.calls)

        goHome()
        val third = run(task)
        assertEquals(TaskStatus.DONE, third.status)
        assertEquals(0, third.calls)

        goHome()
        val byMap = run("افتح Network & internet في الإعدادات")
        assertEquals(TaskStatus.DONE, byMap.status)
        assertEquals(0, byMap.calls)
    }

    @Test
    fun t14_switchesAreFlippedLocally() {
        // A settings page the agent can read: turn location off, then back on, without the model
        val off = run("أطفئ الموقع")
        dump("location page")
        assertEquals(TaskStatus.DONE, off.status)
        assertEquals(0, off.calls)
        assertTrue("unexpected answer: ${off.result}", off.result.contains("إيقاف") || off.result.contains("متوقف"))
        goHome()
        val on = run("شغل الموقع")
        assertEquals(TaskStatus.DONE, on.status)
        assertEquals(0, on.calls)
        assertTrue("unexpected answer: ${on.result}", on.result.contains("تشغيل"))
    }

    @Test
    fun t15_pagesAndroidHidesAreReportedNotGuessed() {
        // On Android 14 the Wi‑Fi page is hidden from every service that is not a disability aid.
        // Either the switch is handled (readable page) or the owner is told plainly — never a model call on a blank page.
        val r = run("شغل الواي فاي")
        dump("wifi page")
        assertEquals(0, r.calls)
        assertTrue("unexpected outcome: ${r.status} ${r.result}", r.status == TaskStatus.DONE || r.result.contains("يحجب"))
    }
}
