import SwiftUI
import UserNotifications

/// Keeps the tasks that wait for their time.
///
/// iPadOS does not let an app work in the background, so a task cannot start by itself while the app is
/// closed. At its time a notification is shown; the task starts when the app is open — at once if it
/// already is, otherwise when the owner taps the notification. A task still waiting two hours after
/// its time is dropped and reported.
@MainActor
final class Scheduler: ObservableObject {
    let store = Store<Scheduled>("schedule")
    private let engine: Engine
    private let history: Store<TaskRecord>
    private var timer: Timer?
    /// Self-test: no system permission prompt.
    var quiet = false
    static let lateLimit: TimeInterval = 2 * 3600

    init(engine: Engine, history: Store<TaskRecord>) {
        self.engine = engine
        self.history = history
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    func add(_ task: String, at: Date) {
        let item = Scheduled(task: task.trimmed, at: at)
        store.items = (store.items + [item]).sorted { $0.at < $1.at }
        notify(item)
    }

    func cancel(_ id: UUID) {
        store.items.removeAll { $0.id == id }
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id.uuidString])
    }

    private func notify(_ item: Scheduled) {
        if quiet { return }
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            let content = UNMutableNotificationContent()
            content.title = "حان موعد مهمتك"
            content.body = item.task + " — اضغط لتبدأ"
            content.sound = .default
            let delay = max(1, item.at.timeIntervalSinceNow)
            let request = UNNotificationRequest(identifier: item.id.uuidString, content: content, trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false))
            center.add(request)
        }
    }

    /// Starts whatever is due, one at a time. Called every second while the app is open.
    func tick() {
        let now = Date()
        guard let item = store.items.first(where: { $0.at <= now }) else { return }
        if now.timeIntervalSince(item.at) > Scheduler.lateLimit {
            store.items.removeAll { $0.id == item.id }
            history.items.insert(TaskRecord(task: item.task, startedAt: now, endedAt: now, status: .failed, calls: 0, usage: Usage(), cost: 0, steps: 0, localSteps: 0,
                                            result: "فات موعد هذه المهمة المجدولة (\(Schedule.clock(item.at))) بأكثر من ساعتين والتطبيق مغلق، فلم أنفّذها. اطلبها من جديد إن شئت.", logs: []), at: 0)
            return
        }
        if engine.start(item.task, scheduledFor: item.at) {
            store.items.removeAll { $0.id == item.id }
            UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [item.id.uuidString])
        }
    }
}

/// Records what the owner does in the browser while teaching a task, and turns it into a skill.
@MainActor
final class Teacher: ObservableObject {
    @Published private(set) var active = false
    @Published private(set) var phrase = ""
    @Published private(set) var steps: [SkillStep] = []
    /// What happened to the last demonstration, for the screen to show once.
    @Published var outcome: String?
    private var sawSecret = false
    private let browser: BrowserController
    private let skills: Store<Skill>

    init(browser: BrowserController, skills: Store<Skill>) {
        self.browser = browser
        self.skills = skills
    }

    func start(_ phrase: String) async {
        self.phrase = phrase.trimmed
        steps = []
        sawSecret = false
        outcome = nil
        active = true
        if browser.hasPage, let u = browser.webView.url?.absoluteString, u != "about:blank" { steps.append(SkillStep(type: "open", text: u)) }
        browser.onRecord = { [weak self] o in self?.note(o) }
        await browser.setRecording(true)
    }

    /// The owner typed an address while teaching: the skill starts there.
    func opened(_ address: String) {
        guard active else { return }
        if steps.isEmpty || steps.allSatisfy({ $0.type == "open" }) { steps = [SkillStep(type: "open", text: address)] }
    }

    private func note(_ o: JSON) {
        guard active else { return }
        let label = J.str(o["label"]) ?? ""
        let sel = J.str(o["sel"]) ?? ""
        switch J.str(o["k"]) ?? "" {
        case "secret":
            sawSecret = true
        case "tap":
            if steps.isEmpty, let u = browser.webView.url?.absoluteString { steps.append(SkillStep(type: "open", text: u)) }
            steps.append(SkillStep(type: "tap", label: label, sel: sel, confirm: Safety.isSensitiveLabel(label)))
        case "type", "select":
            let kind = J.str(o["k"]) ?? "type"
            let text = J.str(o["text"]) ?? ""
            // Typing again in the same field replaces what was noted for it
            if let last = steps.last, last.type == kind, last.label == label, last.sel == sel { steps.removeLast() }
            if !text.isEmpty { steps.append(SkillStep(type: kind, label: label, sel: sel, text: text)) }
        case "enter":
            steps.append(SkillStep(type: "enter", label: label, sel: sel))
        default:
            break
        }
    }

    /// Ends the demonstration. With [save] the steps become a skill — unless they are not worth keeping.
    func stop(save: Bool) async {
        guard active else { return }
        active = false
        browser.onRecord = nil
        await browser.setRecording(false)
        guard save else { outcome = "أُلغي التسجيل، ولم يُحفظ شيء."; return }
        if let why = SkillLogic.incomplete(steps, sawSecret: sawSecret) {
            outcome = "لم أحفظ هذه المهارة: \(why)"
            return
        }
        let g = SkillLogic.generalize(task: phrase, steps: steps)
        skills.items.removeAll { TextTools.norm($0.pattern) == TextTools.norm(g.pattern) }
        let site = URL(string: steps.first(where: { $0.type == "open" })?.text ?? "")?.host ?? ""
        skills.items.insert(Skill(pattern: g.pattern, params: g.params, steps: g.steps, site: site, successes: 2, taught: true), at: 0)
        outcome = "حُفظت المهارة: «\(g.pattern)» — \(g.steps.count) خطوات. اكتب هذه الجملة لأعيدها دون استهلاك" + (g.params.isEmpty ? "." : "، وغيّر ما بين القوسين كما تشاء.")
    }
}
