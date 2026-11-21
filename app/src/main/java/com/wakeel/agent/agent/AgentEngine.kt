package com.wakeel.agent.agent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.Toast
import com.wakeel.agent.core.ActionType
import com.wakeel.agent.core.AgentAction
import com.wakeel.agent.core.DeviceInfo
import com.wakeel.agent.core.LogEntry
import com.wakeel.agent.core.LogKind
import com.wakeel.agent.core.Policy
import com.wakeel.agent.core.Settings
import com.wakeel.agent.core.Shot
import com.wakeel.agent.core.TaskRecord
import com.wakeel.agent.core.TaskStatus
import com.wakeel.agent.core.Text
import com.wakeel.agent.core.Usage
import com.wakeel.agent.llm.LlmClient
import com.wakeel.agent.llm.Prompt
import com.wakeel.agent.service.AgentAccessibilityService
import com.wakeel.agent.service.Screen
import com.wakeel.agent.service.ScreenReader
import com.wakeel.agent.service.UiNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface EngineState {
    data object Idle : EngineState
    data class Running(val line: String, val step: Int, val max: Int) : EngineState
    /**
     * A yes/no question to the owner. [canAlways]: the step belongs to a saved skill, so it may be allowed once
     * and for all. [yes]/[no] are the two answers as shown (a permission by default; "schedule / run now" otherwise).
     */
    data class Confirm(
        val question: String, val canAlways: Boolean = false,
        val yes: String = "سماح", val no: String = "رفض", val title: String = "مطلوب موافقتك",
    ) : EngineState
    data class Ask(val question: String) : EngineState
}

data class LiveStats(val calls: Int = 0, val cost: Double = 0.0, val usage: Usage = Usage(), val localSteps: Int = 0)

/**
 * Runs a task with the cheapest means that works:
 *   learned skill (free) → built-in local command (free) → one planning call that uses built-in app
 *   knowledge + the map learned on this phone to drive several screens at once → learn the result.
 */
class AgentEngine(
    private val ctx: Context,
    private val settings: Settings,
    private val history: HistoryStore,
    private val skills: SkillStore,
    private val notes: AppNotes,
    private val apps: AppIndex,
    private val maps: AppMapStore,
    /** Where a task that names a later time is kept until then. */
    private val scheduler: Scheduler? = null,
    /** True while the owner is demonstrating a task by hand — the agent must not act then. */
    private val isRecording: () -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _live = MutableStateFlow(LiveStats())
    val live: StateFlow<LiveStats> = _live.asStateFlow()

    val lastResult = MutableStateFlow<String?>(null)

    @Volatile private var confirmGate: CompletableDeferred<Boolean>? = null
    @Volatile private var confirmAlways = false
    @Volatile private var askGate: CompletableDeferred<String?>? = null

    val isRunning: Boolean get() = job?.isActive == true

    /** [scheduledFor]: the task was scheduled earlier and its time has come. Returns false when nothing was started. */
    fun start(task: String, delayMs: Long = 0, scheduledFor: Long? = null): Boolean {
        val t = task.trim()
        if (t.isEmpty() || isRunning) return false
        if (isRecording()) {
            // The owner is teaching a task by hand: the agent must not act on the screen at the same time
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "أنهِ تسجيل المهارة أولاً (الفقاعة الحمراء)، ثم اطلب المهمة.", Toast.LENGTH_LONG).show()
            }
            return false
        }
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            val run = Run(t, scheduledFor)
            guarded(run) { execute(run) }
        }
        return true
    }

    /** Visits an app's bottom tabs and records its pages — no API calls, nothing is typed or submitted. */
    fun explore(pkg: String) {
        if (isRunning || isRecording()) return
        job = scope.launch {
            val label = apps.byPackage(pkg)?.label ?: pkg
            val run = Run("استكشاف «$label»")
            guarded(run) { exploreApp(run, pkg, label) }
        }
    }

    fun stop() {
        confirmGate?.complete(false)
        askGate?.complete(null)
        job?.cancel()
    }

    /** [always] (with [allow]): do not ask again for this step of this saved skill. */
    fun answerConfirm(allow: Boolean, always: Boolean = false) {
        confirmAlways = allow && always
        confirmGate?.complete(allow)
    }

    fun answerAsk(text: String?) {
        askGate?.complete(text)
    }

    // ------------------------------------------------------------------ run state

    private class Pending(val pkg: String, val from: String?, val label: String, val viewId: String)

    /** What a tap is remembered as in the app's map: a list row by its title only, an icon without text by its view id. */
    private fun pendingFor(pkg: String, from: String?, n: UiNode): Pending? {
        val label = n.title
        return if (label.isBlank() && n.viewId.isEmpty()) null else Pending(pkg, from, label, n.viewId)
    }

    /** How a tapped element is named in a saved route: by its title, since the details beside it (a count, a status) change. */
    private fun stepLabel(n: UiNode): String = n.title.take(40)

    /** Texts sitting in input fields right now (real text only: no hints, no passwords). */
    private fun draftsOf(s: Screen): List<String> = s.nodes
        .filter { it.editable && !it.password && runCatching { !it.info.isShowingHintText }.getOrDefault(true) }
        .map { it.label.trim() }
        .filter { it.isNotEmpty() && it != "(password)" }

    /**
     * Where on the phone a text is being sent: the app plus the name in its top bar (a chat's contact, a page's
     * title). Icons are skipped and only the first name is used — a status line under it ("online") comes and goes.
     */
    private fun placeOf(s: Screen): String = s.pkg + "|" + s.nodes
        .firstOrNull {
            !it.inList && !it.editable && it.label.isNotBlank() && it.role != "ibtn" && it.role != "img" &&
                it.bounds.centerY() < s.height * 0.16f
        }?.let { Text.norm(it.title) }.orEmpty()

    private fun sentKey(s: Screen, text: String) = placeOf(s) + "::" + Text.norm(text)

    /**
     * After a send/post/save tap: a text that was in an input field and is no longer there went through.
     * Apps show no separate "sent" screen, so this is the confirmation — the model is told, and the same
     * text is not sent to the same place again in this task.
     */
    private suspend fun committed(svc: AgentAccessibilityService, run: Run, before: Screen, drafts: List<String>): String {
        if (drafts.isEmpty()) return ""
        svc.waitForIdle(450, 2500)
        val left = draftsOf(svc.readScreen()).map { Text.norm(it) }.toSet()
        val gone = drafts.filter { Text.norm(it) !in left }
        if (gone.isEmpty()) return ""
        gone.forEach { run.sent += sentKey(before, it) }
        run.sentShown += gone
        log(run, LogKind.INFO, "أُرسل النص «${gone.first().take(40)}»: خلا حقل الكتابة بعد الضغط")
        return " — SENT: the text left the input field, so it went through. Do not send it again; if that was the task, call done."
    }

    /** Text or body that a link pre-fills in the app it opens (wa.me/…?text=, sms:…?body=, mailto:…?body=). */
    private fun noteLinkText(run: Run, url: String) {
        val decoded = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        run.typed += Text.norm(decoded)
    }

    /**
     * True when [draft] is this task's own text: it typed it, a link it opened carried it, or the task names it.
     * Anything else found in an input field was left there earlier — by the owner or by an older task.
     */
    private fun ownText(run: Run, draft: String): Boolean {
        val d = Text.norm(draft)
        if (d.length < 2) return true
        // Typed by this task (the app may have trimmed it or added a mark), or carried by a link it opened.
        // A field holding MORE than was typed is an old draft with the new text stuck onto it: not this task's text.
        if (run.typed.any { it == d || it.contains(d) || (it.isNotEmpty() && d.contains(it) && d.length - it.length <= 2) }) return true
        // Named in the task itself, as whole words
        val words = " " + Text.norm(run.task).replace(NOT_WORD, " ").trim() + " "
        return words.contains(" " + d.replace(NOT_WORD, " ").trim() + " ")
    }

    /**
     * A send button is about to be pressed on a text this task did not write — an old draft still sitting in the
     * field. That text is never sent on the agent's own judgment: the owner sees it and decides, whatever the settings.
     * Returns null when there is no such draft, otherwise the owner's answer.
     */
    private suspend fun staleDraft(run: Run, drafts: List<String>): Pair<String, Boolean>? {
        val stale = drafts.firstOrNull { !ownText(run, it) } ?: return null
        log(run, LogKind.WARN, "في حقل الكتابة نص لم أكتبه في هذه المهمة: «${stale.take(60)}»")
        val send = choose(
            run, "في حقل الكتابة نص لم أكتبه أنا في هذه المهمة:\n«${stale.take(200)}»\nهل أرسله كما هو؟",
            yes = "أرسله", no = "لا ترسله", title = "نص قديم في حقل الكتابة", timeoutMs = 120_000,
        ) == true
        log(run, if (send) LogKind.INFO else LogKind.WARN, if (send) "وافقتَ على إرسال النص الموجود" else "لم يُرسل النص القديم")
        return stale to send
    }

    /** What the approval question shows for a send: the text that will go out, not just the button's name. */
    private fun draftLine(drafts: List<String>): String =
        drafts.firstOrNull()?.let { "\nالنص: «${it.take(200)}»" }.orEmpty()

    /**
     * The model is about to send again a text that already went out to the same place in this task.
     * That only happens when it did not believe the first send. The message is there, so the task ends
     * here as done — no duplicate, and no further calls spent on doubting it.
     */
    private fun alreadySent(run: Run, text: String): Nothing {
        log(run, LogKind.WARN, "منعتُ إرسال النص نفسه مرة ثانية: «${text.take(40)}»")
        throw Stop(TaskStatus.DONE, "أُرسل «${text.take(80)}» مرة واحدة. أوقفتُ محاولة إرساله ثانيةً حتى لا يتكرر.")
    }

    /** What the owner should know when a task ends badly although something was already sent. */
    private fun sentFootnote(run: Run): String =
        if (run.sentShown.isEmpty()) "" else "\n\nللعلم: أُرسل النص «${run.sentShown.first().take(60)}» خلال هذه المهمة (خلا حقل الكتابة بعد الضغط على الإرسال)."

    private val power by lazy { ctx.getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { ctx.getSystemService(KeyguardManager::class.java) }

    /** False while the display is off or the lock screen is up: there is no app screen to read or tap then. */
    private fun screenUsable(): Boolean = runCatching { power.isInteractive && !keyguard.isKeyguardLocked }.getOrDefault(true)

    /**
     * The agent works on what the screen shows. If the owner locks the phone in the middle of a task,
     * it waits here — no model calls, no taps — and carries on when the screen is back.
     */
    private suspend fun awaitScreen(svc: AgentAccessibilityService, run: Run) {
        if (screenUsable()) return
        log(run, LogKind.WARN, "الشاشة مطفأة أو مقفلة: توقّفتُ مؤقتاً دون أي استهلاك، وأكمل حين تفتحها.")
        val since = SystemClock.elapsedRealtime()
        while (!screenUsable()) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() - since > PAUSE_LIMIT_MS) {
                throw Stop(TaskStatus.FAILED, "أُقفلت الشاشة أثناء المهمة ولم تُفتح خلال ${PAUSE_LIMIT_MS / 60_000} دقائق، فأوقفتُها دون استهلاك. اطلبها من جديد والشاشة مفتوحة.")
            }
            _state.value = EngineState.Running("متوقف مؤقتاً: افتح الشاشة لأكمل", run.step, settings.maxSteps)
            delay(1000)
        }
        log(run, LogKind.INFO, "فُتحت الشاشة — أكمل المهمة.")
        svc.waitForIdle(600, 3000)
    }

    private val uiLang: String
        get() = runCatching { ctx.resources.configuration.locales[0].toLanguageTag() }.getOrDefault("ar")

    private class Run(val task: String, val scheduledFor: Long? = null) {
        val started = System.currentTimeMillis()
        val logs = mutableListOf<LogEntry>()
        var usage = Usage()
        var cost = 0.0
        var calls = 0
        var steps = 0
        var localSteps = 0
        var step = 0
        var status = TaskStatus.RUNNING
        var result = ""

        /** Successful steps in order (replayed + model-driven) — the raw material for learning. */
        val route = mutableListOf<MacroStep>()
        var routeOk = true
        var llmActions = 0

        /** The whole text of a page the model asked to read: handed over in its next turn, once. */
        var pageText: String? = null

        /** When a web link was last opened: its page may still be loading when it is read. */
        var urlOpenedAt = 0L

        // Map learning
        var lastKey: String? = null
        var pending: Pending? = null

        // Knowledge given to the model
        var targets: List<String> = emptyList()
        var playbook: Playbook? = null
        val knowledge = HashMap<String, String?>()
        val readOnlyNoted = HashSet<String>()

        /** "where::text" of everything this task already sent, so the same text is never sent twice to the same place. */
        val sent = HashSet<String>()
        val sentShown = ArrayList<String>()

        /** Everything this task itself put into input fields (typed, or pre-filled through a link it opened), normalized. */
        val typed = ArrayList<String>()
    }

    private class Stop(val status: TaskStatus, msg: String) : Exception(msg)

    private companion object {
        /** How long a task waits for a locked screen before giving up. */
        const val PAUSE_LIMIT_MS = 10 * 60_000L
        val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    }

    private class Outcome(
        val line: String,
        val abort: Boolean = false,
        val macro: MacroStep? = null,
        val breaksMacro: Boolean = false,
        val tap: Boolean = false,
        val onEditable: Boolean = false,
    )

    private fun log(run: Run, kind: LogKind, text: String) {
        run.logs += LogEntry(System.currentTimeMillis(), kind, text)
        _logs.value = run.logs.toList()
        if (_state.value is EngineState.Running && kind != LogKind.SUCCESS && kind != LogKind.ERROR) {
            _state.value = EngineState.Running(text, run.step, settings.maxSteps)
        }
    }

    private fun running(run: Run, line: String) {
        _state.value = EngineState.Running(line, run.step, settings.maxSteps)
    }

    private fun pushStats(run: Run) {
        _live.value = LiveStats(run.calls, run.cost, run.usage, run.localSteps)
    }

    private fun account(run: Run, model: String, u: Usage) {
        run.calls++
        run.usage += u
        run.cost += settings.cost(model, u)
        pushStats(run)
    }

    private suspend fun guarded(run: Run, block: suspend () -> Unit) {
        _logs.value = emptyList()
        _live.value = LiveStats()
        lastResult.value = null
        running(run, "جارٍ البدء…")
        log(run, LogKind.INFO, "المهمة: ${run.task}")
        try {
            block()
        } catch (s: Stop) {
            run.status = s.status
            run.result = s.message.orEmpty()
            log(run, if (s.status == TaskStatus.DONE) LogKind.SUCCESS else LogKind.ERROR, run.result.lines().first().take(200))
        } catch (c: CancellationException) {
            run.status = TaskStatus.STOPPED
            run.result = "أوقفتَ المهمة."
            log(run, LogKind.WARN, run.result)
        } catch (e: Exception) {
            run.status = TaskStatus.FAILED
            run.result = e.message ?: "حدث خطأ غير متوقع."
            log(run, LogKind.ERROR, run.result)
        } finally {
            withContext(NonCancellable) {
                runCatching { maps.flush() }
                settings.addSpend(run.cost)
                history.add(
                    TaskRecord(
                        run.started, run.task, run.started, System.currentTimeMillis(), run.status,
                        run.calls, run.usage, run.cost, run.steps, run.localSteps, run.result, run.logs.toList(),
                    )
                )
                pushStats(run)
                lastResult.value = run.result
                showResult(run)
                confirmGate = null
                askGate = null
                _state.value = EngineState.Idle
            }
        }
    }

    /** Long answers open the full result sheet; short confirmations show as a toast. */
    private fun showResult(run: Run) {
        if (run.status == TaskStatus.STOPPED || run.result.isBlank()) return
        val host: Context = AgentAccessibilityService.instance ?: ctx
        Handler(Looper.getMainLooper()).post {
            if (run.result.length >= 90 || run.result.contains('\n') || run.status == TaskStatus.FAILED) {
                com.wakeel.agent.ui.ResultActivity.open(host, run.started)
            } else {
                Toast.makeText(host, "✓ " + run.result, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ------------------------------------------------------------------ pipeline

    private suspend fun execute(run: Run) {
        val task = run.task

        // 0) A task for later ("بعد 10 دقائق…", "الساعة 8 مساءً…") waits on the phone; the model cannot wait
        if (run.scheduledFor != null) {
            log(run, LogKind.LOCAL, "مهمة مجدولة حان موعدها (${Schedule.clock(run.scheduledFor)})")
        } else if (scheduler != null) {
            Schedule.parse(task)?.let { plan ->
                val whenText = Schedule.describe(plan.at)
                log(run, LogKind.INFO, "في المهمة موعد: «${plan.phrase}»")
                val answer = choose(
                    run, "أنفّذ هذه المهمة $whenText؟\n«${plan.task.take(160)}»",
                    yes = "جدولة", no = "نفّذ الآن", title = "مهمة لوقت لاحق", timeoutMs = 60_000,
                )
                when (answer) {
                    true -> {
                        // Asked again from now: the owner may have taken a while to answer "after a minute"
                        val at = Schedule.parse(task)?.at ?: plan.at
                        scheduler.add(plan.task, at)
                        run.localSteps++
                        log(run, LogKind.LOCAL, "جُدولت المهمة دون استهلاك API")
                        throw Stop(TaskStatus.DONE, "جُدولت المهمة ${Schedule.describe(at)}. إلغاؤها من الصفحة الرئيسية.")
                    }
                    false -> log(run, LogKind.INFO, "اخترتَ تنفيذها الآن")
                    null -> throw Stop(TaskStatus.STOPPED, "لم تُجب عن سؤال الجدولة، فلم أنفّذ المهمة ولم أجدولها.")
                }
            }
        }

        val svc = AgentAccessibilityService.instance
            ?: throw Stop(TaskStatus.FAILED, "خدمة إمكانية الوصول غير مفعّلة. فعّلها من الصفحة الرئيسية أولاً.")
        val trail = mutableListOf<String>()
        val router = LocalRouter(ctx, apps, maps)

        // 1) A learned skill that matches locally (exact or by pattern) — zero cost
        var match: Pair<Skill, Map<String, String>>? = if (settings.autoLearn) skills.localMatch(task) else null

        // 2) Built-in local commands — zero cost
        if (match == null) {
            val t0 = SystemClock.uptimeMillis()
            when (val r = router.route(task, svc)) {
                is LocalRouter.Result.Handled -> {
                    run.localSteps++
                    log(run, LogKind.LOCAL, "نُفّذت محلياً دون استهلاك API")
                    throw Stop(TaskStatus.DONE, r.message)
                }
                is LocalRouter.Result.Opened -> {
                    run.localSteps++
                    run.route += r.step
                    if (r.fresh) maps.expectRoot(r.pkg)
                    log(run, LogKind.LOCAL, "فُتح «${r.app}» محلياً دون استهلاك API")
                    trail += "${r.step.type} \"${r.app}\" ✓"
                    svc.waitForApp(if (r.fresh) r.pkg else null, t0)
                }
                is LocalRouter.Result.Blocked -> {
                    run.localSteps++
                    throw Stop(TaskStatus.FAILED, r.message)
                }
                is LocalRouter.Result.Path -> {
                    // The learned map already knows the way to this page: walk it, no model involved
                    val known = Skill(
                        id = "", title = "${r.page} — ${r.app}", pattern = task, params = emptyList(), example = task,
                        steps = r.steps, doneTpl = "", readout = false, pkg = r.pkg,
                    )
                    val walked = replay(svc, run, known, emptyMap(), trail, router, "مسار محفوظ في خريطة «${r.app}» إلى «${r.page}» — تنفيذ محلي دون استهلاك API")
                    if (walked == known.steps.size) throw Stop(TaskStatus.DONE, "فتحتُ «${r.page}» في ${r.app}.")
                }
                LocalRouter.Result.None -> Unit
            }
        }

        // 3) Never operate on ourselves
        if (trail.isEmpty() && ownAppInFront(svc)) {
            svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
            svc.waitForIdle()
        }

        // 4) A learned skill with different wording/values — one tiny routing call
        if (match == null && settings.autoLearn && settings.apiKey.isNotBlank() && settings.fastModel.isNotBlank()) {
            val cands = skills.candidates(task)
            if (cands.isNotEmpty()) {
                running(run, "يبحث في المهارات المتعلَّمة…")
                Learner.route(LlmClient.create(settings), settings.fastModel, task, cands)?.let { (sk, p, u) ->
                    account(run, settings.fastModel, u)
                    match = sk to p
                }
            }
        }

        // 5) Replay the skill locally
        var note: String? = null
        var activeSkill: Skill? = null
        match?.let { (sk, p) ->
            val done = replay(svc, run, sk, p, trail, router)
            if (done == sk.steps.size) {
                if (sk.trusted && !sk.readout) {
                    skills.record(sk.id, true)
                    throw Stop(TaskStatus.DONE, Templates.fill(sk.doneTpl, p).orEmpty().ifBlank { "تمّت المهمة بمهارة متعلَّمة." })
                }
                activeSkill = sk
                note = if (sk.readout)
                    "NOTE: A saved routine for this task was just replayed locally. Read the requested information from the screen and call done with it."
                else
                    "NOTE: A saved routine for this task was just replayed locally. If the task is now complete call done; otherwise continue."
            } else if (sk.taught && sk.uses == 0) {
                // Taught by hand and it could not be completed even once: it was recorded unfinished. Drop it.
                skills.remove(sk.id)
                val why = "المهارة «${sk.title.take(40)}» التي علّمتَني إياها ناقصة (توقّفت عند الخطوة ${done + 1} من ${sk.steps.size})، فحذفتُها."
                log(run, LogKind.WARN, why)
                if (settings.apiKey.isBlank()) throw Stop(TaskStatus.FAILED, "$why علّمني إياها من جديد.")
            } else {
                skills.record(sk.id, false)
            }
        }

        // 6) Plan with the model (and learn at the end)
        llmLoop(svc, run, trail, note, activeSkill, router)
    }

    private suspend fun ownAppInFront(svc: AgentAccessibilityService): Boolean {
        repeat(6) {
            val p = svc.readScreen().pkg
            if (p != ctx.packageName && p.isNotEmpty()) return false
            delay(250)
        }
        return svc.readScreen().pkg.let { it == ctx.packageName || it.isEmpty() }
    }

    private fun budgetCheck(run: Run) {
        if (run.cost >= settings.taskBudget) throw Stop(TaskStatus.FAILED, "توقّفت المهمة لبلوغ حد ميزانيتها (${Text.money(settings.taskBudget.toDouble())}).")
        if (settings.todaySpend() + run.cost >= settings.dailyBudget) throw Stop(TaskStatus.FAILED, "بلغت الحد اليومي للإنفاق (${Text.money(settings.dailyBudget.toDouble())}).")
    }

    /** Reads the screen and, for free, records it in the app's map together with the tap that led here. */
    private fun observe(svc: AgentAccessibilityService, run: Run): Screen {
        val s = svc.readScreen()
        val learn = settings.autoLearn && s.pkg.isNotBlank() && s.pkg != ctx.packageName && s.pkg != "com.android.systemui" &&
            s.pkg !in apps.launcherPackages() && Safety.policy(s.pkg, settings, apps) != Policy.BLOCKED
        if (!learn) {
            run.lastKey = null
            run.pending = null
            return s
        }
        val key = maps.observe(s)
        val p = run.pending
        if (p != null && key != null && p.pkg == s.pkg && p.from != null && p.from != key) maps.edge(s.pkg, p.from, p.label, key, p.viewId)
        run.pending = null
        run.lastKey = key
        return s
    }

    /** Built-in guide + learned map + notes for the apps involved; identical across a task's calls so it is cached. */
    private fun knowledgeFor(run: Run, pkg: String): String? {
        val pkgs = (listOf(pkg) + run.targets).filter { it.isNotBlank() && it != ctx.packageName && it !in apps.launcherPackages() }.distinct().take(2)
        val cacheKey = pkgs.joinToString("+")
        if (run.knowledge.containsKey(cacheKey)) return run.knowledge[cacheKey]
        val sb = StringBuilder()
        for (p in pkgs) {
            val guide = KnowledgeBase.guideFor(p)
            val map = maps.describe(p)
            val tips = notes.get(p)
            if (guide == null && map.isBlank() && tips.isEmpty()) continue
            sb.append("## APP KNOWLEDGE — ").append(apps.byPackage(p)?.label ?: p).append(" (").append(p).append(")\n")
            guide?.let {
                sb.append("GUIDE: ").append(it.guide).append('\n')
                if (it.ar.isNotEmpty() && uiLang.startsWith("ar")) sb.append("ARABIC UI LABELS (English=Arabic, use the Arabic in `find`): ").append(it.ar).append('\n')
            }
            if (map.isNotBlank()) sb.append("MAP (learned on this phone):\n").append(map).append('\n')
            if (tips.isNotEmpty()) {
                sb.append("NOTES:\n")
                tips.take(6).forEach { sb.append("- ").append(it).append('\n') }
            }
        }
        run.playbook?.let { sb.append("## PLAYBOOK — ").append(it.id).append('\n').append(it.text).append('\n') }
        val out = sb.toString().trim().ifEmpty { null }
        run.knowledge[cacheKey] = out
        return out
    }

    private suspend fun llmLoop(
        svc: AgentAccessibilityService, run: Run, trail: MutableList<String>, note: String?, activeSkill: Skill?, router: LocalRouter,
    ) {
        if (settings.apiKey.isBlank()) throw Stop(TaskStatus.FAILED, "أضف مفتاح API من الإعدادات أولاً.")
        if (settings.fastModel.isBlank()) throw Stop(TaskStatus.FAILED, "حدّد اسم النموذج في الإعدادات.")
        val client = LlmClient.create(settings)
        run.targets = apps.mentioned(run.task).map { it.pkg }
        run.playbook = KnowledgeBase.playbookFor(run.task)
        run.playbook?.let { log(run, LogKind.INFO, "خطة تشخيص جاهزة: ${it.title}") }
        var strong = false
        var stagnant = 0
        var wantShot = false
        var wantHd = false
        val recent = ArrayDeque<String>()
        var escalatedForLoop = false
        var blockedHits = 0
        val memo = mutableListOf<String>()
        val max = settings.maxSteps
        // The last request is kept for the conclusion: a task that ran long still ends with an answer
        val acting = if (max >= 3) max - 1 else max

        for (step in 1..acting) {
            currentCoroutineContext().ensureActive()
            run.step = step
            awaitScreen(svc, run)
            budgetCheck(run)
            svc.waitForIdle()
            var screen = observe(svc, run)
            if (screen.pkg == ctx.packageName) {
                // Our own window may still be closing (e.g. after answering a question) — give it a moment
                repeat(6) { if (screen.pkg == ctx.packageName) { delay(250); screen = observe(svc, run) } }
                if (screen.pkg == ctx.packageName) {
                    svc.global(AccessibilityService.GLOBAL_ACTION_HOME); svc.waitForIdle(); screen = observe(svc, run)
                }
            }
            if (screen.pkg.isEmpty() && screen.nodes.isEmpty()) {
                // Nothing readable: the page is still loading — or Android hides it from control apps.
                var hidden: String? = null
                repeat(10) {
                    if (screen.pkg.isEmpty()) {
                        delay(500)
                        screen = observe(svc, run)
                        hidden = if (screen.pkg.isEmpty()) svc.unreadableWindow() else null
                    }
                }
                val title = hidden
                if (screen.pkg.isEmpty() && title != null) {
                    throw Stop(
                        TaskStatus.FAILED,
                        "توقّفتُ عند صفحة ${if (title.isBlank()) "" else "«$title» "}لا أستطيع قراءتها: أندرويد يحجب محتوى بعض الصفحات الحساسة عن تطبيقات التحكم. " +
                            "الصفحة مفتوحة أمامك: أكمل هذه الخطوة بيدك، ثم اطلب مني المتابعة إن شئت.",
                    )
                }
            }
            when (Safety.policy(screen.pkg, settings, apps)) {
                Policy.BLOCKED -> {
                    // A protected app came up (usually a mis-tap): leave it at once and let the agent recover.
                    val reason = Safety.blockReason(screen.pkg, settings, apps).orEmpty()
                    svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
                    svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                    blockedHits++
                    if (blockedHits >= 2) throw Stop(TaskStatus.FAILED, reason)
                    log(run, LogKind.WARN, "فُتح تطبيق محمي بالخطأ — خرج الوكيل منه فوراً دون لمس أي شيء")
                    trail += "(a protected app ${screen.pkg} opened by mistake — it is off-limits; you are back on the home screen. Use open_app to reach the intended app)"
                    run.routeOk = false
                    svc.waitForIdle()
                    continue
                }
                Policy.READ_ONLY -> if (run.readOnlyNoted.add(screen.pkg)) {
                    log(run, LogKind.INFO, "«${apps.byPackage(screen.pkg)?.label ?: screen.pkg}» في وضع القراءة فقط: يقرأ ويحلل ولا ينفّذ أوامر مالية")
                    trail += "(${screen.pkg} is READ-ONLY by owner policy: navigate, search and read only — never trade, order, transfer or confirm. " +
                        "If TASK asks for such an action here, even on a demo or paper account, call fail — not done — and say the app is read-only for you)"
                }
                Policy.ALLOWED -> Unit
            }

            val needShot = settings.allowScreenshots && !screen.hasPassword && (wantShot || screen.nodes.size < 3)
            val shot = if (needShot) { if (wantHd) svc.screenshot(1280, 70) else svc.screenshot() } else null
            if (shot != null) log(run, LogKind.INFO, if (wantHd) "أُرفقت لقطة شاشة عالية الدقة" else "أُرفقت لقطة شاشة مصغّرة")
            wantShot = false
            wantHd = false

            val useStrong = strong && settings.strongModel.isNotBlank()
            val model = if (useStrong) settings.strongModel else settings.fastModel
            running(run, if (useStrong) "يخطّط بالنموذج الأقوى…" else "يخطّط…")
            val finalNote = if (step >= acting - 1) "FINAL STEP: call done now with your best answer from MEMO, PAGE TEXT and this screen (state any limits), or fail with the reason." else null
            val stepNote = listOfNotNull(note.takeIf { step == 1 }, finalNote).joinToString("\n").ifEmpty { null }
            val device = if (run.playbook != null) DeviceInfo.brief(DeviceInfo.snapshot(ctx)) else null
            val pageText = run.pageText
            run.pageText = null
            val user = Prompt.user(run.task, trail, screen, shot, step, max, stepNote, memo, device, uiLang, Prompt.now(), pageText)
            val res = client.decide(model, user, shot, knowledgeFor(run, screen.pkg))
            account(run, model, res.usage)
            strong = false

            val d = res.decision
            if (d.thought.isNotBlank()) log(run, LogKind.THINK, d.thought)
            if (d.memo.isNotBlank() && memo.none { it == d.memo }) {
                memo += d.memo.take(280)
                if (memo.size > 14) memo.removeAt(0)
                log(run, LogKind.INFO, "📝 " + d.memo.take(140))
            }
            if (d.actions.isEmpty()) {
                trail += "(invalid reply — call the act tool with at least one action)"
                stagnant++
                if (stagnant >= 2 && settings.escalate) strong = true
                continue
            }

            val before = screen.hash
            // Loop guard: the same plan start on the same screen again and again = no progress
            val key = d.actions.first().describe() + "|" + before
            recent.addLast(key)
            if (recent.size > 6) recent.removeFirst()
            if (recent.count { it == key } >= 3) {
                if (!escalatedForLoop && settings.escalate && settings.strongModel.isNotBlank()) {
                    escalatedForLoop = true
                    strong = true
                    if (settings.allowScreenshots) wantShot = true
                    recent.clear()
                    trail += "(you repeated the same action 3 times without progress — use a different approach)"
                    log(run, LogKind.WARN, "يكرر الوكيل نفس الخطوة — سيستعين بالنموذج الأقوى وبلقطة شاشة")
                    continue
                }
                // Say which step it was: "try a clearer wording" tells the owner nothing they can act on.
                val first = d.actions.first()
                val target = first.find?.take(30) ?: first.id?.let { screen.byId(it)?.title?.take(30) }
                val what = actionLabel(first, target?.takeIf { it.isNotBlank() } ?: "عنصر بلا اسم")
                val where = apps.byPackage(screen.pkg)?.label?.let { " في «$it»" }.orEmpty()
                throw Stop(
                    TaskStatus.FAILED,
                    "توقّفتُ لأن خطوة واحدة تتكرر والشاشة لا تتغيّر: $what$where.\n" +
                        "ما يفيد الآن: اذكر لي الخطوات بالترتيب وبأسماء الأزرار كما تراها، " +
                        "أو نفّذ المهمة بيدك مرة واحدة من «علّمني مهمة» فأعيدها بعدها دون استهلاك." + sentFootnote(run),
                )
            }

            if (d.actions.size >= 3) log(run, LogKind.INFO, "خطة من ${d.actions.size} خطوات في طلب واحد")
            // The owner confirms a model-flagged plan once, at its last committing action
            val sensitiveIdx = if (d.sensitive) d.actions.indexOfLast {
                it.type in setOf(ActionType.TAP, ActionType.TAP_XY, ActionType.LONG_PRESS, ActionType.ENTER, ActionType.DRAG)
            } else -1

            var waited = false
            val orig = screen
            var cur = screen
            var prevTap = false
            var prevOnEditable = false
            var prevHash = 0
            for ((i, a) in d.actions.withIndex()) {
                currentCoroutineContext().ensureActive()
                awaitScreen(svc, run)
                if (i > 0) {
                    svc.waitForIdle(350, 2500)
                    cur = observe(svc, run)
                    if (prevTap && cur.hash == prevHash) {
                        // Slow screens: give the tap one more moment before judging it
                        delay(800)
                        cur = observe(svc, run)
                    }
                    // A tap that changed nothing: a planned `find` step may still wait for its element,
                    // but never continue blindly with ids, and never declare the task done.
                    if (prevTap && cur.hash == prevHash && !prevOnEditable && (a.find == null || a.type == ActionType.DONE)) {
                        trail[trail.lastIndex] = trail.last() + " · no change"
                        break
                    }
                    if (cur.pkg != orig.pkg && Safety.policy(cur.pkg, settings, apps) == Policy.BLOCKED) break
                }
                when (a.type) {
                    ActionType.DONE -> {
                        val result = a.text ?: "تمّت المهمة."
                        // "Done" whose own words say a read-only app stopped it is not done: the owner is told so, and how to allow it
                        if (run.readOnlyNoted.isNotEmpty() && Safety.saysReadOnlyStoppedIt(result)) {
                            throw Stop(TaskStatus.FAILED, result + readOnlyFootnote(run, cur.pkg))
                        }
                        val unfinished = Safety.saysNotCarriedOut(result)
                        if (unfinished) {
                            log(run, LogKind.INFO, "لم تُحفظ مهارة من هذه المهمة: النتيجة تقول إن جزءاً منها لم يُنفَّذ")
                        } else if (activeSkill != null && run.llmActions == 0) {
                            skills.record(activeSkill.id, true)
                            log(run, LogKind.LOCAL, "تأكّدت المهارة «${activeSkill.title}» — المرات القادمة دون أي استهلاك")
                        } else if (settings.autoLearn && run.routeOk && run.route.isNotEmpty()) {
                            learn(client, run, result, cur.pkg)
                        }
                        throw Stop(TaskStatus.DONE, result)
                    }
                    ActionType.FAIL -> {
                        val why = a.text ?: "تعذّر إكمال المهمة."
                        val more = if (run.readOnlyNoted.isNotEmpty() && Safety.saysReadOnlyStoppedIt(why)) readOnlyFootnote(run, cur.pkg) else ""
                        throw Stop(TaskStatus.FAILED, why + more + sentFootnote(run))
                    }
                    else -> Unit
                }
                prevHash = cur.hash
                val out = perform(svc, run, a, orig, cur, i == sensitiveIdx, shot, router)
                run.steps++
                run.llmActions++
                trail += out.line
                prevTap = out.tap
                prevOnEditable = out.onEditable
                if (out.macro != null) run.route += out.macro else if (out.breaksMacro) run.routeOk = false
                if (a.type == ActionType.WAIT || a.type == ActionType.READ) waited = true
                if (a.type == ActionType.SCREENSHOT && settings.allowScreenshots) {
                    wantShot = true
                    if (a.text?.contains("hd", ignoreCase = true) == true) wantHd = true
                }
                if (out.abort) break
            }

            svc.waitForIdle()
            val after = observe(svc, run)
            if (prevTap && after.hash == prevHash && trail.isNotEmpty() && !trail.last().endsWith("no change")) {
                trail[trail.lastIndex] = trail.last() + " · no change"
            }
            if (after.hash == before && !waited && !wantShot) stagnant++ else stagnant = 0
            if (stagnant >= 2 && settings.escalate) {
                strong = true
                if (settings.allowScreenshots) wantShot = true
                stagnant = 0
                log(run, LogKind.WARN, "تعثّر الوكيل — سيستعين بالنموذج الأقوى ولقطة شاشة للخطوة القادمة")
            }
        }
        if (acting < max) conclude(svc, client, run, trail, memo, max)
        throw Stop(TaskStatus.FAILED, "توقّف الوكيل بعد بلوغ الحد الأقصى للطلبات ($max)." + sentFootnote(run))
    }

    /** Where the owner changes what the agent may do in the read-only app that stopped this task. */
    private fun readOnlyFootnote(run: Run, current: String): String {
        val pkg = if (current in run.readOnlyNoted) current else run.readOnlyNoted.first()
        val name = apps.byPackage(pkg)?.label ?: pkg
        return "\n\n«$name» مضبوط على «قراءة فقط»، فلا أنفّذ فيه أوامر ولو على حساب تجريبي. " +
            "للسماح: المعرفة ← التطبيقات ← $name ← «مسموح». بعدها أطلب موافقتك قبل كل شراء أو بيع."
    }

    /**
     * The requests are used up. Whatever was reached is not thrown away: one last request reads the page in
     * front and gives the owner the answer (or says exactly what was done and what is left).
     */
    private suspend fun conclude(
        svc: AgentAccessibilityService, client: LlmClient, run: Run, trail: List<String>, memo: List<String>, max: Int,
    ) {
        run.step = max
        budgetCheck(run)
        svc.waitForIdle()
        val screen = observe(svc, run)
        if (screen.pkg == ctx.packageName || Safety.policy(screen.pkg, settings, apps) == Policy.BLOCKED) return
        running(run, "يلخّص ما وصل إليه…")
        log(run, LogKind.INFO, "بلغ الحد الأقصى للطلبات — يقرأ الصفحة الحالية ويجيب بما وصل إليه")
        val text = if (screen.hasPassword) null else ScreenReader.joinPage(ScreenReader.page(svc).lines, 7000).ifBlank { null }
        val user = Prompt.user(run.task, trail, screen, null, max, max, null, memo, null, uiLang, Prompt.now(), text)
        val res = try {
            client.callTool(settings.fastModel, Prompt.CONCLUDE, user, null, Prompt.finishTool(), maxTokens = 1200)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        account(run, settings.fastModel, res.usage)
        val o = res.input ?: return
        val answer = o.optString("text").trim()
        if (answer.isEmpty()) return
        when (o.optString("status")) {
            "done" -> throw Stop(TaskStatus.DONE, answer)
            "fail" -> throw Stop(TaskStatus.FAILED, answer + sentFootnote(run))
        }
    }

    /**
     * Reads the page in front from the top of what is visible to its end. A web page is described whole by
     * the browser; any other page (or a browser that describes only the visible part) is scrolled and gathered.
     */
    private suspend fun readPage(svc: AgentAccessibilityService, run: Run): String {
        var page = ScreenReader.page(svc)
        val fresh = run.urlOpenedAt > 0 && SystemClock.uptimeMillis() - run.urlOpenedAt < 12_000
        // A link was just opened and the browser has not drawn the page yet
        if (fresh && !page.web) {
            for (i in 0 until 12) {
                delay(400)
                page = ScreenReader.page(svc)
                if (page.web) break
            }
        }
        // A page that is still loading keeps growing: wait until its readings agree
        if (page.web) {
            var size = page.lines.sumOf { it.length }
            var same = 0
            for (i in 0 until 10) {
                delay(650)
                page = ScreenReader.page(svc)
                val n = page.lines.sumOf { it.length }
                same = if (n == size && n > 0) same + 1 else 0
                size = n
                // Search results arrive after the page itself: a page just opened is given a few seconds before it is believed
                if (same >= (if (fresh) 2 else 1) && (!fresh || SystemClock.uptimeMillis() - run.urlOpenedAt >= 4000)) break
            }
        }
        val lines = LinkedHashSet(page.lines)
        var chars = lines.sumOf { it.length + 1 }
        val whole = page.web && page.below > 0
        var scrolled = 0
        if (!whole && page.scroller != null) {
            while (scrolled < 7 && chars < ScreenReader.PAGE_CHARS) {
                currentCoroutineContext().ensureActive()
                svc.markAction()
                val node = ScreenReader.page(svc).scroller ?: break
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
                scrolled++
                svc.waitForIdle(300, 1500)
                var added = 0
                for (l in ScreenReader.page(svc).lines) if (lines.add(l)) { added++; chars += l.length + 1 }
                if (added == 0) break
            }
            // Back to where the reading began: the next step may tap something that was on screen
            repeat(scrolled) {
                svc.markAction()
                val node = ScreenReader.page(svc).scroller ?: return@repeat
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                svc.waitForIdle(200, 900)
            }
        }
        log(run, LogKind.INFO, "قرأ الصفحة كاملة (${lines.size} سطراً" + (if (scrolled > 0) "، بتمرير $scrolled شاشات" else "") + ")")
        return ScreenReader.joinPage(lines.toList())
    }

    private suspend fun learn(client: LlmClient, run: Run, result: String, screenPkg: String) {
        running(run, "يتعلّم من هذه المهمة…")
        val pkg = run.route.lastOrNull { it.type == "open_app" }?.text ?: screenPkg
        val learned = Learner.learn(client, settings.fastModel, run.task, run.route, result, pkg)
        learned?.let { account(run, settings.fastModel, it.usage) }
        if (learned != null && !learned.reusable) return
        val skill = Learner.newSkill(run.task, learned, run.route.toList(), result, pkg)
        skills.upsert(skill)
        learned?.notes?.groupBy({ it.first }, { it.second })?.forEach { (p, n) -> notes.add(p, n) }
        val vars = if (skill.params.isEmpty()) "" else " بمتغيّرات: " + skill.params.joinToString("، ")
        log(run, LogKind.LOCAL, "تعلّمتُ مهارة «${skill.title}»$vars — ستُنفَّذ لاحقاً محلياً")
    }

    // ------------------------------------------------------------------ actions

    private suspend fun perform(
        svc: AgentAccessibilityService, run: Run, a: AgentAction,
        orig: Screen, cur: Screen, sensitive: Boolean, shot: Shot?, router: LocalRouter,
    ): Outcome {
        val readOnly = Safety.policy(cur.pkg, settings, apps) == Policy.READ_ONLY
        val byFind = a.find != null && (a.id == null || cur !== orig)

        /** Resolves the target: by id on the screen the model saw, or by label on a screen planned ahead. */
        suspend fun target(wantEditable: Boolean = false): UiNode? {
            if (!byFind) {
                val o = a.id?.let { orig.byId(it) } ?: return null
                return if (cur === orig) o else cur.find(o.signature, o.bounds)
            }
            val q = a.find ?: return null
            cur.match(q, wantEditable)?.let { return it }
            repeat(9) {
                delay(400)
                svc.readScreen().match(q, wantEditable)?.let { return it }
            }
            return null
        }

        val shown = if (byFind) a.find?.take(30) else a.id?.let { orig.byId(it)?.label?.take(30) }
        log(run, LogKind.ACTION, actionLabel(a, shown))
        val missing = "${a.describe()} ✗ not found on screen — plan stopped here"

        return when (a.type) {
            ActionType.TAP, ActionType.LONG_PRESS -> {
                val n = target() ?: return Outcome(missing, abort = true)
                if (readOnly && Safety.isTradeLabel(n.label)) {
                    log(run, LogKind.WARN, "رُفضت الضغطة على «${n.label.take(30)}»: التطبيق في وضع القراءة فقط")
                    return Outcome("${a.describe()} \"${n.label.take(30)}\" ✗ REFUSED: read-only app (no orders, transfers or confirmations)", abort = true, breaksMacro = true)
                }
                val risky = sensitive || Safety.isSensitiveLabel(n.label)
                val here = if (risky) svc.readScreen() else cur
                val drafts = if (risky) draftsOf(here) else emptyList()
                drafts.firstOrNull { sentKey(here, it) in run.sent }?.let { alreadySent(run, it) }
                val sends = risky && a.type == ActionType.TAP && (Safety.isSendLabel(n.label) || (n.label.isBlank() && sensitive))
                val stale = if (sends) staleDraft(run, drafts) else null
                if (stale != null && !stale.second) {
                    return Outcome(
                        "${a.describe()} ✗ NOT SENT: the input field held an older draft \"${stale.first.take(60)}\" that this task did not type, " +
                            "and the owner refused to send it. Type the message TASK asks for (type replaces the whole field), then send; " +
                            "if TASK gives no message text, ask_user for it.",
                        abort = true, breaksMacro = true,
                    )
                }
                val what = (if (a.type == ActionType.TAP) "الضغط على «${n.label.take(40)}»" else "الضغط المطوّل على «${n.label.take(40)}»") +
                    if (sends) draftLine(drafts) else ""
                if (stale == null && !confirm(run, risky, what))
                    return Outcome("${a.describe()} ✗ DENIED by owner — do not retry", abort = true, breaksMacro = true)
                val ok = if (a.type == ActionType.TAP) click(svc, n) else longClick(svc, n)
                if (ok) run.pending = pendingFor(cur.pkg, run.lastKey, n)
                val sentNote = if (ok) committed(svc, run, here, drafts) else ""
                Outcome(
                    "${a.describe()} \"${n.label.take(30)}\" ${mark(ok)}$sentNote", abort = !ok,
                    macro = if (ok) MacroStep(a.type.key, n.signature, label = stepLabel(n), confirm = risky) else null,
                    breaksMacro = !ok, tap = ok, onEditable = n.editable,
                )
            }

            ActionType.TAP_XY -> {
                if (shot == null || a.x == null || a.y == null) return Outcome("tap_xy ✗ needs a screenshot and x,y", abort = true)
                val sx = a.x * shot.scaleX
                val sy = a.y * shot.scaleY
                if (readOnly) {
                    val under = cur.at(sx, sy)
                    if (under == null || Safety.isTradeLabel(under.label))
                        return Outcome("tap_xy ✗ REFUSED: read-only app and the target cannot be verified as safe — use labelled elements", abort = true, breaksMacro = true)
                }
                if (!confirm(run, sensitive, "الضغط على نقطة في الشاشة")) return Outcome("tap_xy ✗ DENIED by owner", abort = true, breaksMacro = true)
                val ok = svc.tap(sx, sy)
                Outcome("tap_xy (${a.x},${a.y}) ${mark(ok)}", abort = true, breaksMacro = true)
            }

            ActionType.DRAG -> {
                if (shot == null || a.x == null || a.y == null || a.x2 == null || a.y2 == null)
                    return Outcome("drag ✗ needs a screenshot and x,y,x2,y2", abort = true)
                if (!confirm(run, sensitive, "سحب عنصر على الشاشة")) return Outcome("drag ✗ DENIED by owner", abort = true, breaksMacro = true)
                val ok = svc.swipe(a.x * shot.scaleX, a.y * shot.scaleY, a.x2 * shot.scaleX, a.y2 * shot.scaleY, 900)
                Outcome("drag (${a.x},${a.y})→(${a.x2},${a.y2}) ${mark(ok)}", abort = true, breaksMacro = true)
            }

            ActionType.TYPE -> {
                var n: UiNode? = null
                if (byFind) {
                    var scr = cur
                    for (k in 0 until 8) {
                        n = scr.match(a.find.orEmpty(), true) ?: focusedEditable(scr) ?: scr.nodes.singleOrNull { it.editable }
                        if (n != null) break
                        delay(400)
                        scr = svc.readScreen()
                    }
                } else {
                    n = target() ?: focusedEditable(cur) ?: cur.nodes.firstOrNull { it.editable }
                }
                val field = n ?: return Outcome("type ✗ no text field on screen", abort = true)
                if (field.password) return Outcome("type ✗ refused: password field — the owner must type it", abort = true, breaksMacro = true)
                if (readOnly && !Safety.isSearchField(field.label, field.viewId))
                    return Outcome("type ✗ REFUSED: read-only app — only search fields may be typed in", abort = true, breaksMacro = true)
                val txt = a.text.orEmpty()
                if (txt.isNotBlank() && sentKey(svc.readScreen(), txt) in run.sent) alreadySent(run, txt)
                run.typed += Text.norm(txt)
                val (ok, now) = setTextVerified(svc, field, txt)
                Outcome(
                    "type #${field.id} ${mark(ok)} — field now: \"${now.replace("\n", "\\n").take(160)}\"" +
                        if (ok) "" else " (text did not apply; try tapping the field first, or another approach)",
                    abort = !ok,
                    macro = if (ok) MacroStep("type", field.signature, text = txt) else null,
                    breaksMacro = !ok,
                )
            }

            ActionType.ENTER -> {
                if (readOnly) {
                    val f = focusedEditable(cur)
                    if (f == null || !Safety.isSearchField(f.label, f.viewId))
                        return Outcome("enter ✗ REFUSED: read-only app — only search fields", abort = true, breaksMacro = true)
                }
                val here = svc.readScreen()
                val drafts = draftsOf(here)
                if (sensitive) drafts.firstOrNull { sentKey(here, it) in run.sent }?.let { alreadySent(run, it) }
                val stale = if (sensitive) staleDraft(run, drafts) else null
                if (stale != null && !stale.second) {
                    return Outcome(
                        "enter ✗ NOT SENT: the input field held an older draft \"${stale.first.take(60)}\" that this task did not type, and the owner " +
                            "refused to send it. Type the message TASK asks for (type replaces the whole field); if TASK gives none, ask_user.",
                        abort = true, breaksMacro = true,
                    )
                }
                if (stale == null && !confirm(run, sensitive, "إرسال/تأكيد النص المكتوب" + if (sensitive) draftLine(drafts) else ""))
                    return Outcome("enter ✗ DENIED by owner", abort = true, breaksMacro = true)
                val ok = pressEnter(svc, cur, if (byFind) null else a.id?.let { orig.byId(it) })
                val sentNote = if (ok && sensitive) committed(svc, run, here, drafts) else ""
                Outcome("enter ${mark(ok)}$sentNote", abort = !ok, macro = if (ok) MacroStep("enter", confirm = sensitive) else null, breaksMacro = !ok)
            }

            ActionType.SCROLL -> {
                val dir = a.dir ?: "down"
                val n = (if (a.id != null || a.find != null) target() else null)?.takeIf { it.scrollable }
                val ok = scroll(svc, n, dir)
                Outcome("scroll $dir ${mark(ok)}", macro = if (ok) MacroStep("scroll", n?.signature, dir = dir) else null)
            }

            ActionType.BACK -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK, "back")
            ActionType.HOME -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME, "home")
            ActionType.RECENTS -> global(svc, AccessibilityService.GLOBAL_ACTION_RECENTS, "recents")
            ActionType.NOTIFICATIONS -> global(svc, AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications")

            ActionType.OPEN_APP -> {
                val app = apps.fuzzy(a.text.orEmpty())
                    ?: return Outcome("open_app \"${a.text}\" ✗ app not found on this phone", abort = true)
                Safety.blockReason(app.pkg, settings, apps)?.let { return Outcome("open_app \"${app.label}\" ✗ blocked by owner settings", abort = true) }
                // Already opened a moment ago by the local router: do not restart it a second time
                val justOpened = cur.pkg == app.pkg && run.route.lastOrNull()?.let { it.type == "open_app" && it.text == app.pkg } == true
                val t0 = SystemClock.uptimeMillis()
                val ok = justOpened || apps.launch(app.pkg, fresh = true)
                if (ok && !justOpened) {
                    svc.waitForApp(app.pkg, t0)
                    maps.expectRoot(app.pkg)
                }
                run.pending = null
                Outcome(
                    "open_app \"${app.label}\" ${mark(ok)}", abort = !ok,
                    macro = if (ok) MacroStep("open_app", text = app.pkg, label = app.label) else null,
                )
            }

            ActionType.OPEN_SETTINGS -> {
                val key = a.text.orEmpty()
                val t0 = SystemClock.uptimeMillis()
                val (ok, label) = router.openSettings(key)
                if (ok) {
                    svc.waitForApp(null, t0)
                    if (key.trim().equals("main", ignoreCase = true)) maps.expectRoot("com.android.settings")
                }
                run.pending = null
                Outcome(
                    "open_settings $key ${mark(ok)}" + if (ok) "" else " (unknown key or app — use a key from the list)", abort = !ok,
                    macro = if (ok) MacroStep("open_settings", text = key, label = label) else null,
                )
            }

            ActionType.OPEN_URL -> {
                val url = LocalRouter.normalizeUrl(a.text.orEmpty())
                    ?: return Outcome("open_url ✗ this link type is not allowed", abort = true)
                val t0 = SystemClock.uptimeMillis()
                val ok = router.openUrl(url)
                if (ok) noteLinkText(run, url)
                if (ok && url.startsWith("http", ignoreCase = true)) run.urlOpenedAt = SystemClock.uptimeMillis()
                if (ok) svc.waitForApp(null, t0)
                run.pending = null
                Outcome("open_url ${url.take(90)} ${mark(ok)}", abort = !ok, macro = if (ok) MacroStep("open_url", text = url) else null)
            }

            ActionType.WAIT -> {
                delay(2000); Outcome("wait ✓", macro = MacroStep("wait"))
            }

            ActionType.SCREENSHOT -> Outcome(
                if (settings.allowScreenshots) "screenshot → will be attached next turn" else "screenshot ✗ disabled by owner — use the element list",
                abort = true,
            )

            ActionType.READ -> {
                if (cur.hasPassword) return Outcome("read ✗ this page has a password field — not read", abort = true)
                val text = readPage(svc, run)
                if (text.isBlank()) return Outcome("read ✗ nothing readable on this page (try a screenshot)", abort = true)
                run.pageText = text
                Outcome("read ✓ ${text.length} characters — see PAGE TEXT", abort = true)
            }

            ActionType.ASK_USER -> {
                val q = a.text ?: "هل يمكنك التوضيح؟"
                val ans = ask(run, q)
                if (ans.isNullOrBlank()) throw Stop(TaskStatus.STOPPED, "لم يصل ردّك على سؤال الوكيل.")
                Outcome("ask_user \"${q.take(60)}\" → owner: \"${ans.take(120)}\"", abort = true, breaksMacro = true)
            }

            ActionType.DONE, ActionType.FAIL -> Outcome("", abort = true)
        }
    }

    // ------------------------------------------------------------------ replay of learned skills

    private suspend fun replay(
        svc: AgentAccessibilityService, run: Run, skill: Skill, params: Map<String, String>, trail: MutableList<String>, router: LocalRouter,
        intro: String? = null,
    ): Int {
        val vars = if (params.isEmpty()) "" else " (" + params.values.joinToString("، ") { "«$it»" } + ")"
        log(run, LogKind.LOCAL, intro ?: "مهارة متعلَّمة: «${skill.title}»$vars — تنفيذ محلي دون استهلاك API")
        for ((i, s) in skill.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            run.step = i + 1
            awaitScreen(svc, run)
            svc.waitForIdle()
            val here = observe(svc, run)
            val pol = Safety.policy(here.pkg, settings, apps)
            val text = Templates.fill(s.text, params)
            val label = Templates.fill(s.label, params)
            var filled = s.copy(text = text, label = label)
            var sentNote = ""
            val ok: Boolean = if (pol == Policy.BLOCKED) false else when (s.type) {
                "tap", "long_press" -> {
                    val n = waitFor(svc, s, params)
                    // A send/post step: what is in the input field now is what will go out
                    val commits = n != null && (s.confirm || s.approved || Safety.isSensitiveLabel(n.label))
                    val shown = if (commits) svc.readScreen() else here
                    val drafts = if (commits) draftsOf(shown) else emptyList()
                    val sends = commits && s.type == "tap" && n != null && Safety.isSendLabel(n.label)
                    val stale = if (sends) staleDraft(run, drafts) else null
                    if (n == null || (pol == Policy.READ_ONLY && Safety.isTradeLabel(n.label))) false
                    else if (stale != null && !stale.second) {
                        throw Stop(TaskStatus.STOPPED, "توقّفتُ قبل «${n.title.take(40)}»: في حقل الكتابة نص قديم لم أكتبه، ولم توافق على إرساله.")
                    } else if (stale == null && !confirm(
                            run, !s.approved && (s.confirm || Safety.isSensitiveLabel(n.label)),
                            "الضغط على «${n.title.take(40)}»" + if (sends) draftLine(drafts) else "", canAlways = skill.id.isNotEmpty(),
                        )
                    ) {
                        // The owner said no: stop here. Nothing failed, and no model call is spent looking for another way.
                        throw Stop(TaskStatus.STOPPED, "توقّفتُ قبل «${n.title.take(40)}» لأنك لم توافق.")
                    } else {
                        if (confirmAlways && skill.id.isNotEmpty()) {
                            confirmAlways = false
                            skills.approveStep(skill.id, i)
                            log(run, LogKind.LOCAL, "لن أسألك مجدداً عن «${n.title.take(30)}» في مهارة «${skill.title.take(30)}»")
                        }
                        filled = filled.copy(sig = n.signature, label = stepLabel(n))
                        val done = if (s.type == "tap") click(svc, n) else longClick(svc, n)
                        if (done) run.pending = pendingFor(here.pkg, run.lastKey, n)
                        if (done) sentNote = committed(svc, run, shown, drafts)
                        done
                    }
                }
                "type" -> {
                    val n = waitFor(svc, s, params) ?: focusedEditable(svc.readScreen()) ?: svc.readScreen().nodes.singleOrNull { it.editable }
                    run.typed += Text.norm(text.orEmpty())
                    if (n == null || n.password) false else setTextVerified(svc, n, text.orEmpty()).first
                }
                "enter" -> {
                    if (!confirm(run, s.confirm && !s.approved, "إرسال/تأكيد النص المكتوب", canAlways = skill.id.isNotEmpty())) throw Stop(TaskStatus.STOPPED, "توقّفتُ قبل الإرسال لأنك لم توافق.")
                    if (confirmAlways && skill.id.isNotEmpty()) {
                        confirmAlways = false
                        skills.approveStep(skill.id, i)
                    }
                    pressEnter(svc, svc.readScreen(), null)
                }
                "scroll" -> scroll(svc, s.sig?.let { here.find(it) }, s.dir ?: "down")
                "back" -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "open_app" -> s.text?.let { pkg ->
                    // Saved routes begin on the app's first page ("resume" marks a return to an app mid-task)
                    val fresh = s.dir != "resume"
                    val t0 = SystemClock.uptimeMillis()
                    Safety.blockReason(pkg, settings, apps) == null && apps.launch(pkg, fresh).also {
                        if (it) {
                            run.pending = null
                            svc.waitForApp(pkg, t0)
                            if (fresh) maps.expectRoot(pkg)
                        }
                    }
                } ?: false
                "open_settings" -> {
                    val t0 = SystemClock.uptimeMillis()
                    router.openSettings(s.text.orEmpty()).first.also {
                        if (it) {
                            run.pending = null
                            svc.waitForApp(null, t0)
                            if (s.text?.trim().equals("main", ignoreCase = true)) maps.expectRoot("com.android.settings")
                        }
                    }
                }
                "open_url" -> {
                    // Values inside a link are percent-encoded
                    val url = Templates.fill(s.text, params.mapValues { Commands.enc(it.value) }).orEmpty()
                    filled = filled.copy(text = url)
                    val t0 = SystemClock.uptimeMillis()
                    router.openUrl(url).also {
                        if (it) {
                            noteLinkText(run, url)
                            svc.waitForApp(null, t0)
                        }
                    }
                }
                "wait" -> { delay(2000); true }
                else -> false
            }
            if (!ok) {
                log(run, LogKind.WARN, "تغيّرت الشاشة عن المهارة المحفوظة بعد $i خطوة — سيكمل الذكاء الاصطناعي ويحدّث المهارة")
                trail += "(saved routine: replayed $i of ${skill.steps.size} steps, then the screen differed — continue from here)"
                return i
            }
            run.route += filled
            run.localSteps++
            run.steps++
            pushStats(run)
            trail += "${s.type} ${label ?: text?.take(30) ?: ""} ✓$sentNote"
            log(run, LogKind.ACTION, "⚡ ${actionWord(s.type)} ${label ?: text?.take(30) ?: ""}".trim())
        }
        return skill.steps.size
    }

    private fun actionWord(type: String) = when (type) {
        "tap" -> "ضغط على"
        "long_press" -> "ضغط مطوّل على"
        "type" -> "كتابة"
        "enter" -> "إرسال"
        "scroll" -> "تمرير"
        "open_app" -> "فتح"
        "open_settings" -> "إعدادات"
        "open_url" -> "رابط"
        "back" -> "رجوع"
        "home" -> "الرئيسية"
        "wait" -> "انتظار"
        else -> type
    }

    /**
     * Waits for the element of a saved step. If it is not on screen (a longer list, a smaller display),
     * scrolls down up to twice to look for it — scrolling changes nothing in the app.
     */
    private suspend fun waitFor(svc: AgentAccessibilityService, step: MacroStep, params: Map<String, String>): UiNode? {
        if (step.sig == null && step.label == null) return null
        val mayScroll = step.type == "tap" || step.type == "long_press"
        for (round in 0..2) {
            repeat(if (round == 0) 6 else 3) {
                Learner.locate(svc.readScreen(), step, params)?.let { return it }
                delay(400)
            }
            if (!mayScroll || round == 2) break
            // One page down in the main list (a page scroll never overshoots the way a swipe can)
            val list = svc.readScreen().nodes
                .filter { it.scrollable && !it.cls.contains("Pager") && !it.cls.contains("Horizontal") }
                .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() } ?: break
            svc.markAction()
            if (!list.info.refresh() || !list.info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break
            svc.waitForIdle(300, 1500)
        }
        return null
    }

    // ------------------------------------------------------------------ safe exploration (no API)

    private fun navItems(s: Screen): List<UiNode> {
        val h = s.height
        val w = s.width
        if (h <= 0) return emptyList()
        val cands = s.nodes.filter {
            it.clickable && !it.inList && !it.editable && it.bounds.centerY() > h * 0.86f &&
                it.bounds.height() < h * 0.14f && it.bounds.width() < w * 0.45f
        }
        val row = cands.groupBy { it.bounds.centerY() / maxOf(1, h / 40) }.values.filter { it.size in 3..6 }.maxByOrNull { it.size }
        return row?.sortedBy { it.bounds.left } ?: emptyList()
    }

    private suspend fun exploreApp(run: Run, pkg: String, label: String) {
        val svc = AgentAccessibilityService.instance
            ?: throw Stop(TaskStatus.FAILED, "خدمة إمكانية الوصول غير مفعّلة.")
        if (Safety.policy(pkg, settings, apps) == Policy.BLOCKED) throw Stop(TaskStatus.FAILED, "هذا التطبيق محجوب عن الوكيل، فلا يمكن استكشافه.")
        log(run, LogKind.LOCAL, "استكشاف آمن: يزور التبويبات السفلية فقط، دون كتابة أو إرسال، ودون استهلاك API")
        val t0 = SystemClock.uptimeMillis()
        if (!apps.launch(pkg, fresh = true)) throw Stop(TaskStatus.FAILED, "تعذّر فتح «$label».")
        svc.waitForApp(pkg, t0, 9000)
        maps.expectRoot(pkg)
        delay(500)
        var home = svc.readScreen()
        if (home.pkg != pkg) { delay(1800); home = svc.readScreen() }
        if (home.pkg != pkg) throw Stop(TaskStatus.FAILED, "لم يظهر «$label» في المقدمة (ربما يطلب تسجيل دخول أو إذناً).")
        maps.observe(home)
        val rootKey = AppMapStore.keyOf(home)
        var learned = 1
        val bar = navItems(home)
        if (bar.isEmpty()) log(run, LogKind.INFO, "لا يوجد شريط تبويبات سفلي واضح؛ حُفظت الصفحة الرئيسية فقط")
        for (item in bar.take(6)) {
            currentCoroutineContext().ensureActive()
            run.step++
            awaitScreen(svc, run)
            var scr = svc.readScreen()
            if (scr.pkg != pkg) {
                val t = SystemClock.uptimeMillis()
                apps.launch(pkg)
                svc.waitForApp(pkg, t)
                scr = svc.readScreen()
                if (scr.pkg != pkg) continue
            }
            val node = scr.find(item.signature, item.bounds) ?: continue
            if (Safety.isSensitiveLabel(node.label) || Safety.isTradeLabel(node.label)) continue
            log(run, LogKind.ACTION, "تبويب «${node.label.take(24).ifBlank { "بدون اسم" }}»")
            val fromKey = AppMapStore.keyOf(scr)
            click(svc, node)
            svc.waitForIdle(700, 4000)
            var next = svc.readScreen()
            if (next.pkg != pkg || navItems(next).none { it.signature == item.signature }) {
                // Possibly still mid-transition: look once more before judging what this page is
                delay(900)
                next = svc.readScreen()
            }
            if (next.pkg != pkg) {
                svc.global(AccessibilityService.GLOBAL_ACTION_BACK); svc.waitForIdle()
                if (svc.readScreen().pkg != pkg) {
                    val t = SystemClock.uptimeMillis()
                    apps.launch(pkg)
                    svc.waitForApp(pkg, t)
                }
                continue
            }
            val key = maps.observe(next)
            if (key != null) {
                maps.edge(pkg, fromKey, node.label, key, node.viewId)
                // The tab bar is on the first page too, so every tab is one tap away from it
                if (fromKey != rootKey) maps.edge(pkg, rootKey, node.label, key, node.viewId)
                learned++
            }
            run.localSteps++
            run.steps++
            pushStats(run)
            // A full-screen flow without the bar (camera, composer): step back out
            if (navItems(next).none { it.signature == item.signature }) {
                svc.global(AccessibilityService.GLOBAL_ACTION_BACK); svc.waitForIdle(600, 3000)
            }
        }
        maps.flush()
        svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
        throw Stop(TaskStatus.DONE, "تعرّفتُ على $learned صفحة في «$label» دون أي استهلاك. سأستخدم هذه الخريطة لأخطّط المهام في هذا التطبيق بطلب واحد.")
    }

    // ------------------------------------------------------------------ gates

    private suspend fun confirm(run: Run, risky: Boolean, what: String, canAlways: Boolean = false): Boolean {
        confirmAlways = false
        if (!risky || !settings.confirmSensitive) return true
        val gate = CompletableDeferred<Boolean>()
        confirmGate = gate
        val q = "$what — هل تسمح؟"
        log(run, LogKind.WARN, "بانتظار موافقتك: $what")
        _state.value = EngineState.Confirm(q, canAlways)
        val ok = try {
            withTimeout(120_000) { gate.await() }
        } catch (e: TimeoutCancellationException) {
            false
        }
        confirmGate = null
        running(run, if (ok) "وافقتَ — متابعة" else "رفضتَ — سيبحث عن بديل")
        log(run, if (ok) LogKind.INFO else LogKind.WARN, if (ok) "وافقتَ على: $what" else "رفضتَ: $what")
        return ok
    }

    /**
     * A two-way question that is not a plain permission ("schedule or run now?", "send this old draft?").
     * It is asked whatever the confirmation setting says. Null when nobody answered in time.
     */
    private suspend fun choose(run: Run, question: String, yes: String, no: String, title: String, timeoutMs: Long): Boolean? {
        val gate = CompletableDeferred<Boolean>()
        confirmAlways = false
        confirmGate = gate
        _state.value = EngineState.Confirm(question, canAlways = false, yes = yes, no = no, title = title)
        val answer = try {
            withTimeout(timeoutMs) { gate.await() }
        } catch (e: TimeoutCancellationException) {
            null
        }
        confirmGate = null
        running(run, "متابعة…")
        return answer
    }

    private suspend fun ask(run: Run, question: String): String? {
        val gate = CompletableDeferred<String?>()
        askGate = gate
        log(run, LogKind.WARN, "سؤال لك: $question")
        _state.value = EngineState.Ask(question)
        val ans = try {
            withTimeout(300_000) { gate.await() }
        } catch (e: TimeoutCancellationException) {
            null
        }
        askGate = null
        running(run, "متابعة…")
        if (!ans.isNullOrBlank()) log(run, LogKind.INFO, "ردّك: $ans")
        return ans
    }

    // ------------------------------------------------------------------ primitives

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun global(svc: AgentAccessibilityService, action: Int, name: String): Outcome {
        val ok = svc.global(action)
        return Outcome("$name ${mark(ok)}", abort = !ok, macro = if (ok) MacroStep(name) else null)
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }

    private suspend fun click(svc: AgentAccessibilityService, n: UiNode): Boolean {
        svc.markAction()
        val info = n.info
        if (info.refresh()) {
            clickableAncestor(info)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        return svc.tap(n.bounds.exactCenterX(), n.bounds.exactCenterY())
    }

    /**
     * Long-presses an element. Galleries and file managers often accept the accessibility request and then
     * ignore it — their grids enter selection mode only under a real finger — so when the screen did not
     * react, a real long touch on the same element follows.
     */
    private suspend fun longClick(svc: AgentAccessibilityService, n: UiNode): Boolean {
        svc.markAction()
        val info = n.info
        if (info.refresh() && info.isLongClickable) {
            val before = svc.readScreen().hash
            if (info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                svc.waitForIdle(350, 1500)
                if (svc.readScreen().hash != before) return true
                svc.markAction()
            }
        }
        return svc.tap(n.bounds.exactCenterX(), n.bounds.exactCenterY(), long = true)
    }

    private fun focusedEditable(s: Screen): UiNode? = s.nodes.firstOrNull { it.editable && it.info.isFocused }

    private fun sameText(a: CharSequence?, b: String): Boolean =
        Text.norm(a?.toString().orEmpty()).replace(" ", "") == Text.norm(b).replace(" ", "")

    /**
     * Writes text into a field and checks it really changed. Falls back to select-all + paste,
     * which works in custom fields (Instagram, etc.) that ignore ACTION_SET_TEXT.
     * Returns (success, text now in the field).
     */
    private suspend fun setTextVerified(svc: AgentAccessibilityService, n: UiNode, text: String): Pair<Boolean, String> {
        svc.markAction()
        val info = n.info
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (info.refresh()) {
            info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                delay(300)
                info.refresh()
                if (sameText(info.text, text)) return true to info.text.toString()
            }
        } else {
            svc.tap(n.bounds.exactCenterX(), n.bounds.exactCenterY())
            delay(400)
        }
        // Fallback: focus the field, select everything, paste from clipboard
        val target = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: info.takeIf { it.refresh() }
            ?: return false to ""
        target.refresh()
        val len = target.text?.length ?: 0
        withContext(Dispatchers.Main) {
            runCatching { svc.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("wakeel", text)) }
        }
        delay(150)
        target.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
            },
        )
        target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        delay(350)
        target.refresh()
        val now = target.text?.toString().orEmpty()
        return sameText(now, text) to now
    }

    private fun pressEnter(svc: AgentAccessibilityService, cur: Screen, preferred: UiNode?): Boolean {
        svc.markAction()
        val node = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: preferred?.info
            ?: focusedEditable(cur)?.info
            ?: cur.nodes.firstOrNull { it.editable }?.info
            ?: return false
        return node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private suspend fun scroll(svc: AgentAccessibilityService, n: UiNode?, dir: String): Boolean {
        svc.markAction()
        if (n != null && n.info.refresh()) {
            val act = if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (n.info.performAction(act)) return true
        }
        val (w, h) = svc.screenSize()
        val cx = w / 2f
        val cy = h / 2f
        return when (dir) {
            "up" -> svc.swipe(cx, h * 0.30f, cx, h * 0.72f)
            "left" -> svc.swipe(w * 0.15f, cy, w * 0.85f, cy)
            "right" -> svc.swipe(w * 0.85f, cy, w * 0.15f, cy)
            else -> svc.swipe(cx, h * 0.72f, cx, h * 0.30f)
        }
    }

    private fun actionLabel(a: AgentAction, label: String?): String = when (a.type) {
        ActionType.TAP -> "ضغط على «${label ?: "#${a.id}"}»"
        ActionType.LONG_PRESS -> "ضغط مطوّل على «${label ?: "#${a.id}"}»"
        ActionType.TAP_XY -> "ضغط على نقطة (${a.x}، ${a.y})"
        ActionType.DRAG -> "سحب من (${a.x}، ${a.y}) إلى (${a.x2}، ${a.y2})"
        ActionType.TYPE -> "كتابة «${a.text?.take(40).orEmpty()}»"
        ActionType.ENTER -> "إدخال/إرسال"
        ActionType.SCROLL -> "تمرير ${when (a.dir) { "up" -> "للأعلى"; "left" -> "لليسار"; "right" -> "لليمين"; else -> "للأسفل" }}"
        ActionType.BACK -> "رجوع"
        ActionType.HOME -> "الشاشة الرئيسية"
        ActionType.RECENTS -> "التطبيقات الأخيرة"
        ActionType.NOTIFICATIONS -> "فتح الإشعارات"
        ActionType.OPEN_APP -> "فتح تطبيق «${a.text.orEmpty()}»"
        ActionType.OPEN_SETTINGS -> "فتح إعدادات «${a.text.orEmpty()}» مباشرة"
        ActionType.OPEN_URL -> "فتح رابط مباشر"
        ActionType.WAIT -> "انتظار"
        ActionType.SCREENSHOT -> "طلب لقطة شاشة"
        ActionType.READ -> "قراءة الصفحة كاملة"
        ActionType.ASK_USER -> "سؤال لك"
        ActionType.DONE -> "إنهاء"
        ActionType.FAIL -> "تعذّر"
    }
}
