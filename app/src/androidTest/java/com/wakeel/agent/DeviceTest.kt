package com.wakeel.agent

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.wakeel.agent.agent.EngineState
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
import com.wakeel.agent.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private const val NO_PLAN = """{"thought":"لا خطة","actions":[{"type":"fail","text":"انتهت الخطط المجهّزة للاختبار."}],"sensitive":false}"""

    override suspend fun callTool(
        model: String, system: String, user: String, shot: Shot?, tool: ToolSpec, maxTokens: Int, extraSystem: String?,
    ): ToolResult {
        Log.i(TAG, "   ◇ model asked (${tool.name}): " + user.lineSequence().firstOrNull().orEmpty().take(120))
        return when (tool.name) {
            "act" -> {
                lastKnowledge = extraSystem
                val p = synchronized(plans) { plans.removeFirstOrNull() } ?: NO_PLAN
                ToolResult(JSONObject(p), Usage(900, 60))
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

    private fun await(before: Long?, what: String, allow: Boolean, timeoutMs: Long): TaskRecord {
        confirmed = false
        val finished = waitUntil(timeoutMs, 150) {
            if (Hub.engine.state.value is EngineState.Confirm) {
                confirmed = true
                Hub.engine.answerConfirm(allow)
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

    private fun run(task: String, allow: Boolean = true, timeoutMs: Long = 120_000): TaskRecord {
        val before = Hub.history.records.value.firstOrNull()?.id
        note("▶ TASK: $task")
        Hub.engine.start(task)
        return await(before, task, allow, timeoutMs)
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
        val expect = linkedMapOf("المعرفة" to "التطبيقات", "السجل" to "لا توجد مهام بعد", "الإعدادات" to "مزوّد الذكاء الاصطناعي", "القيادة" to "ماذا أنجز لك؟")
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

        // Third time: no model at all — and the contact is far down the list, so it has to scroll to find it
        val third = run("أرسل في Sandbox إلى Saeed: تمام")
        dump("after the free replay")
        assertEquals(TaskStatus.DONE, third.status)
        assertEquals("sent: تمام to Saeed", status())
        assertEquals("a trusted skill must not call the model", 0, third.calls)
        assertEquals("أرسلتُ «تمام» إلى Saeed.", third.result)

        // Saying no to the confirmation stops the task: nothing is sent, and no model call is spent on another way
        val before = Hub.skills.skills.value.single()
        val denied = run("أرسل في Sandbox إلى Layla: لا ترسل", allow = false)
        note("denied run: ${denied.status} · ${status()}")
        assertFalse("a denied message was sent anyway", status().contains("لا ترسل"))
        assertEquals(TaskStatus.STOPPED, denied.status)
        assertEquals(0, denied.calls)
        assertEquals("a refusal is not a failure of the skill", before.fails, Hub.skills.skills.value.single().fails)
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
        val skill = Hub.recorder.stop(save = true)
        assertNotNull("nothing was recorded", skill)
        note("taught: ${skill!!.pattern} · ${skill.steps.joinToString(" → ") { it.type + ":" + (it.label ?: it.text.orEmpty()) }}")
        assertEquals("راسل {p1} وقل له {p2}", skill.pattern)
        assertTrue("a taught skill is trusted from the start", skill.trusted)

        goHome()
        val r = run("راسل Sara وقل له salam")
        dump("after replaying the taught skill")
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals("a taught skill must not call the model", 0, r.calls)
        assertEquals("sent: salam to Sara", status())
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
    fun t14_wifiSwitchIsHandledLocally() {
        val r = run("شغل الواي فاي")
        dump("wifi page")
        if (r.status != TaskStatus.DONE) {
            Thread.sleep(4000)
            dump("wifi page, four seconds later")
        }
        assertEquals(TaskStatus.DONE, r.status)
        assertEquals(0, r.calls)
    }
}
