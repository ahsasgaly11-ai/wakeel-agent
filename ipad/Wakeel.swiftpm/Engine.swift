import SwiftUI
import UIKit

/// A yes/no question to the owner.
struct Question: Equatable {
    let id = UUID()
    var title = "مطلوب موافقتك"
    let text: String
    var yes = "سماح"
    var no = "رفض"
    /// The step belongs to a saved skill, so it may be allowed once and for all.
    var canAlways = false
}

enum EngineState: Equatable {
    case idle
    case running(String)
    case confirm(Question)
    case ask(String)
}

/// Something the owner attached to a task: text taken from a file, or a picture.
struct Attachment: Identifiable {
    let id = UUID()
    let name: String
    var text: String?
    var image: Shot?
}

/// Runs a task from the cheapest way to the dearest: a saved skill, a local command, then the model.
@MainActor
final class Engine: ObservableObject {
    @Published private(set) var state: EngineState = .idle
    @Published private(set) var live: [LogEntry] = []
    @Published var last: TaskRecord?
    /// Raised when the agent starts using its browser, so the screen can show it.
    @Published var showBrowser = 0

    let settings: SettingsStore
    let browser: BrowserController
    let history: Store<TaskRecord>
    let skills: Store<Skill>
    let shortcuts: Store<ShortcutItem>
    /// Where a task that names a later time is kept until then.
    var schedule: ((String, Date) -> Void)?
    /// True while the owner is teaching a task by hand — the agent must not act then.
    var isTeaching: () -> Bool = { false }
    /// Opens a link in another app. Replaced by the self-test.
    var opener: (String) async -> Bool = { link in
        guard let u = URL(string: link) else { return false }
        return await UIApplication.shared.open(u)
    }
    /// Self-test: answers questions in the owner's place.
    var autoAnswer: ((Question) -> Bool?)?

    private var job: Task<Void, Never>?
    private var confirmGate: CheckedContinuation<Bool?, Never>?
    private var confirmAlways = false
    private var askGate: CheckedContinuation<String?, Never>?

    init(settings: SettingsStore, browser: BrowserController, history: Store<TaskRecord>, skills: Store<Skill>, shortcuts: Store<ShortcutItem>) {
        self.settings = settings
        self.browser = browser
        self.history = history
        self.skills = skills
        self.shortcuts = shortcuts
    }

    var isRunning: Bool { state != .idle }

    private final class Run {
        let task: String
        let attachments: [Attachment]
        let scheduledFor: Date?
        let started = Date()
        var logs: [LogEntry] = []
        var calls = 0
        var usage = Usage()
        var cost = 0.0
        var steps = 0
        var localSteps = 0
        var memo = ""
        var trail: [String] = []
        /// Everything this task itself put into fields, normalised.
        var typed: [String] = []
        var files: [String] = []
        /// The steps that worked, as a skill would replay them.
        var route: [SkillStep] = []
        var routeOk = true
        var readNext = false
        var readOnlyNoted = false
        init(task: String, attachments: [Attachment], scheduledFor: Date?) {
            self.task = task
            self.attachments = attachments
            self.scheduledFor = scheduledFor
        }
    }

    private struct Stop: Error {
        let status: TaskStatus
        let message: String
    }

    private struct Outcome {
        var line: String
        var abort = false
    }

    // MARK: - control

    /// Returns false when nothing was started (a task is running, or the owner is teaching one).
    @discardableResult
    func start(_ task: String, attachments: [Attachment] = [], scheduledFor: Date? = nil) -> Bool {
        let t = task.trimmed
        if t.isEmpty || isRunning || isTeaching() { return false }
        let run = Run(task: t, attachments: attachments, scheduledFor: scheduledFor)
        live = []
        state = .running("بدء المهمة…")
        job = Task { [weak self] in
            await self?.guarded(run)
        }
        return true
    }

    func stop() {
        confirmGate?.resume(returning: false)
        confirmGate = nil
        askGate?.resume(returning: nil)
        askGate = nil
        job?.cancel()
    }

    func answer(_ yes: Bool, always: Bool = false) {
        confirmAlways = yes && always
        confirmGate?.resume(returning: yes)
        confirmGate = nil
    }

    func reply(_ text: String?) {
        askGate?.resume(returning: text)
        askGate = nil
    }

    // MARK: - bookkeeping

    private func log(_ run: Run, _ kind: LogKind, _ text: String) {
        let e = LogEntry(kind: kind, text: text)
        run.logs.append(e)
        live.append(e)
        if live.count > 60 { live.removeFirst(live.count - 60) }
        if case .running = state, kind != .think { state = .running(text.take(90)) }
    }

    private func guarded(_ run: Run) async {
        var status = TaskStatus.done
        var result = ""
        log(run, .info, "المهمة: \(run.task)")
        do {
            try await execute(run)
        } catch let s as Stop {
            status = s.status
            result = s.message
        } catch is CancellationError {
            status = .stopped
            result = "أوقفتَ المهمة."
        } catch let e as LlmError {
            status = .failed
            result = e.message
        } catch {
            status = Task.isCancelled ? .stopped : .failed
            result = Task.isCancelled ? "أوقفتَ المهمة." : "حدث خطأ غير متوقع: \(error.localizedDescription)"
        }
        log(run, status == .done ? .success : status == .stopped ? .warn : .error, result)
        let record = TaskRecord(task: run.task, startedAt: run.started, endedAt: Date(), status: status, calls: run.calls, usage: run.usage, cost: run.cost,
                                steps: run.steps, localSteps: run.localSteps, result: result, logs: run.logs, files: run.files)
        history.items.insert(record, at: 0)
        if history.items.count > 200 { history.items.removeLast(history.items.count - 200) }
        last = record
        confirmGate = nil
        askGate = nil
        state = .idle
    }

    private func todayCost() -> Double {
        let cal = Calendar.current
        return history.items.filter { cal.isDateInToday($0.startedAt) }.reduce(0) { $0 + $1.cost }
    }

    // MARK: - questions

    /// A question with two answers. Nil when the task was stopped while it waited.
    private func choose(_ run: Run, _ q: Question) async -> Bool? {
        if let auto = autoAnswer { return auto(q) }
        confirmAlways = false
        state = .confirm(q)
        let answer: Bool? = await withCheckedContinuation { (c: CheckedContinuation<Bool?, Never>) in confirmGate = c }
        if Task.isCancelled { return nil }
        state = .running("متابعة…")
        return answer
    }

    /// Permission for a sensitive step. Skipped when the owner turned confirmations off.
    private func confirm(_ run: Run, needed: Bool, _ what: String, canAlways: Bool = false) async -> Bool {
        if !needed || !settings.confirmSensitive { return true }
        log(run, .warn, "بانتظار موافقتك: \(what)")
        let ok = await choose(run, Question(text: what + " — هل تسمح؟", canAlways: canAlways)) ?? false
        log(run, ok ? .info : .warn, ok ? "وافقتَ" : "لم توافق")
        return ok
    }

    private func ask(_ run: Run, _ question: String) async -> String? {
        if autoAnswer != nil { return nil }
        log(run, .warn, "سؤال: \(question)")
        state = .ask(question)
        let answer: String? = await withCheckedContinuation { (c: CheckedContinuation<String?, Never>) in askGate = c }
        if Task.isCancelled { return nil }
        state = .running("متابعة…")
        return answer?.trimmed
    }

    // MARK: - the pipeline

    private func execute(_ run: Run) async throws {
        let task = run.task

        // 0) A task for later waits on the device; the model cannot wait
        if let due = run.scheduledFor {
            log(run, .local, "مهمة مجدولة حان موعدها (\(Schedule.clock(due)))")
        } else if let schedule = schedule, let plan = Schedule.parse(task) {
            log(run, .info, "في المهمة موعد: «\(plan.phrase)»")
            let q = Question(title: "مهمة لوقت لاحق", text: "أنفّذ هذه المهمة \(Schedule.describe(plan.at))؟\n«\(plan.task.take(160))»", yes: "جدولة", no: "نفّذ الآن")
            guard let answer = await choose(run, q) else { throw CancellationError() }
            if answer {
                let at = Schedule.parse(task)?.at ?? plan.at      // counted again from now: answering took a while
                schedule(plan.task, at)
                run.localSteps += 1
                throw Stop(status: .done, message: "جُدولت المهمة \(Schedule.describe(at)). سأنبّهك عند موعدها، وإلغاؤها من الصفحة الرئيسية.")
            }
            log(run, .info, "اخترتَ تنفيذها الآن")
        }

        // 1) A saved skill that fits the task: replayed without the model
        var note: String?
        if let (skill, values) = SkillLogic.find(task, in: skills.items) {
            log(run, .local, "مهارة محفوظة: «\(skill.pattern)» — دون استهلاك")
            let failedAt = try await replay(run, skill, values)
            if failedAt == nil {
                bump(skill.id) { $0.uses += 1; $0.successes += 1 }
                throw Stop(status: .done, message: "نفّذتُ المهارة المحفوظة دون استهلاك. الصفحة أمامك في المتصفح.")
            }
            if skill.taught && skill.successes <= 2 && skill.uses == 0 {
                skills.items.removeAll { $0.id == skill.id }
                log(run, .warn, "المهارة التي علّمتني إياها لم تعمل، فحذفتُها. أكمل المهمة بالنموذج.")
            } else {
                bump(skill.id) { $0.uses += 1; $0.failures += 1 }
                log(run, .warn, "تعثّرت المهارة عند الخطوة \((failedAt ?? 0) + 1). أكمل بالنموذج.")
            }
            note = "A saved routine for this task ran its first \(failedAt ?? 0) steps and then failed. Continue from the page now open."
        }

        // 2) A command the app can do by itself
        if note == nil, let cmd = Commands.parse(task, dial: settings.dialCode, shortcuts: shortcuts.items.map { $0.name }, search: settings.searchURL) {
            switch cmd {
            case .answer(let text):
                run.localSteps += 1
                throw Stop(status: .done, message: text)
            case .browse(let url, let say):
                try checkSite(run, url)
                showBrowser += 1
                log(run, .local, "فتح \(url.take(80)) — دون استهلاك")
                await browser.load(url)
                run.localSteps += 1
                throw Stop(status: .done, message: say)
            case .external(let url, let say):
                log(run, .local, "فتح رابط خارجي — دون استهلاك")
                run.localSteps += 1
                if await opener(url) { throw Stop(status: .done, message: say) }
                throw Stop(status: .failed, message: "لم أستطع فتح الرابط: لا يوجد على هذا الجهاز تطبيق يفتحه.")
            }
        }

        // 3) The model
        guard settings.ready || LLM.override != nil else {
            throw Stop(status: .failed, message: "أضف مفتاح API واسم النموذج من «الإعدادات» أولاً. المهام المحلية (واتساب، بحث، اختصارات، جدولة) تعمل دونه.")
        }
        if todayCost() >= settings.dailyBudget {
            throw Stop(status: .failed, message: "بلغتَ حد الميزانية اليومية (\(String(format: "%.2f", settings.dailyBudget)) دولار). ارفعه من الإعدادات إن أردت المتابعة.")
        }
        try await modelLoop(run, note: note)
    }

    private func bump(_ id: UUID, _ change: (inout Skill) -> Void) {
        if let i = skills.items.firstIndex(where: { $0.id == id }) { change(&skills.items[i]) }
    }

    private func policy(_ address: String) -> Safety.Policy {
        Safety.policy(host: URL(string: address)?.host ?? "", blocked: settings.blockedSites, readOnly: settings.readOnlySites)
    }

    private func checkSite(_ run: Run, _ address: String) throws {
        if policy(address) == .blocked {
            throw Stop(status: .failed, message: "هذا الموقع في قائمة المواقع الممنوعة على الوكيل (\(URL(string: address)?.host ?? address)). افتحه بنفسك، أو عدّل القائمة من الإعدادات.")
        }
    }

    // MARK: - the model loop

    private func modelLoop(_ run: Run, note: String?) async throws {
        let client = LLM.create(settings)
        if let n = note { run.trail.append(n) }
        var recent: [String] = []
        var stagnant = 0
        var strong = false
        var wantShot = false
        let maxSteps = settings.maxSteps

        for step in 1...maxSteps {
            try Task.checkCancellation()
            let page = await browser.snapshot()
            if let p = page {
                switch policy(p.url) {
                case .blocked:
                    throw Stop(status: .failed, message: "وصلتُ إلى موقع ممنوع على الوكيل (\(p.host)) فتوقّفت.")
                case .readOnly:
                    if !run.readOnlyNoted {
                        run.readOnlyNoted = true
                        run.trail.append("NOTE: \(p.host) is a READ-ONLY site: search and read only, never act.")
                        log(run, .warn, "\(p.host) موقع للقراءة فقط: أقرأ وأحلّل ولا أنفّذ.")
                    }
                case .allowed:
                    break
                }
            }

            var user = "TASK: \(run.task)\nNOW: \(Engine.nowText())\n"
            let texts = run.attachments.compactMap { a in a.text.map { "[\(a.name)]\n\($0.take(9000))" } }
            if !texts.isEmpty { user += "ATTACHMENTS:\n" + texts.joined(separator: "\n\n") + "\n" }
            let pictures = run.attachments.filter { $0.image != nil }
            if !pictures.isEmpty { user += "ATTACHED PICTURES: " + pictures.map { $0.name }.joined(separator: ", ") + (step == 1 ? " (shown)" : " (shown earlier; rely on MEMO)") + "\n" }
            if !shortcuts.items.isEmpty {
                user += "SHORTCUTS:\n" + shortcuts.items.map { "- \($0.name)\($0.about.isEmpty ? "" : " — " + $0.about)\($0.takesInput ? " (takes text)" : "")" }.joined(separator: "\n") + "\n"
            }
            if !run.files.isEmpty { user += "FILES CREATED: " + run.files.joined(separator: ", ") + "\n" }
            if !run.memo.isEmpty { user += "MEMO: \(run.memo.take(3000))\n" }
            if !run.trail.isEmpty { user += "LOG:\n" + run.trail.suffix(14).joined(separator: "\n") + "\n" }
            if let p = page { user += p.render() } else { user += "PAGE (no page is open in the browser)\n" }
            if run.readNext {
                run.readNext = false
                user += "FULL PAGE TEXT:\n" + (await browser.pageText()).take(9000) + "\n"
            }
            if step == maxSteps { user += "FINAL STEP: this is your last reply. Call done with the best answer you have, or fail.\n" }

            var shots: [Shot] = step == 1 ? pictures.compactMap { $0.image } : []
            if wantShot, settings.allowScreenshots, page != nil, let shot = await browser.screenshot() {
                shots.append(shot)
                user += "A screenshot of the page is attached.\n"
                wantShot = false
            }

            let useStrong = strong && !settings.strongModel.isEmpty
            let model = useStrong ? settings.strongModel : settings.fastModel
            if case .running = state { state = .running(step == 1 ? "أفكّر في الخطة…" : "أفكّر في الخطوة التالية…") }
            let res = try await client.callTool(model: model, system: Prompt.system, user: user, shots: shots, tool: Prompt.actTool(), maxTokens: 4096, extraSystem: nil)
            run.calls += 1
            run.usage = run.usage + res.usage
            run.cost += settings.cost(res.usage, strong: useStrong)
            if run.cost > settings.taskBudget {
                throw Stop(status: .failed, message: "توقّفتُ لأن المهمة بلغت حد ميزانيتها (\(String(format: "%.2f", settings.taskBudget)) دولار)." + partial(run))
            }

            let d = AgentDecision(res.input)
            if !d.thought.isEmpty { log(run, .think, d.thought) }
            if !d.memo.isEmpty { run.memo = d.memo }
            guard let first = d.actions.first else {
                run.trail.append("(your reply had no actions — call act with at least one action)")
                stagnant += 1
                if stagnant >= 3 { throw Stop(status: .failed, message: "لم يُعطِ النموذج خطوات قابلة للتنفيذ. جرّب نموذجاً أقوى من الإعدادات." + partial(run)) }
                continue
            }

            // The same first step on the same page, again and again: stop and say which
            let before = page?.hash ?? ""
            recent.append(first.describe() + "|" + before)
            if recent.count > 6 { recent.removeFirst() }
            if recent.filter({ $0 == recent.last }).count >= 3 {
                if !useStrong && settings.escalate && !settings.strongModel.isEmpty {
                    strong = true
                    wantShot = true
                    recent.removeAll()
                    log(run, .warn, "الخطوة تتكرر — أنتقل إلى النموذج الأقوى")
                    run.trail.append("You repeated the same step with no effect. Change approach.")
                    continue
                }
                var target = first.find?.take(30)
                if target == nil, let i = first.id { target = page?.byId(i)?.label.take(30) }
                let what = Engine.actionLabel(first, target)
                let host = page?.host ?? ""
                throw Stop(status: .failed, message: "توقّفتُ لأن خطوة واحدة تتكرر والصفحة لا تتغيّر: \(what)\(host.isEmpty ? "" : " في \(host)").\n" +
                    "ما يفيد الآن: اذكر لي الخطوات بالترتيب وبأسماء الأزرار كما تراها، أو علّمني المهمة بيدك مرة واحدة من تبويب «المتصفح»." + partial(run))
            }

            // Carry out the plan, stopping at the first step that fails
            var current = page
            var changed = false
            for (i, action) in d.actions.enumerated() {
                try Task.checkCancellation()
                let isLastCommit = d.sensitive && i == (d.actions.lastIndex { ["tap", "enter", "type", "select"].contains($0.type) } ?? -1)
                let out = try await perform(run, action, page: changed ? nil : current, sensitive: isLastCommit)
                run.steps += 1
                run.trail.append(out.line)
                log(run, out.line.contains("✗") ? .warn : .action, Engine.actionLabel(action, nil) + (out.line.contains("✗") ? " ✗" : ""))
                if out.abort { break }
                // After these the page may be another one: ids from the old listing no longer count
                if ["open_url", "search", "tap", "enter", "select", "back"].contains(action.type) || (action.type == "type" && action.enter) { changed = true; current = nil }
            }

            let after = await browser.snapshot()
            if (after?.hash ?? "") == before && d.actions.allSatisfy({ ["tap", "type", "enter", "select", "scroll", "wait"].contains($0.type) }) {
                stagnant += 1
                run.trail.append("(no change on the page after that plan)")
                if stagnant >= 2 && settings.escalate {
                    strong = true
                    wantShot = true
                }
            } else {
                stagnant = 0
            }
        }
        throw Stop(status: .failed, message: "بلغتُ الحد الأقصى للطلبات (\(maxSteps)) دون إتمام المهمة." + partial(run))
    }

    private func partial(_ run: Run) -> String {
        var s = ""
        if !run.memo.isEmpty { s += "\n\nما جمعتُه حتى الآن:\n" + run.memo.take(1200) }
        if !run.files.isEmpty { s += "\n\nالملفات التي أنشأتُها: " + run.files.joined(separator: "، ") }
        return s
    }

    static func nowText() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "EEEE yyyy-MM-dd HH:mm"
        return f.string(from: Date()) + " (\(TimeZone.current.identifier))"
    }

    /// A step in the owner's words, for the mission log.
    static func actionLabel(_ a: AgentAction, _ target: String?) -> String {
        let t = target ?? a.find ?? ""
        let name = t.isEmpty ? "عنصر" : "«\(t.take(40))»"
        switch a.type {
        case "open_url": return "فتح \((a.text ?? "").take(60))"
        case "search": return "بحث عن «\((a.text ?? "").take(50))»"
        case "tap": return "ضغط على \(name)"
        case "type": return "كتابة «\((a.text ?? "").take(50))»"
        case "enter": return "ضغط Enter"
        case "select": return "اختيار «\((a.text ?? "").take(40))»"
        case "scroll": return a.dir == "up" ? "تمرير للأعلى" : "تمرير للأسفل"
        case "back": return "رجوع"
        case "wait": return "انتظار"
        case "read": return "قراءة الصفحة"
        case "create_file": return "إنشاء ملف \(a.kind ?? "") «\((a.name ?? "").take(40))»"
        case "run_shortcut": return "تشغيل الاختصار «\((a.name ?? a.text ?? "").take(40))»"
        case "open_link": return "فتح رابط في تطبيق آخر"
        case "ask_user": return "سؤالك"
        case "done": return "إنهاء المهمة"
        case "fail": return "تعذّر"
        default: return a.type
        }
    }

    // MARK: - one step

    /// True when [draft] is this task's own text: it typed it, a link it opened carried it, or the task names it.
    private func ownText(_ run: Run, _ draft: String) -> Bool {
        let d = TextTools.norm(draft)
        if d.count < 2 { return true }
        if run.typed.contains(where: { $0 == d || $0.contains(d) || (!$0.isEmpty && d.contains($0) && d.count - $0.count <= 2) }) { return true }
        return (" " + TextTools.norm(run.task) + " ").contains(" " + d + " ")
    }

    /// Resolves the element an action points at: by id on the page the plan was made for, by label anywhere otherwise.
    private func target(_ a: AgentAction, page: PageSnapshot?, editable: Bool) async -> PageElement? {
        if let f = a.find {
            for attempt in 0..<8 {
                if let id = await browser.find(f, editable: editable), let e = await browser.info(id) { return e }
                if attempt == 0 { await browser.waitIdle(maxMs: 4000) } else { try? await Task.sleep(nanoseconds: 400_000_000) }
                if Task.isCancelled { return nil }
            }
            return nil
        }
        if let i = a.id, let e = page?.byId(i) { return e }
        if editable {
            let snap = await browser.snapshot()
            let fields = snap?.elements.filter { $0.editable && !$0.password } ?? []
            if fields.count == 1 { return fields[0] }
        }
        return nil
    }

    private func perform(_ run: Run, _ a: AgentAction, page: PageSnapshot?, sensitive: Bool) async throws -> Outcome {
        let readOnly = browser.hasPage && policy(browser.webView.url?.absoluteString ?? "") == .readOnly
        switch a.type {
        case "open_url", "search":
            let raw = (a.text ?? "").trimmed
            if raw.isEmpty { return Outcome(line: "\(a.type) ✗ no address given", abort: true) }
            var address = a.type == "search" ? settings.searchURL + Commands.enc(raw) : raw
            if a.type == "open_url" {
                if !address.contains("://") && !address.hasPrefix("about:") {
                    if address.hasPrefix("tel:") || address.hasPrefix("mailto:") || address.hasPrefix("sms:") { return try await openLink(run, address) }
                    address = "https://" + address
                }
                let scheme = address.components(separatedBy: "://").first?.lowercased() ?? ""
                if scheme != "http" && scheme != "https" { return try await openLink(run, address) }
                if address.lowercased().contains("://wa.me/") { return try await openLink(run, address) }
            }
            try checkSite(run, address)
            showBrowser += 1
            let ok = await browser.load(address)
            run.typed.append(TextTools.norm(address.removingPercentEncoding ?? address))
            if ok { run.route.append(SkillStep(type: "open", text: address)) } else { run.routeOk = false }
            return Outcome(line: ok ? "\(a.describe()) ✓" : "\(a.describe()) ✗ the page did not load", abort: !ok)

        case "tap":
            guard let e = await target(a, page: page, editable: false) else {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ not found on the page — plan stopped here", abort: true)
            }
            if e.disabled { run.routeOk = false; return Outcome(line: "\(a.describe()) ✗ the element is disabled", abort: true) }
            if readOnly && Safety.isTradeLabel(e.label) {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ REFUSED: read-only site — acting on money or orders is not allowed. Do not retry.", abort: true)
            }
            let risky = sensitive || Safety.isSensitiveLabel(e.label)
            var what = "الضغط على «\(e.label.take(40))»"
            if risky && Safety.isSendLabel(e.label) {
                // What sits in the input fields now is what will go out
                let snap = await browser.snapshot()
                let drafts = snap?.elements.filter { $0.editable && !$0.password && !$0.value.isEmpty }.map { $0.value } ?? []
                if let stale = drafts.first(where: { !ownText(run, $0) }) {
                    log(run, .warn, "في حقل الكتابة نص لم أكتبه في هذه المهمة: «\(stale.take(60))»")
                    let q = Question(title: "نص قديم في حقل الكتابة", text: "في حقل الكتابة نص لم أكتبه أنا في هذه المهمة:\n«\(stale.take(200))»\nهل أرسله كما هو؟", yes: "أرسله", no: "لا ترسله")
                    if await choose(run, q) != true {
                        log(run, .warn, "لم يُرسل النص القديم")
                        run.routeOk = false
                        return Outcome(line: "\(a.describe()) ✗ NOT SENT: the field held an older text \"\(stale.take(60))\" that this task did not type, and the owner refused to send it. Type the text TASK asks for (type replaces the field), then send; if TASK gives none, ask_user.", abort: true)
                    }
                    what = ""
                } else if let d = drafts.first {
                    what += "\nالنص: «\(d.take(200))»"
                }
                // The id came from an older listing: find the button again on the page as it is now
                if !what.isEmpty, !(await confirm(run, needed: true, what)) {
                    run.routeOk = false
                    return Outcome(line: "\(a.describe()) ✗ DENIED by owner — do not retry", abort: true)
                }
                var found = await browser.find(e.sel.isEmpty ? e.label : e.sel)
                if found == nil { found = await browser.find(e.label) }
                guard let again = found else {
                    run.routeOk = false
                    return Outcome(line: "\(a.describe()) ✗ not found on the page any more", abort: true)
                }
                let ok = await browser.tap(again)
                await browser.waitIdle(maxMs: 8000)
                if ok { run.route.append(SkillStep(type: "tap", label: e.label, sel: e.sel, confirm: true)) } else { run.routeOk = false }
                return Outcome(line: ok ? "\(a.describe()) ✓ SENT" : "\(a.describe()) ✗ the tap did not go through", abort: !ok)
            }
            if !(await confirm(run, needed: risky, what)) {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ DENIED by owner — do not retry", abort: true)
            }
            var id = e.id
            if risky, let again = await browser.find(e.sel.isEmpty ? e.label : e.sel) { id = again }     // the listing was re-read while asking
            let ok = await browser.tap(id)
            await browser.waitIdle(maxMs: 8000)
            if ok { run.route.append(SkillStep(type: "tap", label: e.label, sel: e.sel, confirm: risky)) } else { run.routeOk = false }
            return Outcome(line: ok ? "\(a.describe()) ✓" : "\(a.describe()) ✗ the element is gone", abort: !ok)

        case "type":
            guard let e = await target(a, page: page, editable: true), e.editable else {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ no such input field on the page — plan stopped here", abort: true)
            }
            if e.password {
                run.routeOk = false
                return Outcome(line: "type ✗ REFUSED: this is a password field. Never type secrets; ask_user to sign in themselves in the browser tab.", abort: true)
            }
            if readOnly && !Safety.isSearchLabel(e.label + " " + e.sel) {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ REFUSED: read-only site — only search fields may be typed in", abort: true)
            }
            let text = a.text ?? ""
            run.typed.append(TextTools.norm(text))
            let r = await browser.type(e.id, text)
            if !r.ok {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ the text did not go in (field now: \"\(r.now.take(60))\")", abort: true)
            }
            run.route.append(SkillStep(type: "type", label: e.label, sel: e.sel, text: text))
            if a.enter {
                if !(await confirm(run, needed: sensitive, "إرسال النص المكتوب\nالنص: «\(text.take(200))»")) {
                    run.routeOk = false
                    return Outcome(line: "enter ✗ DENIED by owner — do not retry", abort: true)
                }
                _ = await browser.enter(e.id)
                await browser.waitIdle(maxMs: 8000)
                run.route.append(SkillStep(type: "enter", label: e.label, sel: e.sel, confirm: sensitive))
                return Outcome(line: "\(a.describe()) + enter ✓")
            }
            await browser.waitIdle(maxMs: 1500)
            return Outcome(line: "\(a.describe()) ✓")

        case "enter":
            let e = await target(a, page: page, editable: true)
            if !(await confirm(run, needed: sensitive, "إرسال/تأكيد النص المكتوب" + ((e?.value.isEmpty ?? true) ? "" : "\nالنص: «\(e?.value.take(200) ?? "")»"))) {
                run.routeOk = false
                return Outcome(line: "enter ✗ DENIED by owner — do not retry", abort: true)
            }
            let ok = await browser.enter(e?.id)
            await browser.waitIdle(maxMs: 8000)
            if ok { run.route.append(SkillStep(type: "enter", label: e?.label ?? "", sel: e?.sel ?? "", confirm: sensitive)) } else { run.routeOk = false }
            return Outcome(line: ok ? "enter ✓" : "enter ✗ nothing to press Enter in", abort: !ok)

        case "select":
            guard let e = await target(a, page: page, editable: false) else {
                run.routeOk = false
                return Outcome(line: "\(a.describe()) ✗ no such list on the page", abort: true)
            }
            let r = await browser.select(e.id, a.text ?? "")
            await browser.waitIdle(maxMs: 3000)
            if r == "ok" { run.route.append(SkillStep(type: "select", label: e.label, sel: e.sel, text: a.text ?? "")) } else { run.routeOk = false }
            return Outcome(line: r == "ok" ? "\(a.describe()) ✓" : "\(a.describe()) ✗ \(r)", abort: r != "ok")

        case "scroll":
            let moved = await browser.scroll(down: a.dir != "up")
            await browser.waitIdle(maxMs: 2500)
            return Outcome(line: moved ? "scroll \(a.dir ?? "down") ✓" : "scroll \(a.dir ?? "down") ✗ no change (the end of the page)", abort: !moved)

        case "back":
            await browser.goBack()
            run.routeOk = false
            return Outcome(line: "back ✓")

        case "wait":
            try await Task.sleep(nanoseconds: 1_500_000_000)
            await browser.waitIdle(maxMs: 4000)
            return Outcome(line: "wait ✓")

        case "read":
            run.readNext = true
            return Outcome(line: "read ✓ (the full text is in this turn's prompt)", abort: true)

        case "create_file":
            run.routeOk = false
            let kind = a.kind ?? ""
            guard ["docx", "pptx", "xlsx"].contains(kind), let spec = a.spec, !spec.isEmpty else {
                return Outcome(line: "create_file ✗ kind must be docx, pptx or xlsx and spec must hold the content", abort: true)
            }
            let name = (a.name ?? J.str(spec["title"]) ?? "ملف").trimmed
            let data: Data
            switch kind {
            case "docx": data = Docx.build(spec)
            case "pptx": data = Pptx.build(spec, defaultTheme: settings.deckTheme)
            default: data = Xlsx.build(spec)
            }
            let url = FileBox.freshURL(name: name, ext: kind)
            do { try data.write(to: url, options: .atomic) } catch {
                return Outcome(line: "create_file ✗ could not save the file: \(error.localizedDescription)", abort: true)
            }
            run.files.append(url.lastPathComponent)
            log(run, .success, "أنشأتُ الملف «\(url.lastPathComponent)» (\(max(1, data.count / 1024)) ك.ب)")
            return Outcome(line: "create_file ✓ saved \"\(url.lastPathComponent)\" (\(data.count / 1024) KB)")

        case "run_shortcut":
            let name = (a.name ?? a.text ?? "").trimmed
            guard let item = shortcuts.items.first(where: { TextTools.norm($0.name) == TextTools.norm(name) }) else {
                return Outcome(line: "run_shortcut ✗ \"\(name)\" is not one of the owner's registered Shortcuts", abort: true)
            }
            if !(await confirm(run, needed: true, "تشغيل الاختصار «\(item.name)»")) { return Outcome(line: "run_shortcut ✗ DENIED by owner — do not retry", abort: true) }
            let input = a.name != nil ? a.text : nil
            if await opener(Commands.shortcutURL(item.name, input: input)) {
                throw Stop(status: .done, message: "شغّلتُ الاختصار «\(item.name)». يكمل عمله في تطبيق الاختصارات.")
            }
            return Outcome(line: "run_shortcut ✗ could not open the Shortcuts app", abort: true)

        case "open_link":
            return try await openLink(run, (a.text ?? "").trimmed)

        case "ask_user":
            let q = (a.text ?? "").trimmed
            guard let answer = await ask(run, q.isEmpty ? "أحتاج توضيحاً للمتابعة." : q), !answer.isEmpty else {
                if Task.isCancelled { throw CancellationError() }
                throw Stop(status: .stopped, message: "توقّفتُ لأن السؤال بقي دون جواب: \(q)")
            }
            log(run, .info, "جوابك: \(answer.take(120))")
            run.typed.append(TextTools.norm(answer))
            return Outcome(line: "ask_user \"\(q.take(80))\" → OWNER ANSWERED: \(answer)", abort: true)

        case "done":
            learn(run)
            throw Stop(status: .done, message: (a.text ?? "").trimmed.isEmpty ? "تمّت المهمة." : (a.text ?? "").trimmed)

        case "fail":
            throw Stop(status: .failed, message: ((a.text ?? "").trimmed.isEmpty ? "تعذّرت المهمة." : (a.text ?? "").trimmed) + partial(run))

        default:
            return Outcome(line: "\(a.type) ✗ unknown action", abort: true)
        }
    }

    /// Hands over to another app. The task ends here: the app goes to the background and the owner finishes there.
    private func openLink(_ run: Run, _ raw: String) async throws -> Outcome {
        let link = Dial.waLink(raw, code: settings.dialCode)
        if link.isEmpty { return Outcome(line: "open_link ✗ no address given", abort: true) }
        if await opener(link) {
            let lower = link.lowercased()
            let say: String
            if lower.contains("wa.me") || lower.hasPrefix("whatsapp") { say = "فتحتُ محادثة واتساب والنص جاهز — راجعه واضغط إرسال." }
            else if lower.hasPrefix("tel:") { say = "بدأتُ الاتصال." }
            else if lower.hasPrefix("mailto:") { say = "فتحتُ رسالة البريد جاهزة — راجعها واضغط إرسال." }
            else if lower.hasPrefix("sms:") { say = "فتحتُ الرسالة جاهزة — راجعها واضغط إرسال." }
            else { say = "فتحتُ الرابط في تطبيقه." }
            throw Stop(status: .done, message: say)
        }
        return Outcome(line: "open_link ✗ no app on this iPad opens \(link.take(60))", abort: true)
    }

    // MARK: - skills

    /// Keeps the route of a web task that worked, so the same task later costs nothing.
    private func learn(_ run: Run) {
        guard settings.autoLearn, run.routeOk, run.files.isEmpty else { return }
        let steps = run.route
        guard steps.first?.type == "open", steps.filter({ $0.type != "open" }).count >= 1, steps.count <= 14 else { return }
        if steps.last?.type == "type" { return }
        let g = SkillLogic.generalize(task: run.task, steps: steps)
        if skills.items.contains(where: { TextTools.norm($0.pattern) == TextTools.norm(g.pattern) }) { return }
        let site = URL(string: steps.first?.text ?? "")?.host ?? ""
        skills.items.insert(Skill(pattern: g.pattern, params: g.params, steps: g.steps, site: site, successes: 1), at: 0)
        log(run, .success, "تعلّمتُ هذه المهمة: «\(g.pattern)» — تُعاد بعد الآن دون استهلاك")
    }

    /// Replays a skill. Returns nil when every step worked, otherwise the index of the step that failed.
    private func replay(_ run: Run, _ skill: Skill, _ values: [String: String]) async throws -> Int? {
        showBrowser += 1
        for (index, s) in skill.steps.enumerated() {
            try Task.checkCancellation()
            let label = SkillLogic.fill(s.label, values)
            var ok = false
            switch s.type {
            case "open":
                let address = SkillLogic.fill(s.text, values, encode: true)
                try checkSite(run, address)
                log(run, .local, "فتح \(address.take(70))")
                ok = await browser.load(address)
            case "tap":
                if let id = await locate(label: label, sel: s.sel, editable: false) {
                    let name = label.isEmpty ? "هذا الزر" : "«\(label.take(40))»"
                    var what = "الضغط على \(name)"
                    if s.confirm && Safety.isSendLabel(label), let snap = await browser.snapshot(),
                       let draft = snap.elements.first(where: { $0.editable && !$0.password && !$0.value.isEmpty })?.value {
                        what += "\nالنص: «\(draft.take(200))»"
                    }
                    if !(await confirm(run, needed: s.confirm && !s.approved, what, canAlways: true)) {
                        throw Stop(status: .stopped, message: "توقّفتُ قبل \(name) لأنك لم توافق.")
                    }
                    if confirmAlways {
                        confirmAlways = false
                        bump(skill.id) { if index < $0.steps.count { $0.steps[index].approved = true } }
                    }
                    let again = (s.confirm && !s.approved) ? (await locate(label: label, sel: s.sel, editable: false) ?? id) : id
                    log(run, .local, "ضغط على \(name)")
                    ok = await browser.tap(again)
                    await browser.waitIdle(maxMs: 8000)
                }
            case "type", "select":
                let text = SkillLogic.fill(s.text, values)
                if let id = await locate(label: label, sel: s.sel, editable: s.type == "type") {
                    run.typed.append(TextTools.norm(text))
                    log(run, .local, s.type == "type" ? "كتابة «\(text.take(50))»" : "اختيار «\(text.take(40))»")
                    if s.type == "type" { ok = (await browser.type(id, text)).ok } else { ok = (await browser.select(id, text)) == "ok" }
                    await browser.waitIdle(maxMs: 1500)
                }
            case "enter":
                let id = await locate(label: label, sel: s.sel, editable: true)
                if !(await confirm(run, needed: s.confirm && !s.approved, "إرسال النص المكتوب", canAlways: true)) {
                    throw Stop(status: .stopped, message: "توقّفتُ قبل الإرسال لأنك لم توافق.")
                }
                if confirmAlways {
                    confirmAlways = false
                    bump(skill.id) { if index < $0.steps.count { $0.steps[index].approved = true } }
                }
                log(run, .local, "ضغط Enter")
                ok = await browser.enter(id)
                await browser.waitIdle(maxMs: 8000)
            default:
                ok = true
            }
            if !ok { return index }
            run.steps += 1
            run.localSteps += 1
        }
        return nil
    }

    /// Finds a skill's element: by its label, then by its CSS hint, giving the page a few seconds to show it.
    private func locate(label: String, sel: String, editable: Bool) async -> Int? {
        for attempt in 0..<10 {
            if !label.isEmpty, let id = await browser.find(label, editable: editable) { return id }
            if !sel.isEmpty, let id = await browser.find(sel, editable: false) { return id }
            if label.isEmpty && sel.isEmpty && editable {
                let fields = (await browser.snapshot())?.elements.filter { $0.editable && !$0.password } ?? []
                if fields.count == 1 { return fields[0].id }
            }
            if Task.isCancelled { return nil }
            if attempt == 0 { await browser.waitIdle(maxMs: 3000) } else { try? await Task.sleep(nanoseconds: 400_000_000) }
        }
        return nil
    }
}
