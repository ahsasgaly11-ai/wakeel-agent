import SwiftUI

/// A scripted stand-in for the model: it returns the plans a test queued, in order.
final class FakeModel: LlmClient {
    var plans: [JSON] = []
    var calls = 0
    var lastUser = ""

    func callTool(model: String, system: String, user: String, shots: [Shot], tool: ToolSpec, maxTokens: Int, extraSystem: String?) async throws -> ToolResult {
        calls += 1
        lastUser = user
        let usage = Usage(input: 900, output: 120, cacheRead: 0, cacheWrite: 0)
        if plans.isEmpty {
            let fail: JSON = ["type": "fail", "text": "no scripted plan left"]
            return ToolResult(input: ["actions": [fail]], usage: usage)
        }
        return ToolResult(input: plans.removeFirst(), usage: usage)
    }

    func plan(_ actions: [JSON], sensitive: Bool = false) {
        plans.append(["thought": "خطة مكتوبة", "actions": actions, "sensitive": sensitive])
    }
}

/// The app testing itself on a real device or simulator: the real browser, real pages, real files —
/// only the model is scripted. Started with the `-selftest` launch argument; writes its report to
/// Documents/selftest.txt.
@MainActor
enum SelfTest {
    static let sandbox = BrowserController.sandboxURL

    private static var lines: [String] = []
    private static var failed = 0

    private static func report() {
        let url = (FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory).appendingPathComponent("selftest.txt")
        try? lines.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
    }

    private static func check(_ name: String, _ ok: Bool, _ detail: String = "") {
        lines.append((ok ? "PASS  " : "FAIL  ") + name + (ok || detail.isEmpty ? "" : " — " + detail))
        if !ok { failed += 1 }
        report()
    }

    private static func runTask(_ m: AppModel, _ task: String, timeout: Double = 60) async -> TaskRecord? {
        let before = m.history.items.first?.id
        lines.append("  ▶ \(task)")
        guard m.engine.start(task) else { lines.append("  (did not start)"); return nil }
        let start = Date()
        while Date().timeIntervalSince(start) < timeout {
            try? await Task.sleep(nanoseconds: 120_000_000)
            if !m.engine.isRunning, let top = m.history.items.first, top.id != before {
                lines.append("  ◀ \(top.status.rawValue) · calls=\(top.calls) · free=\(top.localSteps) · \(top.result.take(140).replacingOccurrences(of: "\n", with: " ⏎ "))")
                return top
            }
        }
        m.engine.stop()
        lines.append("  (timed out)")
        return nil
    }

    private static func status(_ m: AppModel) async -> String {
        (await m.browser.js("document.getElementById('status').textContent") as? String) ?? ""
    }

    static func run(_ m: AppModel) async {
        lines = ["Wakeel for iPad — self-test", ""]
        failed = 0
        let fake = FakeModel()
        LLM.override = fake
        m.browser.sandboxHTML = page
        m.browser.autoAcceptDialogs = true
        m.scheduler.quiet = true
        m.skills.items = []
        m.history.items = []
        m.shortcuts.items = []
        m.scheduler.store.items = []
        let escalate = m.settings.escalate
        m.settings.escalate = false          // the tests script one model; a second, stronger one would need plans of its own
        for f in FileBox.list() { try? FileManager.default.removeItem(at: f) }
        var opened: [String] = []
        m.engine.opener = { link in opened.append(link); return true }
        var refuseOldText = false
        var asked: [String] = []
        m.engine.autoAnswer = { q in
            asked.append(q.text)
            if q.title == "نص قديم في حقل الكتابة" { return !refuseOldText }
            return true
        }
        let open: JSON = ["type": "open_url", "text": sandbox]
        func tap(_ label: String) -> JSON { ["type": "tap", "find": label] }
        func type(_ label: String, _ text: String) -> JSON { ["type": "type", "find": label, "text": text] }
        func done(_ text: String) -> JSON { ["type": "done", "text": text] }

        // 1 — a whole web task in one plan, then learned
        fake.plan([open, tap("Contacts"), tap("Sara"), type("Message", "مرحبا"), tap("Send"), done("أرسلتُ الرسالة.")], sensitive: true)
        var r = await runTask(m, "أرسل في الصندوق إلى Sara رسالة مرحبا")
        check("a web task runs from one plan", r?.status == .done && r?.calls == 1, "\(r?.status.rawValue ?? "nil") calls=\(r?.calls ?? -1)")
        var st = await status(m)
        check("the message really went out on the page", st == "sent: مرحبا to Sara", st)
        check("the owner was shown the text before it was sent", asked.contains { $0.contains("النص: «مرحبا»") }, asked.joined(separator: " | "))
        let learned = m.skills.items.first
        check("the task was learned as a pattern with two slots", learned?.params.count == 2, learned?.pattern ?? "no skill")

        // 2 — the learned skill, with new values, free
        var calls = fake.calls
        r = await runTask(m, "أرسل في الصندوق إلى Omar رسالة كيف حالك")
        st = await status(m)
        check("a learned skill replays with new values", st == "sent: كيف حالك to Omar", st)
        check("the replay costs no model call", fake.calls == calls && (r?.localSteps ?? 0) >= 5, "calls +\(fake.calls - calls), free=\(r?.localSteps ?? 0)")

        // 3 — a password field is never typed in
        fake.plan([open, tap("Account"), type("PIN", "1234")])
        fake.plan([done("لم أكتب الرمز.")])
        r = await runTask(m, "اكتب الرمز 1234 في حساب الصندوق")
        let pin = (await m.browser.js("document.getElementById('pin').value.length") as? NSNumber)?.intValue ?? -1
        check("a password field is never typed in", pin == 0 && (r?.logs.contains { $0.text.contains("كتابة") && $0.text.contains("✗") } ?? false), "pin length \(pin)")

        // 4 — a step that repeats with no effect is named
        for _ in 0..<3 { fake.plan([tap("Nothing here")]) }
        r = await runTask(m, "اضغط على شيء غير موجود في الصندوق")
        check("a repeated step stops the task and is named", r?.status == .failed && (r?.result.contains("Nothing here") ?? false) && r?.calls == 3, r?.result ?? "nil")

        // 5 — Office files, built on the device
        let docx: JSON = ["title": "دورة الماء في الطبيعة", "subtitle": "العلوم — الصف الثالث", "blocks": [
            ["type": "heading", "level": 1, "text": "ما هي دورة الماء؟"] as JSON,
            ["type": "paragraph", "text": "دورة الماء هي **حركة الماء المستمرة** بين الأرض والهواء."] as JSON,
            ["type": "numbered", "items": ["التبخر", "التكاثف", "الهطول"]] as JSON,
            ["type": "table", "header": ["المرحلة", "ماذا يحدث"], "rows": [["التبخر", "ماء ← بخار"], ["الهطول", "سقوط المطر"]]] as JSON,
        ]]
        let pptx: JSON = ["title": "دورة الماء في الطبيعة", "subtitle": "العلوم — الصف الثالث", "theme": "maroon", "slides": [
            ["layout": "section", "title": "من أين يأتي المطر؟"] as JSON,
            ["layout": "bullets", "title": "مراحل دورة الماء", "bullets": ["**التبخر**: يصعد الماء بخاراً", "التكاثف: تتكوّن السحب", "الهطول: يسقط المطر"]] as JSON,
            ["layout": "two", "title": "مقارنة", "left": ["title": "التبخر", "bullets": ["يحتاج حرارة"]] as JSON, "right": ["title": "التكاثف", "bullets": ["يحتاج برودة"]] as JSON] as JSON,
            ["layout": "table", "title": "جدول", "table": ["header": ["المرحلة", "مثال"], "rows": [["التبخر", "جفاف الملابس"], ["الهطول", "مطر الشتاء"]]] as JSON] as JSON,
            ["layout": "quote", "title": "تأمّل", "text": "وجعلنا من الماء كل شيء حي", "by": "سورة الأنبياء"] as JSON,
        ]]
        let xlsx: JSON = ["sheets": [["name": "درجات الصف", "header": ["الطالب", "اختبار 1", "اختبار 2", "المعدل", "النسبة"],
                                      "rows": [["أحمد", 18, 17.5, "=AVERAGE(B2:C2)", "88%"] as [Any], ["سالم", 15, 19, "=AVERAGE(B3:C3)", "85%"] as [Any]]] as JSON]]
        fake.plan([["type": "create_file", "kind": "docx", "name": "ملخص دورة الماء", "spec": docx], ["type": "create_file", "kind": "pptx", "name": "عرض دورة الماء", "spec": pptx],
                   ["type": "create_file", "kind": "xlsx", "name": "درجات الصف", "spec": xlsx], done("أنشأتُ الملفات الثلاثة.")])
        let skillsBefore = m.skills.items.count
        r = await runTask(m, "أنشئ ملخصاً وعرضاً وجدول درجات عن دورة الماء")
        let made = FileBox.list()
        let sizes = made.map { ((try? Data(contentsOf: $0))?.count ?? 0) }
        let zipped = made.allSatisfy { ((try? Data(contentsOf: $0))?.prefix(2) ?? Data()) == Data([0x50, 0x4B]) }
        check("three Office files are created in one plan", r?.status == .done && r?.files.count == 3 && made.count == 3, "files=\(made.map { $0.lastPathComponent })")
        check("the files are real archives with content", zipped && sizes.allSatisfy { $0 > 1500 }, "sizes=\(sizes)")
        check("creating files teaches no browsing skill", m.skills.items.count == skillsBefore)

        // 6 — a task for later waits for its time, without the model
        calls = fake.calls
        r = await runTask(m, "بعد 4 ثواني كم الساعة")
        check("a task for later is scheduled, not run", r?.status == .done && (r?.result.contains("جُدولت") ?? false) && m.scheduler.store.items.count == 1 && fake.calls == calls, r?.result ?? "nil")
        check("the time phrase is taken out of the task", m.scheduler.store.items.first?.task == "كم الساعة", m.scheduler.store.items.first?.task ?? "none")
        let mark = m.history.items.first?.id
        var fired: TaskRecord?
        for _ in 0..<100 {
            try? await Task.sleep(nanoseconds: 100_000_000)
            if let top = m.history.items.first, top.id != mark, !m.engine.isRunning { fired = top; break }
        }
        check("the scheduled task starts by itself at its time", fired?.task == "كم الساعة" && fired?.status == .done && m.scheduler.store.items.isEmpty, fired?.result ?? "did not fire")

        // 7 — a WhatsApp chat link, built locally with the country code
        calls = fake.calls
        r = await runTask(m, "أرسل واتساب إلى 70776067: هلا")
        let link = opened.last ?? ""
        check("a WhatsApp message is prepared without the model", fake.calls == calls && link.hasPrefix("https://wa.me/") && link.contains("70776067") && link.hasSuffix("?text=" + Commands.enc("هلا")), link)

        // 8 — taught by hand in the browser, then replayed with another value
        await m.browser.load(sandbox)
        await m.teacher.start("اشترِ من الصندوق مقاس Large")
        _ = await m.browser.js("__wk.trustAll = true; 1")
        _ = await m.browser.js("document.getElementById('nav-shop').click(); var s = document.getElementById('size'); s.value = 'Large'; s.dispatchEvent(new Event('change', {bubbles: true})); document.getElementById('buy').click(); 1")
        try? await Task.sleep(nanoseconds: 500_000_000)
        let recorded = m.teacher.steps.map { $0.type }
        await m.teacher.stop(save: true)
        let taught = m.skills.items.first { $0.taught }
        check("what the owner does in the browser is recorded", recorded == ["open", "tap", "select", "tap"], "\(recorded)")
        check("the demonstration becomes a skill with a slot", taught?.pattern == "اشترِ من الصندوق مقاس {p1}", taught?.pattern ?? m.teacher.outcome ?? "none")
        calls = fake.calls
        r = await runTask(m, "اشترِ من الصندوق مقاس Small")
        st = await status(m)
        check("the taught skill replays with another value, free", st == "bought Small" && fake.calls == calls, st)

        // 9 — an unfinished demonstration is not saved
        let count = m.skills.items.count
        await m.browser.load(sandbox)
        await m.teacher.start("اكتب شيئاً في بحث الصندوق")
        _ = await m.browser.js("__wk.trustAll = true; var q = document.getElementById('q'); q.value = 'نصف مهمة'; q.dispatchEvent(new Event('change', {bubbles: true})); 1")
        try? await Task.sleep(nanoseconds: 400_000_000)
        await m.teacher.stop(save: true)
        check("an unfinished demonstration is not saved", m.skills.items.count == count && (m.teacher.outcome ?? "").contains("لم أحفظ"), m.teacher.outcome ?? "")
        m.teacher.outcome = nil

        // 10 — a blocked site is refused before it loads
        fake.plan([["type": "open_url", "text": "https://online.mybank.example/login"]])
        r = await runTask(m, "افتح حسابي في البنك وحوّل مبلغاً")
        check("a blocked site is refused", r?.status == .failed && (r?.result.contains("الممنوعة") ?? false), r?.result ?? "nil")

        // 11 — text already in a field is never sent unasked
        refuseOldText = true
        asked = []
        fake.plan([open, tap("Contacts"), tap("Noor"), tap("Send"), done("أرسلتُ.")], sensitive: true)
        fake.plan([type("Message", "هلا"), tap("Send"), done("أرسلتُ «هلا».")], sensitive: true)
        r = await runTask(m, "حيِّ Noor في الصندوق")
        st = await status(m)
        let sentCount = (await m.browser.js("document.getElementById('count').textContent") as? String) ?? ""
        check("the owner is shown text the task did not write", asked.contains { $0.contains("لم أكتبه") && $0.contains("مسودة قديمة") }, asked.joined(separator: " | "))
        check("only the real message goes out", st == "sent: هلا to Noor" && sentCount == "messages sent to Noor: 1", "\(st) / \(sentCount)")
        refuseOldText = false

        // 12 — scrolling, and finding something below the visible part
        fake.plan([open, ["type": "scroll", "dir": "down"], tap("Deep button"), done("ضغطتُ الزر.")])
        r = await runTask(m, "انزل في الصندوق واضغط الزر العميق")
        st = await status(m)
        check("an element below the visible part is reached", st == "deep pressed", st)

        // 13 — reading a page for an answer
        fake.plan([open, ["type": "read"]])
        fake.plan([done("عاصمة قطر هي **الدوحة**.")])
        r = await runTask(m, "ما عاصمة قطر بحسب صفحة الصندوق")
        check("the full page text reaches the model when asked", fake.lastUser.contains("FULL PAGE TEXT") && fake.lastUser.contains("capital of Qatar is Doha") && r?.status == .done, fake.lastUser.suffix(200).description)

        // 14 — a search command opens the results in the agent's own browser, free
        calls = fake.calls
        m.settings.searchEngine = "google"
        let cmd = Commands.parse("ابحث عن قهوة عربية", search: m.settings.searchURL)
        check("a plain search is a free local command", cmd == .browse(url: "https://www.google.com/search?q=" + Commands.enc("قهوة عربية"), say: "هذه نتائج البحث عن «قهوة عربية»."), "\(String(describing: cmd))")
        m.settings.escalate = escalate

        // 15 — the app's own keyboard types into the app's own fields
        m.settings.inAppKeyboard = true
        m.tab = 0
        m.draft = ""
        try? await Task.sleep(nanoseconds: 700_000_000)
        for _ in 0..<5 where !m.keyboard.visible {
            m.focusComposer += 1
            try? await Task.sleep(nanoseconds: 900_000_000)
        }
        check("the keyboard comes up when a field of the app takes the focus", m.keyboard.visible, FocusHelp.report)
        for piece in ["مرحبا", " ", "A", "1"] { m.keyboard.insert(piece) }
        try? await Task.sleep(nanoseconds: 300_000_000)
        check("its keys type into the field", m.draft == "مرحبا A1", "«\(m.draft)»")
        m.keyboard.backspace()
        m.keyboard.backspace()
        try? await Task.sleep(nanoseconds: 300_000_000)
        check("its delete key deletes", m.draft == "مرحبا ", "«\(m.draft)»")
        m.keyboard.hide()
        try? await Task.sleep(nanoseconds: 500_000_000)
        check("its hide key puts it away", !m.keyboard.visible)
        m.draft = ""

        // 16 — and into a field on a web page, where the owner signs in and searches
        m.tab = 1
        await m.browser.load(sandbox)
        _ = await m.browser.js("document.getElementById('q').focus(); 1")
        try? await Task.sleep(nanoseconds: 600_000_000)
        check("the keyboard comes up for a field on a web page", m.keyboard.visible)
        for piece in ["ق", "ه", "و", "ة"] { m.keyboard.insert(piece) }
        m.keyboard.backspace()
        try? await Task.sleep(nanoseconds: 400_000_000)
        let typedInPage = (await m.browser.js("document.getElementById('q').value") as? String) ?? ""
        check("its keys type into the page", typedInPage == "قهو", "«\(typedInPage)»")
        m.keyboard.enter()
        try? await Task.sleep(nanoseconds: 600_000_000)
        st = await status(m)
        check("its Return key submits the page's form", st == "searched: قهو", st)
        m.keyboard.hide()
        try? await Task.sleep(nanoseconds: 400_000_000)

        lines.append("")
        lines.append(failed == 0 ? "RESULT: OK (\(lines.filter { $0.hasPrefix("PASS") }.count) checks)" : "RESULT: FAILED (\(failed) of \(lines.filter { $0.hasPrefix("PASS") || $0.hasPrefix("FAIL") }.count))")
        report()
        LLM.override = nil
        m.engine.autoAnswer = nil
        m.tab = 0
    }

    /// Sample content, so screenshots show the app as it looks in use.
    static func demo(_ m: AppModel) async {
        m.browser.sandboxHTML = page
        m.scheduler.quiet = true
        m.skills.items = [
            Skill(pattern: "ابحث في المتجر عن {p1}", params: ["p1"], steps: [SkillStep(type: "open", text: "https://example.com"), SkillStep(type: "type", label: "Search", text: "{p1}"), SkillStep(type: "enter")], site: "example.com", successes: 4),
            Skill(pattern: "اشترِ من الصندوق مقاس {p1}", params: ["p1"], steps: [SkillStep(type: "open", text: sandbox), SkillStep(type: "tap", label: "Shop"), SkillStep(type: "select", label: "Size", text: "{p1}"), SkillStep(type: "tap", label: "Buy now", confirm: true)], site: "sandbox.wakeel", successes: 2, taught: true),
        ]
        m.shortcuts.items = [ShortcutItem(name: "رسالة لمجموعة المعلمين", about: "يرسل النص إلى مجموعة واتساب المعلمين", takesInput: true)]
        for f in FileBox.list() { try? FileManager.default.removeItem(at: f) }
        let deck: JSON = ["title": "دورة الماء في الطبيعة", "slides": [["layout": "bullets", "title": "المراحل", "bullets": ["التبخر", "التكاثف", "الهطول"]] as JSON]]
        let name = FileBox.freshURL(name: "عرض دورة الماء", ext: "pptx")
        try? Pptx.build(deck).write(to: name)
        try? Docx.build(["title": "ملخص الدرس", "blocks": [["type": "paragraph", "text": "نص تجريبي."] as JSON]]).write(to: FileBox.freshURL(name: "ملخص الدرس", ext: "docx"))
        try? Xlsx.build(["sheets": [["name": "الدرجات", "header": ["الطالب", "الدرجة"], "rows": [["أحمد", 18] as [Any]]] as JSON]]).write(to: FileBox.freshURL(name: "درجات الصف", ext: "xlsx"))
        let now = Date()
        let answer = "أنشأتُ عرضاً من 8 شرائح عن دورة الماء.\n## ما فيه\n- **المراحل الأربع** بترتيبها مع مثال لكل مرحلة\n- شريحة مقارنة بين التبخر والتكاثف\n- جدول تلخيص وشريحة أسئلة للمراجعة"
        m.history.items = [
            TaskRecord(task: "أنشئ عرضاً تقديمياً من 8 شرائح عن دورة الماء للصف الثالث", startedAt: now.addingTimeInterval(-40), endedAt: now.addingTimeInterval(-22), status: .done, calls: 1,
                       usage: Usage(input: 2100, output: 1900, cacheRead: 0, cacheWrite: 0), cost: 0.004, steps: 2, localSteps: 0, result: answer,
                       logs: [LogEntry(kind: .info, text: "المهمة: أنشئ عرضاً تقديمياً"), LogEntry(kind: .success, text: "أنشأتُ الملف «عرض دورة الماء.pptx»")], files: [name.lastPathComponent]),
            TaskRecord(task: "أرسل واتساب إلى 70776067: هلا", startedAt: now.addingTimeInterval(-600), endedAt: now.addingTimeInterval(-599), status: .done, calls: 0, usage: Usage(), cost: 0, steps: 0, localSteps: 1,
                       result: "فتحتُ محادثة واتساب مع 70776067 والرسالة جاهزة — اضغط إرسال.", logs: []),
            TaskRecord(task: "اضغط زر الشراء في المتجر", startedAt: now.addingTimeInterval(-3600), endedAt: now.addingTimeInterval(-3570), status: .failed, calls: 3, usage: Usage(), cost: 0.002, steps: 3, localSteps: 0,
                       result: "توقّفتُ لأن خطوة واحدة تتكرر والصفحة لا تتغيّر: ضغط على «Buy» في example.com.", logs: []),
        ]
        m.engine.last = m.history.items.first
        m.scheduler.store.items = [Scheduled(task: "ابحث عن أخبار التعليم في قطر ولخّصها", at: now.addingTimeInterval(1800))]
        await m.browser.load(sandbox)
    }

    /// The pretend site the tests run on.
    static let page = #"""
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sandbox</title>
<style>
 body{font-family:-apple-system,Arial,sans-serif;margin:0;padding:16px;background:#f8fafc;color:#0f172a}
 nav{display:flex;gap:8px;margin-bottom:12px} button,input,select{font-size:17px;padding:8px 12px}
 section{display:none;background:#fff;border:1px solid #cbd5e1;border-radius:12px;padding:16px}
 section.on{display:block} a.contact{display:block;padding:10px;border-bottom:1px solid #e2e8f0;color:#0f766e;text-decoration:none}
 #status{font-weight:bold;color:#8a1538} .card{cursor:pointer;padding:12px;background:#e6f4f1;border-radius:10px;margin-top:10px;display:inline-block}
</style></head><body>
<h1>Sandbox</h1>
<p id="status">status: idle</p>
<nav>
 <button id="nav-home">Home</button><button id="nav-contacts">Contacts</button><button id="nav-shop">Shop</button><button id="nav-account">Account</button>
</nav>
<section id="home" class="on">
 <form id="searchform"><input id="q" name="q" placeholder="Search"> <button type="submit" id="go">Go</button></form>
 <p>Welcome to the sandbox. The capital of Qatar is Doha, and this page exists only for tests.</p>
 <div style="height:1800px"></div>
 <button id="deep">Deep button</button>
</section>
<section id="contacts"><div id="list"></div></section>
<section id="chat">
 <h2 id="who"></h2>
 <input id="message" placeholder="Message"> <button id="send">Send</button>
 <p id="count"></p>
</section>
<section id="shop">
 <label for="size">Size</label> <select id="size"><option>Small</option><option>Medium</option><option>Large</option></select>
 <button id="buy">Buy now</button>
 <div class="card" id="details">Product details</div>
</section>
<section id="account">
 <input type="password" id="pin" placeholder="PIN"> <span id="pinlen">pin length 0</span><br><br>
 <button id="del">Delete account</button>
</section>
<script>
 var names = ['Sara','Omar','Layla','Noor','Khalid','Mariam','Yousef','Hind'];
 var sent = {}; var who = ''; var drafts = {Noor: 'مسودة قديمة'};
 function status(s){ document.getElementById('status').textContent = s; }
 function show(id){ var all = document.querySelectorAll('section'); for (var i=0;i<all.length;i++) all[i].className = all[i].id === id ? 'on' : ''; }
 ['home','contacts','shop','account'].forEach(function(id){ document.getElementById('nav-'+id).addEventListener('click', function(){ show(id); }); });
 var list = document.getElementById('list');
 names.forEach(function(n){ var a = document.createElement('a'); a.href = '#'; a.className = 'contact'; a.textContent = n;
   a.addEventListener('click', function(e){ e.preventDefault(); who = n; document.getElementById('who').textContent = 'Chat with ' + n;
     document.getElementById('message').value = drafts[n] || ''; count(); show('chat'); }); list.appendChild(a); });
 function count(){ document.getElementById('count').textContent = 'messages sent to ' + who + ': ' + (sent[who] || 0); }
 document.getElementById('message').addEventListener('input', function(e){ drafts[who] = e.target.value; });
 document.getElementById('send').addEventListener('click', function(){ var m = document.getElementById('message'); if (!m.value) { status('nothing to send'); return; }
   sent[who] = (sent[who] || 0) + 1; status('sent: ' + m.value + ' to ' + who); m.value = ''; drafts[who] = ''; count(); });
 document.getElementById('searchform').addEventListener('submit', function(e){ e.preventDefault(); status('searched: ' + document.getElementById('q').value); });
 document.getElementById('buy').addEventListener('click', function(){ status('bought ' + document.getElementById('size').value); });
 document.getElementById('details').addEventListener('click', function(){ status('details opened'); });
 document.getElementById('pin').addEventListener('input', function(e){ document.getElementById('pinlen').textContent = 'pin length ' + e.target.value.length; });
 document.getElementById('del').addEventListener('click', function(){ if (confirm('Delete the account?')) status('account deleted'); });
 document.getElementById('deep').addEventListener('click', function(){ status('deep pressed'); });
</script></body></html>
"""#
}
