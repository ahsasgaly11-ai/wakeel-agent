package com.wakeel.agent.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wakeel.agent.core.Policy
import com.wakeel.agent.core.Settings
import com.wakeel.agent.core.Text
import com.wakeel.agent.core.Usage
import com.wakeel.agent.service.AgentAccessibilityService
import com.wakeel.agent.service.ScreenReader
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pure logic of learning by demonstration (unit-tested on CI). */
object Demo {
    private const val EDGE = ":،,.!?؟;«»\"'()[]"

    /**
     * Turns the phrase the owner chose into a pattern: any typed text or picked list item that literally
     * appears in the phrase becomes a {slot}, so the skill works with new values later. No model involved.
     */
    fun generalize(task: String, steps: List<MacroStep>, picked: Collection<String>): Learner.Learned {
        val values = LinkedHashSet<String>()
        steps.filter { it.type == "type" }.mapNotNull { it.text?.trim() }.filter { it.length >= 2 }.forEach { values += it }
        picked.map { it.trim() }.filter { it.length >= 2 }.forEach { values += it }

        // Whole words of the phrase, without the punctuation that hugs them ("أحمد:" → "أحمد")
        val words = Regex("\\S+").findAll(task).mapNotNull { m ->
            var a = m.range.first
            var b = m.range.last
            while (a <= b && task[a] in EDGE) a++
            while (b >= a && task[b] in EDGE) b--
            if (a <= b) a..b else null
        }.toList()
        val hits = ArrayList<IntRange>()
        for (v in values.sortedByDescending { it.length }) {
            val nv = Text.norm(v.trim { it in EDGE })
            if (nv.length < 2) continue
            search@ for (i in words.indices) {
                for (j in i until minOf(words.size, i + 8)) {
                    val range = words[i].first..words[j].last
                    if (range.last - range.first + 1 > v.length + 12) break
                    // Compared after normalization, so "احمد" in the phrase still matches the row "أحمد"
                    if (Text.norm(task.substring(range.first, range.last + 1)) != nv) continue
                    if (hits.none { range.first <= it.last && range.last >= it.first }) hits += range
                    break@search
                }
            }
        }
        hits.sortBy { it.first }
        val params = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var pos = 0
        hits.forEachIndexed { idx, h ->
            val name = "p${idx + 1}"
            sb.append(task, pos, h.first).append('{').append(name).append('}')
            params[name] = task.substring(h.first, h.last + 1)
            pos = h.last + 1
        }
        sb.append(task, pos, task.length)
        return Learner.Learned(task.take(40), sb.toString(), params, false, true, emptyList(), Usage())
    }

    /**
     * Why a demonstration looks unfinished, or null if nothing says so. Used when a skill is saved by itself:
     * an unfinished one is dropped instead of kept. It can only judge what is visible in the steps — a task
     * the owner stopped one tap early looks finished here.
     */
    fun incomplete(task: String, steps: List<MacroStep>): String? {
        val acts = steps.filter { it.type != "open_app" }
        if (acts.isEmpty()) return "لم تنفّذ أي خطوة داخل التطبيق"
        // Text left in a field with nothing pressed after it: a message that was never sent, a form never saved
        if (acts.last().type == "type") return "كتبتَ نصاً ولم ترسله أو تحفظه"
        // "…: النص" in the phrase promises a text; if nothing like it was typed, the task stopped before that
        val promised = Regex("[:：]\\s+(\\S.*)$").find(task.trim())?.groupValues?.get(1)?.trim { it in EDGE }.orEmpty()
        if (promised.length >= 2 && promised.any { it.isLetter() }) {
            val want = Text.norm(promised)
            val typed = steps.filter { it.type == "type" }.map { Text.norm(it.text.orEmpty()) }.filter { it.isNotEmpty() }
            if (typed.none { it.contains(want) || want.contains(it) }) return "النص الذي في جملتك («${promised.take(30)}») لم يُكتب"
        }
        return null
    }
}

/**
 * Learning by demonstration: the owner performs a task by hand once, and it becomes a skill
 * that replays locally with zero API calls. Records which app was opened, what was tapped and what was typed.
 * Never records passwords, the keyboard, the home screen, or anything in blocked apps.
 */
class DemoRecorder(
    private val ctx: Context,
    private val settings: Settings,
    private val skills: SkillStore,
    private val apps: AppIndex,
) {
    data class State(val active: Boolean = false, val task: String = "", val steps: Int = 0, val last: String = "")

    /** How a recording that ended by itself turned out: the saved skill, or why nothing was saved. */
    data class Outcome(val skill: Skill?, val reason: String?)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val active: Boolean get() = _state.value.active

    private val _outcome = MutableSharedFlow<Outcome>(extraBufferCapacity = 4)
    val outcome: SharedFlow<Outcome> = _outcome.asSharedFlow()

    @Volatile
    var lastOutcome: Outcome? = null
        private set

    // Saving by itself: once the owner is back on the home screen and it has been still for a moment
    private val main = Handler(Looper.getMainLooper())
    private val autoSave = Runnable { finishByItself() }

    private val steps = ArrayList<MacroStep>()
    private val picked = LinkedHashSet<String>()
    private val visited = HashSet<String>()
    private var task = ""
    private var lastPkg = ""
    private var imePkg = ""
    private var pendingSig: String? = null
    private var pendingText: String? = null
    private var pendingSearch = false
    private var lastTypeWasSearch = false

    /** Nothing is recorded before this moment: the phone is still returning to the home screen. */
    private var armedAt = 0L

    /** What was seen during the last recording, for troubleshooting (kept in memory only). */
    val trace = ArrayList<String>()

    private fun note(line: String) {
        if (trace.size < 80) trace += line
    }

    @Synchronized
    fun start(phrase: String): Boolean {
        val svc = AgentAccessibilityService.instance ?: return false
        if (phrase.isBlank() || active) return false
        lastOutcome = null
        steps.clear()
        picked.clear()
        visited.clear()
        trace.clear()
        task = phrase.trim()
        lastPkg = ""
        pendingSig = null
        pendingText = null
        lastTypeWasSearch = false
        imePkg = runCatching {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty().substringBefore('/')
        _state.value = State(true, task, 0, "افتح التطبيق ونفّذ المهمة بيدك")
        // Always begin from the home screen, the place a replay begins from
        armedAt = SystemClock.uptimeMillis() + 1300
        svc.setFastEvents(true)
        Handler(Looper.getMainLooper()).postDelayed({ svc.global(AccessibilityService.GLOBAL_ACTION_HOME) }, 500)
        return true
    }

    private fun publish(last: String) {
        _state.value = State(true, task, steps.size, last)
    }

    private fun flushText() {
        val t = pendingText
        if (!t.isNullOrBlank()) {
            steps += MacroStep("type", pendingSig, text = t)
            note("type «${t.take(30)}» in $pendingSig")
            lastTypeWasSearch = pendingSearch
            publish("كتابة «${t.take(24)}»")
        }
        pendingSig = null
        pendingText = null
        pendingSearch = false
    }

    private fun inList(n: AccessibilityNodeInfo?): Boolean {
        var cur: AccessibilityNodeInfo? = runCatching { n?.parent }.getOrNull()
        repeat(10) {
            val c = cur ?: return false
            val cls = c.className?.toString().orEmpty()
            if (c.isScrollable && !(cls.endsWith("ScrollView") || cls.contains("Pager"))) return true
            cur = runCatching { c.parent }.getOrNull()
        }
        return false
    }

    /**
     * Notes that the owner moved to [pkg]. On the first visit ([restart]) the app is restarted on its
     * first page, because that is where a replay will start; a later return is replayed as a plain switch.
     */
    private fun enterApp(pkg: String, restart: Boolean) {
        if (pkg == lastPkg) return
        val app = apps.byPackage(pkg) ?: return   // helper screens (share sheet, pickers) are not apps to open
        flushText()
        val first = visited.add(pkg)
        steps += MacroStep("open_app", text = pkg, label = app.label, dir = if (first) null else "resume")
        note("app $pkg first=$first restart=$restart")
        lastPkg = pkg
        lastTypeWasSearch = false
        publish(if (first) "فتح ${app.label} من صفحته الأولى" else "العودة إلى ${app.label}")
        if (first && restart) apps.launch(pkg, fresh = true)
    }

    @Synchronized
    fun onEvent(e: AccessibilityEvent) {
        if (!active || steps.size >= 40 || SystemClock.uptimeMillis() < armedAt) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == ctx.packageName || pkg == "com.android.systemui" || (imePkg.isNotEmpty() && pkg == imePkg)) return
        val launcher = pkg in apps.launcherPackages()
        if (!launcher && Safety.policy(pkg, settings, apps) == Policy.BLOCKED) return

        if (launcher) {
            // Back on the home screen. If something was done already, the task is probably over: save after a
            // still moment. Browsing the home screen (looking for another app) keeps postponing that.
            val moved = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                flushText()
                lastPkg = ""
            }
            if (moved && settings.autoSaveTaught && steps.any { it.type != "open_app" }) {
                main.removeCallbacks(autoSave)
                main.postDelayed(autoSave, AUTO_SAVE_MS)
                publish("عدتَ إلى الشاشة الرئيسية: سأحفظ المهارة بعد لحظات")
            }
            return
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            main.removeCallbacks(autoSave)   // still working: another app came up
            enterApp(pkg, restart = true)
            return
        }
        val isClick = e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
        val isText = e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        if (!isClick && !isText) return
        main.removeCallbacks(autoSave)

        val src: AccessibilityNodeInfo? = runCatching { e.source }.getOrNull()
        if (runCatching { src?.window?.type }.getOrNull() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return
        val cls = (src?.className ?: e.className)?.toString()?.substringAfterLast('.').orEmpty()
        val viewId = src?.viewIdResourceName?.substringAfter(":id/").orEmpty()

        if (isText) {
            if (e.isPassword || src?.isPassword == true) {
                pendingSig = null
                pendingText = null
                return
            }
            enterApp(pkg, restart = false)
            val text = if (src != null) {
                if (src.isShowingHintText) "" else src.text?.toString().orEmpty()
            } else {
                e.text?.joinToString("") { it.toString() }.orEmpty()
            }
            val sig = "$cls|$viewId|"
            if (pendingSig != null && pendingSig != sig) flushText()   // moved on to another field
            pendingSig = sig
            pendingText = text
            pendingSearch = Safety.isSearchField(src?.hintText?.toString().orEmpty() + " " + src?.contentDescription?.toString().orEmpty(), viewId)
            return
        }

        if (src?.isEditable == true) return   // tapping a text field only focuses it; the typing step does that itself
        flushText()
        enterApp(pkg, restart = false)
        // The element's label exactly as the agent reads it on screen ("Title · detail"). By the time the event
        // arrives its page may be closing, so hidden children are read too; the event's own texts are the last resort.
        // Two sources: the element as the agent reads it on screen, and the texts the event itself carries.
        // The tapped page is often already closing, which leaves the first one incomplete — the fuller one wins.
        val texts = e.text?.map { it.toString().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val fromTree = src?.let { ScreenReader.labelOf(it, evenIfHidden = true) }.orEmpty().trim()
        val fromEvent = e.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: texts.take(3).joinToString(" · ")
        val full = (if (fromTree.length >= fromEvent.length) fromTree else fromEvent).take(50)
        if (full.isBlank() && viewId.isBlank()) return   // nothing to find it by later
        // Remembered by its title: the details beside it (last message, a count) change every time
        val label = full.substringBefore(" · ").trim().take(40)
        // A row of a list, or anything with details beside its title, may be "the one named in the phrase"
        val row = label != full || inList(src)
        if (label.length >= 2 && row) picked += label
        note("tap «$label» full=«$full» tree=«$fromTree» event=$texts id=$viewId class=$cls source=${src != null} row=$row")
        val type = if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) "tap" else "long_press"
        steps += MacroStep(
            type, "$cls|$viewId|${full.take(60)}",
            label = label.ifBlank { "#$viewId" }, confirm = Safety.isSensitiveLabel(label),
        )
        lastTypeWasSearch = false
        publish("${if (type == "tap") "ضغط" else "ضغط مطوّل"} على «${label.ifBlank { viewId }.take(24)}»")
    }

    /** The steps as they will be saved. */
    private fun finalRoute(): MutableList<MacroStep> {
        flushText()
        val route = steps.toMutableList()
        // A search typed last was submitted from the keyboard, which cannot be observed: press enter on replay
        if (route.isNotEmpty() && route.last().type == "type" && lastTypeWasSearch) route += MacroStep("enter")
        return route
    }

    /** Ends the recording. Returns the saved skill, or null if it was cancelled or nothing was recorded. */
    @Synchronized
    fun stop(save: Boolean): Skill? {
        if (!active) return null
        main.removeCallbacks(autoSave)
        val route = finalRoute()
        _state.value = State()
        AgentAccessibilityService.instance?.setFastEvents(false)
        // Opening an app and doing nothing in it is not a skill ("افتح …" already does that for free)
        if (!save || route.none { it.type != "open_app" }) return null
        val pkg = route.lastOrNull { it.type == "open_app" }?.text.orEmpty()
        val learned = Demo.generalize(task, route, picked)
        val skill = Learner.newSkill(task, learned, route, "تمّت «${task.take(40)}» بالمهارة التي علّمتَني إياها.", pkg)
            .copy(successes = 2, taught = true)   // taught by the owner: trusted from its first run
        skills.put(skill)
        return skill
    }

    /** The owner went back to the home screen and stayed there: save the skill — unless it looks unfinished. */
    @Synchronized
    private fun finishByItself() {
        if (!active) return
        val reason = Demo.incomplete(task, finalRoute())
        val skill = stop(save = reason == null)
        val outcome = Outcome(skill, if (skill != null) null else reason ?: "لم تُسجَّل أي خطوة")
        lastOutcome = outcome
        _outcome.tryEmit(outcome)
    }

    private companion object {
        const val AUTO_SAVE_MS = 4000L
    }
}
