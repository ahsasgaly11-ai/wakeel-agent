package com.wakeel.agent.agent

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.wakeel.agent.core.ActionType
import com.wakeel.agent.core.AgentAction
import com.wakeel.agent.core.LogEntry
import com.wakeel.agent.core.LogKind
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
    data class Confirm(val question: String) : EngineState
    data class Ask(val question: String) : EngineState
}

data class LiveStats(val calls: Int = 0, val cost: Double = 0.0, val usage: Usage = Usage(), val localSteps: Int = 0)

class AgentEngine(
    private val ctx: Context,
    private val settings: Settings,
    private val history: HistoryStore,
    private val skills: SkillStore,
    private val notes: AppNotes,
    private val apps: AppIndex,
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
    @Volatile private var askGate: CompletableDeferred<String?>? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start(task: String, delayMs: Long = 0) {
        val t = task.trim()
        if (t.isEmpty() || isRunning) return
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            execute(t)
        }
    }

    fun stop() {
        confirmGate?.complete(false)
        askGate?.complete(null)
        job?.cancel()
    }

    fun answerConfirm(allow: Boolean) {
        confirmGate?.complete(allow)
    }

    fun answerAsk(text: String?) {
        askGate?.complete(text)
    }

    // ------------------------------------------------------------------

    private class Run(val task: String) {
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
    }

    private class Stop(val status: TaskStatus, msg: String) : Exception(msg)

    private class Outcome(
        val line: String,
        val abort: Boolean = false,
        val macro: MacroStep? = null,
        val breaksMacro: Boolean = false,
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

    private suspend fun execute(task: String) {
        val run = Run(task)
        _logs.value = emptyList()
        _live.value = LiveStats()
        lastResult.value = null
        running(run, "جارٍ البدء…")
        log(run, LogKind.INFO, "المهمة: $task")
        try {
            val svc = AgentAccessibilityService.instance
                ?: throw Stop(TaskStatus.FAILED, "خدمة إمكانية الوصول غير مفعّلة. فعّلها من الصفحة الرئيسية أولاً.")
            val trail = mutableListOf<String>()

            // 1) A learned skill that matches locally (exact or by pattern) — zero cost
            var match: Pair<Skill, Map<String, String>>? = if (settings.autoLearn) skills.localMatch(task) else null

            // 2) Built-in local commands — zero cost
            if (match == null) {
                when (val r = LocalRouter(ctx, apps).route(task, svc)) {
                    is LocalRouter.Result.Handled -> {
                        run.localSteps++
                        log(run, LogKind.LOCAL, "نُفّذت محلياً دون استهلاك API")
                        throw Stop(TaskStatus.DONE, r.message)
                    }
                    is LocalRouter.Result.Opened -> {
                        run.localSteps++
                        run.route += MacroStep("open_app", text = r.pkg, label = r.app)
                        log(run, LogKind.LOCAL, "فُتح «${r.app}» محلياً دون استهلاك API")
                        trail += "open_app \"${r.app}\" ✓"
                        svc.waitForIdle(600, 4000)
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
                val done = replay(svc, run, sk, p, trail)
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
                } else {
                    skills.record(sk.id, false)
                }
            }

            // 6) Model loop (also learns at the end)
            llmLoop(svc, run, trail, note, activeSkill)
        } catch (s: Stop) {
            run.status = s.status
            run.result = s.message.orEmpty()
            log(run, if (s.status == TaskStatus.DONE) LogKind.SUCCESS else LogKind.ERROR, run.result)
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
                settings.addSpend(run.cost)
                history.add(
                    TaskRecord(
                        run.started, run.task, run.started, System.currentTimeMillis(), run.status,
                        run.calls, run.usage, run.cost, run.steps, run.localSteps, run.result, run.logs.toList(),
                    )
                )
                pushStats(run)
                lastResult.value = run.result
                confirmGate = null
                askGate = null
                _state.value = EngineState.Idle
            }
        }
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

    private fun account(run: Run, model: String, u: Usage) {
        run.calls++
        run.usage += u
        run.cost += settings.cost(model, u)
        pushStats(run)
    }

    private suspend fun llmLoop(svc: AgentAccessibilityService, run: Run, trail: MutableList<String>, note: String?, activeSkill: Skill?) {
        if (settings.apiKey.isBlank()) throw Stop(TaskStatus.FAILED, "أضف مفتاح API من الإعدادات أولاً.")
        if (settings.fastModel.isBlank()) throw Stop(TaskStatus.FAILED, "حدّد اسم النموذج في الإعدادات.")
        val client = LlmClient.create(settings)
        var strong = false
        var stagnant = 0
        var wantShot = false
        val recent = ArrayDeque<String>()
        var escalatedForLoop = false
        val max = settings.maxSteps

        for (step in 1..max) {
            currentCoroutineContext().ensureActive()
            run.step = step
            budgetCheck(run)
            svc.waitForIdle()
            var screen = svc.readScreen()
            if (screen.pkg == ctx.packageName) {
                // Our own window may still be closing (e.g. after answering a question) — give it a moment
                repeat(6) { if (screen.pkg == ctx.packageName) { delay(250); screen = svc.readScreen() } }
                if (screen.pkg == ctx.packageName) {
                    svc.global(AccessibilityService.GLOBAL_ACTION_HOME); svc.waitForIdle(); screen = svc.readScreen()
                }
            }
            Safety.blockReason(screen.pkg, settings, apps)?.let {
                svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                throw Stop(TaskStatus.FAILED, it)
            }

            val needShot = settings.allowScreenshots && !screen.hasPassword && (wantShot || screen.nodes.size < 3)
            val shot = if (needShot) svc.screenshot() else null
            if (shot != null) log(run, LogKind.INFO, "أُرفقت لقطة شاشة مصغّرة")
            wantShot = false

            val useStrong = strong && settings.strongModel.isNotBlank()
            val model = if (useStrong) settings.strongModel else settings.fastModel
            running(run, if (useStrong) "يفكّر بالنموذج الأقوى…" else "يفكّر…")
            val user = Prompt.user(run.task, trail, screen, shot, step, max, note.takeIf { step == 1 }, notes.get(screen.pkg))
            val res = client.decide(model, user, shot)
            account(run, model, res.usage)
            strong = false

            val d = res.decision
            if (d.thought.isNotBlank()) log(run, LogKind.THINK, d.thought)
            if (d.actions.isEmpty()) {
                trail += "(invalid reply — call the act tool with at least one action)"
                stagnant++
                if (stagnant >= 2 && settings.escalate) strong = true
                continue
            }

            val before = screen.hash
            // Loop guard: the same action on the same screen again and again = no progress
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
                throw Stop(TaskStatus.FAILED, "توقّف الوكيل لأنه يكرر نفس الخطوة دون تقدّم، وأوقفته لتوفير الاستهلاك. جرّب صياغة أوضح للمهمة.")
            }
            var waited = false
            val orig = screen
            for ((i, a) in d.actions.withIndex()) {
                currentCoroutineContext().ensureActive()
                when (a.type) {
                    ActionType.DONE -> {
                        val result = a.text ?: "تمّت المهمة."
                        if (activeSkill != null && run.llmActions == 0) {
                            skills.record(activeSkill.id, true)
                            log(run, LogKind.LOCAL, "تأكّدت المهارة «${activeSkill.title}» — المرات القادمة دون أي استهلاك")
                        } else if (settings.autoLearn && run.routeOk && run.route.isNotEmpty()) {
                            learn(client, run, result, screen.pkg)
                        }
                        throw Stop(TaskStatus.DONE, result)
                    }
                    ActionType.FAIL -> throw Stop(TaskStatus.FAILED, a.text ?: "تعذّر إكمال المهمة.")
                    else -> Unit
                }
                val cur = if (i == 0) orig else { svc.waitForIdle(); svc.readScreen() }
                val out = perform(svc, run, a, orig, cur, d.sensitive, shot)
                run.steps++
                run.llmActions++
                trail += out.line
                if (out.macro != null) run.route += out.macro else if (out.breaksMacro) run.routeOk = false
                if (a.type == ActionType.WAIT) waited = true
                if (a.type == ActionType.SCREENSHOT && settings.allowScreenshots) wantShot = true
                if (out.abort) break
            }

            svc.waitForIdle()
            val after = svc.readScreen().hash
            if (after == before && !waited && !wantShot) stagnant++ else stagnant = 0
            if (stagnant >= 2 && settings.escalate) {
                strong = true
                if (settings.allowScreenshots) wantShot = true
                stagnant = 0
                log(run, LogKind.WARN, "تعثّر الوكيل — سيستعين بالنموذج الأقوى ولقطة شاشة للخطوة القادمة")
            }
        }
        throw Stop(TaskStatus.FAILED, "توقّف الوكيل بعد بلوغ الحد الأقصى للخطوات ($max).")
    }

    private suspend fun learn(client: LlmClient, run: Run, result: String, pkg: String) {
        running(run, "يتعلّم من هذه المهمة…")
        val learned = Learner.learn(client, settings.fastModel, run.task, run.route, result, pkg)
        learned?.let { account(run, settings.fastModel, it.usage) }
        if (learned != null && !learned.reusable) return
        val skill = Learner.newSkill(run.task, learned, run.route.toList(), result)
        skills.upsert(skill)
        learned?.notes?.groupBy({ it.first }, { it.second })?.forEach { (p, n) -> notes.add(p, n) }
        val vars = if (skill.params.isEmpty()) "" else " بمتغيّرات: " + skill.params.joinToString("، ")
        log(run, LogKind.LOCAL, "تعلّمتُ مهارة «${skill.title}»$vars — ستُنفَّذ لاحقاً محلياً")
    }

    // ------------------------------------------------------------------

    private suspend fun perform(
        svc: AgentAccessibilityService, run: Run, a: AgentAction,
        orig: Screen, cur: Screen, sensitive: Boolean, shot: Shot?,
    ): Outcome {
        fun target(): UiNode? {
            val o = a.id?.let { orig.byId(it) } ?: return null
            return if (cur === orig) o else cur.find(o.signature, o.bounds)
        }
        val shown = a.id?.let { orig.byId(it)?.label?.take(30) }
        log(run, LogKind.ACTION, actionLabel(a, shown))

        return when (a.type) {
            ActionType.TAP, ActionType.LONG_PRESS -> {
                val n = target() ?: return Outcome("${a.describe()} ✗ element not on screen anymore", abort = true)
                val risky = sensitive || Safety.isSensitiveLabel(n.label)
                if (!confirm(run, risky, if (a.type == ActionType.TAP) "الضغط على «${n.label.take(40)}»" else "الضغط المطوّل على «${n.label.take(40)}»"))
                    return Outcome("${a.describe()} ✗ DENIED by owner — do not retry", abort = true, breaksMacro = true)
                val ok = if (a.type == ActionType.TAP) click(svc, n) else longClick(svc, n)
                Outcome(
                    "${a.describe()} \"${n.label.take(30)}\" ${mark(ok)}", abort = !ok,
                    macro = if (ok) MacroStep(a.type.key, n.signature, label = n.label.take(40), confirm = risky) else null,
                    breaksMacro = !ok,
                )
            }

            ActionType.TAP_XY -> {
                if (shot == null || a.x == null || a.y == null) return Outcome("tap_xy ✗ needs a screenshot and x,y", abort = true)
                if (!confirm(run, sensitive, "الضغط على نقطة في الشاشة")) return Outcome("tap_xy ✗ DENIED by owner", abort = true, breaksMacro = true)
                val ok = svc.tap(a.x * shot.scaleX, a.y * shot.scaleY)
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
                val n = target() ?: focusedEditable(cur) ?: cur.nodes.firstOrNull { it.editable }
                    ?: return Outcome("type ✗ no text field on screen", abort = true)
                if (n.password) return Outcome("type ✗ refused: password field — the owner must type it", abort = true, breaksMacro = true)
                val txt = a.text.orEmpty()
                val (ok, now) = setTextVerified(svc, n, txt)
                Outcome(
                    "type #${n.id} ${mark(ok)} — field now: \"${now.replace("\n", "\\n").take(160)}\"" +
                        if (ok) "" else " (text did not apply; try tapping the field first, or another approach)",
                    abort = !ok,
                    macro = if (ok) MacroStep("type", n.signature, text = txt) else null, breaksMacro = !ok,
                )
            }

            ActionType.ENTER -> {
                if (!confirm(run, sensitive, "إرسال/تأكيد النص المكتوب")) return Outcome("enter ✗ DENIED by owner", abort = true, breaksMacro = true)
                val ok = pressEnter(svc, cur, target())
                Outcome("enter ${mark(ok)}", abort = !ok, macro = if (ok) MacroStep("enter", confirm = sensitive) else null, breaksMacro = !ok)
            }

            ActionType.SCROLL -> {
                val dir = a.dir ?: "down"
                val n = target()?.takeIf { it.scrollable }
                val ok = scroll(svc, cur, n, dir)
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
                val ok = apps.launch(app.pkg)
                if (ok) svc.waitForIdle(600, 4000)
                Outcome(
                    "open_app \"${app.label}\" ${mark(ok)}", abort = true,
                    macro = if (ok) MacroStep("open_app", text = app.pkg, label = app.label) else null,
                )
            }

            ActionType.WAIT -> {
                delay(2000); Outcome("wait ✓", macro = MacroStep("wait"))
            }

            ActionType.SCREENSHOT -> Outcome(
                if (settings.allowScreenshots) "screenshot → will be attached next turn" else "screenshot ✗ disabled by owner — use the element list",
                abort = true,
            )

            ActionType.ASK_USER -> {
                val q = a.text ?: "هل يمكنك التوضيح؟"
                val ans = ask(run, q)
                if (ans.isNullOrBlank()) throw Stop(TaskStatus.STOPPED, "لم يصل ردّك على سؤال الوكيل.")
                Outcome("ask_user \"${q.take(60)}\" → owner: \"${ans.take(120)}\"", abort = true, breaksMacro = true)
            }

            ActionType.DONE, ActionType.FAIL -> Outcome("", abort = true)
        }
    }

    // ------------------------------------------------------------------ replay

    private suspend fun replay(svc: AgentAccessibilityService, run: Run, skill: Skill, params: Map<String, String>, trail: MutableList<String>): Int {
        val vars = if (params.isEmpty()) "" else " (" + params.values.joinToString("، ") { "«$it»" } + ")"
        log(run, LogKind.LOCAL, "مهارة متعلَّمة: «${skill.title}»$vars — تنفيذ محلي دون استهلاك API")
        for ((i, s) in skill.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            run.step = i + 1
            svc.waitForIdle()
            val text = Templates.fill(s.text, params)
            val label = Templates.fill(s.label, params)
            var filled = s.copy(text = text, label = label)
            val ok: Boolean = when (s.type) {
                "tap", "long_press" -> {
                    val n = waitFor(svc, s, params)
                    if (n == null) false
                    else if (!confirm(run, s.confirm || Safety.isSensitiveLabel(n.label), "الضغط على «${n.label.take(40)}»")) {
                        trail += "(owner denied a saved step)"; return i
                    } else {
                        filled = filled.copy(sig = n.signature, label = n.label.take(40))
                        if (s.type == "tap") click(svc, n) else longClick(svc, n)
                    }
                }
                "type" -> {
                    val n = waitFor(svc, s, params) ?: focusedEditable(svc.readScreen()) ?: svc.readScreen().nodes.singleOrNull { it.editable }
                    if (n == null || n.password) false else setTextVerified(svc, n, text.orEmpty()).first
                }
                "enter" -> {
                    if (!confirm(run, s.confirm, "إرسال/تأكيد النص المكتوب")) { trail += "(owner denied a saved step)"; return i }
                    pressEnter(svc, svc.readScreen(), null)
                }
                "scroll" -> {
                    val scr = svc.readScreen()
                    scroll(svc, scr, s.sig?.let { scr.find(it) }, s.dir ?: "down")
                }
                "back" -> svc.global(AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> svc.global(AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> svc.global(AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" -> svc.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                "open_app" -> s.text?.let { pkg ->
                    Safety.blockReason(pkg, settings, apps) == null && apps.launch(pkg).also { if (it) svc.waitForIdle(600, 4000) }
                } ?: false
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
            trail += "${s.type} ${label ?: text?.take(30) ?: ""} ✓"
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
        "back" -> "رجوع"
        "home" -> "الرئيسية"
        "wait" -> "انتظار"
        else -> type
    }

    private suspend fun waitFor(svc: AgentAccessibilityService, step: MacroStep, params: Map<String, String>): UiNode? {
        if (step.sig == null && step.label == null) return null
        repeat(10) {
            Learner.locate(svc.readScreen(), step, params)?.let { return it }
            delay(400)
        }
        return null
    }

    // ------------------------------------------------------------------ gates

    private suspend fun confirm(run: Run, risky: Boolean, what: String): Boolean {
        if (!risky || !settings.confirmSensitive) return true
        val gate = CompletableDeferred<Boolean>()
        confirmGate = gate
        val q = "$what — هل تسمح؟"
        log(run, LogKind.WARN, "بانتظار موافقتك: $what")
        _state.value = EngineState.Confirm(q)
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
        return Outcome("$name ${mark(ok)}", abort = true, macro = if (ok) MacroStep(name) else null)
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
        val info = n.info
        if (info.refresh()) {
            clickableAncestor(info)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        }
        return svc.tap(n.bounds.exactCenterX(), n.bounds.exactCenterY())
    }

    private suspend fun longClick(svc: AgentAccessibilityService, n: UiNode): Boolean {
        val info = n.info
        if (info.refresh() && info.isLongClickable && info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
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
        val node = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: preferred?.info
            ?: focusedEditable(cur)?.info
            ?: cur.nodes.firstOrNull { it.editable }?.info
            ?: return false
        return node.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
    }

    private suspend fun scroll(svc: AgentAccessibilityService, s: Screen, n: UiNode?, dir: String): Boolean {
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
        ActionType.WAIT -> "انتظار"
        ActionType.SCREENSHOT -> "طلب لقطة شاشة"
        ActionType.ASK_USER -> "سؤال لك"
        ActionType.DONE -> "إنهاء"
        ActionType.FAIL -> "تعذّر"
    }
}
