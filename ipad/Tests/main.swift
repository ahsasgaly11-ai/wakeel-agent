import Foundation

// Logic tests for the parts of the app that need no screen: compiled together with the app's own
// source files and run on the build machine. The sample Office files they write are then opened
// by Tests/validate.py.

var failures = 0
var passed = 0
func expect(_ ok: Bool, _ what: String, _ detail: @autoclosure () -> String = "") {
    if ok { passed += 1 } else { failures += 1; print("FAIL  \(what)  \(detail())") }
}
func same<T: Equatable>(_ a: T, _ b: T, _ what: String) { expect(a == b, what, "got \(a), expected \(b)") }

// ---------------------------------------------------------------- text
same(TextTools.norm("  أَرْسِلْ  إلى  مُحمّد ٣ "), "ارسل الي محمد 3", "Arabic normalisation")
expect(TextTools.isRTL("مرحبا") && !TextTools.isRTL("hello"), "direction")
same(TextTools.xml("a<b & \"c\"\u{01}"), "a&lt;b &amp; &quot;c&quot;", "XML escaping drops control characters")
same(ZipWriter.crc32(Data("123456789".utf8)), 0xCBF43926, "CRC-32")

// ---------------------------------------------------------------- schedule
let zone = TimeZone(identifier: "Asia/Qatar")!
func at(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int) -> Date {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = zone
    return cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h, minute: mi))!
}
let now = at(2026, 11, 20, 3, 10)
func p(_ s: String) -> Schedule.Plan? { Schedule.parse(s, now: now, zone: zone) }

let asked = p("افتح الواتساب وارسل رسالة الي 70776067 بفتح خط قطر وارسل له رساله بعد دقيقة (هلا)")
same(asked?.at, now.addingTimeInterval(60), "after a minute")
same(asked?.task, "افتح الواتساب وارسل رسالة الي 70776067 بفتح خط قطر وارسل له رساله (هلا)", "phrase removed from the task")
same(asked?.phrase, "بعد دقيقة", "the phrase itself")
same(p("بعد ١٠ دقائق افتح يوتيوب")?.at, now.addingTimeInterval(600), "Arabic digits")
same(p("بعد ١٠ دقائق افتح يوتيوب")?.task, "افتح يوتيوب", "leading phrase removed")
same(p("افتح يوتيوب بعد 5 دقائق.")?.task, "افتح يوتيوب", "trailing phrase and full stop removed")
same(p("بعد ساعة ونصف شغل الواي فاي")?.at, now.addingTimeInterval(90 * 60), "an hour and a half")
same(p("شغل البلوتوث بعد نصف ساعة")?.at, now.addingTimeInterval(30 * 60), "half an hour")
same(p("بعد ساعتين من الآن افتح الكاميرا")?.at, now.addingTimeInterval(2 * 3600), "two hours from now")
same(p("بعد ساعتين من الآن افتح الكاميرا")?.task, "افتح الكاميرا", "'from now' removed")
same(p("بعد عشرين دقيقة اطفئ الواي فاي")?.at, now.addingTimeInterval(20 * 60), "twenty in words")
same(p("بعد خمس دقائق افتح الاعدادات")?.at, now.addingTimeInterval(5 * 60), "five in words")
same(p("in 5 minutes open youtube")?.at, now.addingTimeInterval(300), "English")
same(p("in 5 minutes open youtube")?.task, "open youtube", "English phrase removed")
same(p("الساعة 8 مساءً ارسل تقرير الاسبوع")?.at, at(2026, 11, 20, 20, 0), "8 in the evening")
same(p("الساعة 8 مساءً ارسل تقرير الاسبوع")?.task, "ارسل تقرير الاسبوع", "clock phrase removed")
same(p("افتح الكاميرا غدا الساعة 7")?.at, at(2026, 11, 21, 7, 0), "tomorrow at 7")
same(p("افتح الإعدادات الساعة 2")?.at, at(2026, 11, 20, 14, 0), "the next 2 o'clock")
same(p("ارسلها الساعة 5:30 م")?.at, at(2026, 11, 20, 17, 30), "5:30 pm")
same(p("افتح الخرائط الساعة الخامسة والنصف عصراً")?.at, at(2026, 11, 20, 17, 30), "half past five, in words")
same(p("الساعة 9 إلا ربع صباحا افتح البريد")?.at, at(2026, 11, 20, 8, 45), "a quarter to nine")
same(p("الساعة 2 ص افتح الكاميرا")?.at, at(2026, 11, 21, 2, 0), "already past today")
same(p("open maps at 5:30 pm")?.at, at(2026, 11, 20, 17, 30), "English clock")
expect(p("أرسل إلى أحمد: سأصل بعد 5 دقائق") == nil, "a time inside the message is not a schedule")
expect(p("أرسل «سأصل بعد ساعة» إلى أحمد") == nil, "a time inside quotes is not a schedule")
same(p("ارسل «سأصل بعد ساعة» الى احمد بعد نصف ساعة")?.task, "ارسل «سأصل بعد ساعة» الى احمد", "the quoted text is kept")
for s in ["اضبط منبه الساعة 7 صباحا", "ذكرني بعد 10 دقائق بالدواء", "ارسل له بعد ذلك صورة", "كم الساعة الآن", "افتح الإعدادات", "بعد 5 دقائق"] {
    expect(p(s) == nil, "not a schedule: \(s)")
}
same(Schedule.describe(now.addingTimeInterval(60), now: now, zone: zone), "بعد دقيقة (الساعة 3:11 ص)", "describe a minute")
same(Schedule.describe(now.addingTimeInterval(600), now: now, zone: zone), "بعد 10 دقيقة (الساعة 3:20 ص)", "describe ten minutes")
same(Schedule.describe(at(2026, 11, 20, 20, 0), now: now, zone: zone), "اليوم الساعة 8:00 م", "describe today")
same(Schedule.describe(at(2026, 11, 21, 7, 0), now: now, zone: zone), "غداً الساعة 7:00 ص", "describe tomorrow")

// ---------------------------------------------------------------- local commands
same(Dial.international("70776067", code: "974"), "97470776067", "local number gets the country code")
same(Dial.international("+97470776067", code: "974"), "97470776067", "a number with + is complete")
same(Dial.international("0097470776067", code: "974"), "97470776067", "a number with 00 is complete")
same(Dial.international("0555123456", code: "966"), "966555123456", "the leading zero goes")
same(Dial.international("70776067", code: nil), "70776067", "no region known")
same(Dial.waLink("https://wa.me/70776067?text=hi", code: "974"), "https://wa.me/97470776067?text=hi", "chat link gets the country code")
same(Commands.parse("أرسل واتساب إلى 70776067: هلا", dial: "974"),
     .external(url: "https://wa.me/97470776067?text=" + Commands.enc("هلا"), say: "فتحتُ محادثة واتساب مع 70776067 والرسالة جاهزة — اضغط إرسال."), "WhatsApp command")
same(Commands.parse("اتصل بالرقم 44551234"), .external(url: "tel:44551234", say: "بدأتُ الاتصال بـ 44551234."), "call command")
same(Commands.parse("شغّل اختصار رسالة الصباح: صباح الخير"), .external(url: Commands.shortcutURL("رسالة الصباح", input: "صباح الخير"), say: "شغّلتُ الاختصار «رسالة الصباح»."), "shortcut with input")
same(Commands.parse("رسالة الصباح", shortcuts: ["رسالة الصباح"]), .external(url: Commands.shortcutURL("رسالة الصباح", input: nil), say: "شغّلتُ الاختصار «رسالة الصباح»."), "shortcut by its name")
same(Commands.parse("ابحث عن قهوة عربية"), .browse(url: "https://www.google.com/search?q=" + Commands.enc("قهوة عربية"), say: "هذه نتائج البحث عن «قهوة عربية»."), "search command")
same(Commands.parse("ابحث في يوتيوب عن شرح دورة الماء"), .browse(url: "https://www.youtube.com/results?search_query=" + Commands.enc("شرح دورة الماء"), say: "هذه نتائج يوتيوب عن «شرح دورة الماء»."), "YouTube search")
same(Commands.parse("افتح wikipedia.org"), .browse(url: "https://wikipedia.org", say: "فتحتُ wikipedia.org."), "open a site")
same(Commands.parse("افتح تطبيق واتساب"), .external(url: "whatsapp://", say: "فتحتُ واتساب."), "open an app")
same(Commands.parse("أين يقع برج خليفة؟"), .external(url: "http://maps.apple.com/?q=" + Commands.enc("برج خليفة"), say: "فتحتُ الخرائط على «برج خليفة»."), "a place on the map")
same(Commands.parse("أرسل بريد إلى ali@example.com: الاجتماع غداً"), .external(url: "mailto:ali@example.com?body=" + Commands.enc("الاجتماع غداً"), say: "فتحتُ رسالة بريد إلى ali@example.com والنص جاهز — راجعها واضغط إرسال."), "mail command")
for s in ["ابحث عن أسعار الذهب ولخّص النتائج", "أنشئ عرضاً تقديمياً عن دورة الماء", "افتح موقع المدرسة ثم حمّل الجدول", "ما عاصمة قطر"] {
    expect(Commands.parse(s) == nil, "needs the model: \(s)", "\(String(describing: Commands.parse(s)))")
}
if case .answer(let t)? = Commands.parse("كم الساعة؟") { expect(t.contains("الساعة الآن"), "the time") } else { expect(false, "the time is answered locally") }

// ---------------------------------------------------------------- safety
expect(Safety.isSensitiveLabel("إرسال") && Safety.isSensitiveLabel("Buy now") && !Safety.isSensitiveLabel("المزيد"), "sensitive labels")
expect(Safety.isSendLabel("Send") && Safety.isSendLabel("نشر") && !Safety.isSendLabel("حذف"), "send labels")
expect(Safety.policy(host: "online.qnb.com", blocked: Safety.defaultBlocked, readOnly: Safety.defaultReadOnly) == .blocked, "a bank is blocked")
expect(Safety.policy(host: "www.mexc.com", blocked: Safety.defaultBlocked, readOnly: Safety.defaultReadOnly) == .readOnly, "an exchange is read-only")
expect(Safety.policy(host: "ar.wikipedia.org", blocked: Safety.defaultBlocked, readOnly: Safety.defaultReadOnly) == .allowed, "an ordinary site is allowed")

// ---------------------------------------------------------------- skills
let route = [SkillStep(type: "open", text: "https://shop.example/search?q=" + Commands.enc("قهوة عربية")), SkillStep(type: "type", label: "Search", text: "قهوة عربية"), SkillStep(type: "enter", label: "Search")]
let g = SkillLogic.generalize(task: "ابحث في المتجر عن قهوة عربية", steps: route)
same(g.pattern, "ابحث في المتجر عن {p1}", "typed text becomes a slot")
same(g.steps[1].text, "{p1}", "the step carries the slot")
same(g.steps[0].text, "https://shop.example/search?q={p1}", "the address carries the slot")
let fit = SkillLogic.match(task: "ابحث في المتجر عن شاي أخضر", pattern: g.pattern)
same(fit?["p1"], "شاي أخضر", "a new value fits the pattern")
same(SkillLogic.fill(g.steps[0].text, fit ?? [:], encode: true), "https://shop.example/search?q=" + Commands.enc("شاي أخضر"), "the address is filled, encoded")
expect(SkillLogic.match(task: "ابحث في السوق عن شاي", pattern: g.pattern) == nil, "another task does not fit")
expect(SkillLogic.match(task: "ابحث في المتجر عن", pattern: g.pattern) == nil, "an empty slot does not fit")
let chat = [SkillStep(type: "open", text: "https://sandbox.wakeel/"), SkillStep(type: "tap", label: "Contacts"), SkillStep(type: "tap", label: "Sara"),
            SkillStep(type: "type", label: "Message", text: "مرحبا"), SkillStep(type: "tap", label: "Send", confirm: true)]
let g2 = SkillLogic.generalize(task: "أرسل في الصندوق إلى Sara رسالة مرحبا", steps: chat)
same(g2.pattern, "أرسل في الصندوق إلى {p2} رسالة {p1}", "a tapped name and a typed text both become slots")
same(g2.steps[2].label, "{p2}", "the tapped name is a slot")
same(g2.steps[1].label, "Contacts", "an ordinary button stays as it is")
let fit2 = SkillLogic.match(task: "أرسل في الصندوق إلى Omar رسالة كيف حالك", pattern: g2.pattern)
expect(fit2?["p1"] == "كيف حالك" && fit2?["p2"] == "Omar", "two slots are filled", "\(String(describing: fit2))")
same(SkillLogic.generalize(task: "افتح Contacts", steps: [SkillStep(type: "open", text: "https://a.b/"), SkillStep(type: "tap", label: "Contacts")]).pattern, "افتح Contacts",
     "a pattern keeps words of its own")
same(SkillLogic.generalize(task: "ابحث عن amazon", steps: [SkillStep(type: "open", text: "https://amazon.com/s?k=amazon"), SkillStep(type: "type", label: "Search", text: "amazon")]).steps[0].text,
     "https://amazon.com/s?k={p1}", "the site's own name is not turned into a slot")
expect(SkillLogic.incomplete([SkillStep(type: "open", text: "x")], sawSecret: false) != nil, "a demonstration with no steps is incomplete")
expect(SkillLogic.incomplete([SkillStep(type: "open", text: "x"), SkillStep(type: "type", text: "y")], sawSecret: false) != nil, "typing without sending is incomplete")
expect(SkillLogic.incomplete(chat, sawSecret: true) != nil, "a demonstration with a password is not kept")
expect(SkillLogic.incomplete(chat, sawSecret: false) == nil, "a finished demonstration is complete")

// ---------------------------------------------------------------- model replies
let d = AgentDecision(J.object(in: "Here: {\"thought\":\"t\",\"actions\":[{\"type\":\"TAP\",\"id\":\"7\"},{\"type\":\"create_file\",\"kind\":\"PPTX\",\"spec\":\"{\\\"title\\\":\\\"x\\\"}\"},{\"nope\":1}]}"))
expect(d.actions.count == 2 && d.actions[0].type == "tap" && d.actions[0].id == 7 && d.actions[1].kind == "pptx" && d.actions[1].spec?["title"] as? String == "x", "lenient action parsing")
expect(AgentDecision(["type": "done", "text": "ok"]).actions.first?.type == "done", "a single top-level action is accepted")
expect(Prompt.system.contains("create_file") && JSONSerialization.isValidJSONObject(Prompt.actTool().schema), "the tool schema is valid JSON")

// ---------------------------------------------------------------- Office files
let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : ".")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
let docSpec: JSON = ["title": "دورة الماء في الطبيعة", "subtitle": "ملخص درس العلوم — الصف الثالث", "blocks": [
    ["type": "heading", "level": 1, "text": "ما هي دورة الماء؟"] as JSON,
    ["type": "paragraph", "text": "دورة الماء هي **حركة الماء المستمرة** بين الأرض والهواء. تبدأ بالتبخر (Evaporation) ثم التكاثف & الهطول <دائماً>."] as JSON,
    ["type": "heading", "level": 2, "text": "المراحل"] as JSON,
    ["type": "numbered", "items": ["التبخر", "التكاثف", "الهطول", "الجريان"]] as JSON,
    ["type": "bullets", "items": ["الشمس مصدر الطاقة", "السحب تتكون من قطرات صغيرة"]] as JSON,
    ["type": "quote", "text": "وجعلنا من الماء كل شيء حي"] as JSON,
    ["type": "table", "header": ["المرحلة", "ماذا يحدث", "مثال"], "rows": [["التبخر", "يتحول الماء إلى بخار", "جفاف الملابس"], ["الهطول", "يسقط المطر", "مطر الشتاء"]]] as JSON,
    ["type": "page_break"] as JSON,
    ["type": "heading", "level": 3, "text": "English note"] as JSON,
    ["type": "paragraph", "text": "This paragraph is left to right."] as JSON,
    ["type": "numbered", "items": ["سؤال أول", "سؤال ثانٍ"]] as JSON,
]]
let deckSpec: JSON = ["title": "دورة الماء في الطبيعة", "subtitle": "العلوم — الصف الثالث", "theme": "maroon", "slides": [
    ["layout": "section", "title": "أولاً: من أين يأتي المطر؟", "subtitle": "رحلة قطرة الماء"] as JSON,
    ["layout": "bullets", "title": "مراحل دورة الماء", "bullets": ["**التبخر**: تسخّن الشمس الماء فيصعد بخاراً", "التكاثف: يبرد البخار فيصير قطرات", "الهطول: تسقط القطرات مطراً", "الجريان: يعود الماء إلى البحار"]] as JSON,
    ["layout": "two", "title": "مقارنة", "left": ["title": "التبخر", "bullets": ["يحتاج حرارة", "من سائل إلى غاز"]] as JSON, "right": ["title": "التكاثف", "bullets": ["يحتاج برودة", "من غاز إلى سائل"]] as JSON] as JSON,
    ["layout": "table", "title": "جدول المراحل", "table": ["header": ["المرحلة", "ماذا يحدث", "مثال"], "rows": [["التبخر", "ماء ← بخار", "جفاف الملابس"], ["التكاثف", "بخار ← قطرات", "الندى"]]] as JSON] as JSON,
    ["layout": "quote", "title": "تأمّل", "text": "وجعلنا من الماء كل شيء حي", "by": "سورة الأنبياء"] as JSON,
    ["layout": "bullets", "title": "Summary in English", "bullets": ["Evaporation", "Condensation", "Precipitation"]] as JSON,
]]
let sheetSpec: JSON = ["sheets": [
    ["name": "درجات الصف", "header": ["الطالب", "الاختبار 1", "الاختبار 2", "المعدل", "النسبة"],
     "rows": [["أحمد", 18, 17.5, "=AVERAGE(B2:C2)", "88%"] as [Any], ["سالم", 15, 19, "=AVERAGE(B3:C3)", "85%"] as [Any], ["المجموع", "=SUM(B2:B3)", "=SUM(C2:C3)", "", ""] as [Any]]] as JSON,
    ["name": "Notes", "header": ["Item", "Qty"], "rows": [["Pens", "12"], ["Code", "007"]]] as JSON,
]]
do {
    try Docx.build(docSpec).write(to: out.appendingPathComponent("sample.docx"))
    try Xlsx.build(sheetSpec).write(to: out.appendingPathComponent("sample.xlsx"))
    for theme in ["maroon", "teal", "dark"] {
        var spec = deckSpec
        spec["theme"] = theme
        try Pptx.build(spec).write(to: out.appendingPathComponent("sample-\(theme).pptx"))
    }
    try Pptx.build(["title": ""]).write(to: out.appendingPathComponent("empty.pptx"))
    try Docx.build([:]).write(to: out.appendingPathComponent("empty.docx"))
    try Xlsx.build([:]).write(to: out.appendingPathComponent("empty.xlsx"))
    expect(true, "files written")
} catch {
    expect(false, "writing the sample files", "\(error)")
}
same(Xlsx.column(0) + Xlsx.column(25) + Xlsx.column(26) + Xlsx.column(701), "AZAAZZ", "column letters")

// ---------------------------------------------------------------- trading mode
func near(_ a: Double?, _ b: Double, _ what: String, _ eps: Double = 1e-9) { expect(a != nil && abs(a! - b) <= eps, what, "got \(String(describing: a)), expected \(b)") }
func bar(_ t: Int64, _ o: Double, _ h: Double, _ l: Double, _ c: Double, _ v: Double = 10) -> Candle { Candle(time: t, open: o, high: h, low: l, close: c, volume: v) }

near(Indicators.ema([1, 2, 3, 4, 5], 3), 4, "EMA")
expect(Indicators.ema([1, 2], 3) == nil, "EMA needs enough values")
near(Indicators.rsi((1...30).map(Double.init)), 100, "RSI of a steady climb")
near(Indicators.rsi(Array(repeating: 7, count: 30)), 50, "RSI of a flat line")
let book = [44.34, 44.09, 44.15, 43.61, 44.33, 44.83, 45.10, 45.42, 45.84, 46.08, 45.89, 46.03, 45.61, 46.28, 46.28]
near(Indicators.rsi(book), 70.5, "RSI of the textbook series", 0.2)
expect(Indicators.rsi(Array(book.prefix(14))) == nil, "RSI needs fifteen closes")
near(Indicators.atr((0..<30).map { bar(Int64($0) * 3_600_000, 100, 101, 99, 100) }), 2, "ATR")
near(Indicators.bollinger(Array(repeating: 5, count: 20))?.low, 5, "Bollinger of a flat line")
expect(Indicators.macdHist((1...30).map(Double.init)) == nil, "MACD needs history")
near(Indicators.macdHist((1...200).map(Double.init)), 0, "MACD histogram of a steady climb", 1e-6)

let klines = MarketText.parseKlines(#"[[1499644800000,"0.016","0.80","0.015","0.0157","148976.1",1499648399999,"2434.19",308,"1756.8","28.4","0"],[1499040000000,"0.0163","0.8","0.0157","0.0158","100.5",1499043599999,"1.0"]]"#)
same(klines.count, 2, "two candles read")
same(klines.first?.time, 1499040000000, "candles sorted by time")
near(klines.first?.close, 0.0158, "close", 1e-12)
near(klines.last?.high, 0.80, "high", 1e-12)
near(klines.last?.volume, 148976.1, "volume", 1e-6)
expect(MarketText.parseKlines(#"{"code":-1121,"msg":"Invalid symbol."}"#).isEmpty, "an error reply gives no candles")
same(MarketText.parseRss("<rss><channel><title>feed</title><item><title>Bitcoin climbs &amp; holds - Site</title><link>x</link></item><item><title><![CDATA[Ether slips]]></title></item></channel></rss>"),
     ["Bitcoin climbs & holds - Site", "Ether slips"], "headlines")
same(MarketText.symbols("btc/usdt، ETHUSDT xx btcusdt"), ["BTCUSDT", "ETHUSDT"], "symbols cleaned")
same(Fmt.money(12345.678), "12,345.68", "money")
same(Fmt.signed(-42.02), "-42.02", "signed loss")
same(Fmt.signed(1234.5), "+1,234.50", "signed gain")

let lim = Limits()
var acct = Account(10_000)
var res = Paper.open(&acct, lim, "BTCUSDT", .long, 100, sl: 98, tp: 104, atr: nil, now: 1000, reason: "سبب", equity: 10_000)
expect(res.changed, "a trade opens", res.text)
near(acct.positions.first?.qty, 20, "the position cap decides the size")
near(acct.positions.first?.sl, 98, "the stop as given")
near(acct.cash, 9_999, "entry fee")
expect(!Paper.open(&acct, lim, "BTCUSDT", .long, 100, sl: 98, tp: 104, atr: nil, now: 1000, reason: "", equity: 10_000).changed, "one position per symbol")
near(Paper.equity(acct, ["BTCUSDT": 102]), 10_039, "equity with an open profit")
let closedWin = Paper.close(&acct, lim, "BTCUSDT", 104, now: 5, why: "agent")
near(closedWin?.pnl, 77.96, "profit net of both fees")
near(acct.cash, 10_077.96, "cash after the close")
same(Paper.stats(acct).wins, 1, "one winning trade")

var small = Account(1_000)
_ = Paper.open(&small, lim, "ETHUSDT", .long, 100, sl: 99, tp: nil, atr: nil, now: 0, reason: "", equity: 1_000)
near(small.positions.first?.qty, 10, "the risk rule decides the size")
near(small.positions.first?.tp, 102, "a missing target is twice the stop distance")

acct = Account(10_000)
res = Paper.open(&acct, lim, "BTCUSDT", .long, 100, sl: 90, tp: nil, atr: nil, now: 0, reason: "", equity: 10_000)
near(acct.positions.first?.sl, 97, "a distant stop is pulled in")
expect(res.text.contains("قُرّب"), "and the owner is told", res.text)
acct = Account(10_000)
_ = Paper.open(&acct, lim, "BTCUSDT", .long, 100, sl: nil, tp: nil, atr: 1, now: 0, reason: "", equity: 10_000)
near(acct.positions.first?.sl, 98.5, "a missing stop is set from ATR")
near(acct.positions.first?.tp, 103, "and the target from it")
acct = Account(10_000)
res = Paper.open(&acct, lim, "BTCUSDT", .long, 100, sl: 105, tp: 95, atr: nil, now: 0, reason: "", equity: 10_000)
near(acct.positions.first?.sl, 97, "a stop on the wrong side is replaced")
near(acct.positions.first?.tp, 106, "a target on the wrong side is replaced")
expect(res.text.contains("تلقائياً"), "the automatic stop is reported")
acct = Account(10_000)
_ = Paper.open(&acct, lim, "BTCUSDT", .short, 100, sl: 102, tp: 96, atr: nil, now: 0, reason: "", equity: 10_000)
same(acct.positions.first?.side, .short, "a short opens")
near(Paper.close(&acct, lim, "BTCUSDT", 102, now: 5, why: "sl")?.pnl, -42.02, "a short stopped out")
var noShort = Limits()
noShort.allowShort = false
acct = Account(10_000)
expect(!Paper.open(&acct, noShort, "BTCUSDT", .short, 100, sl: 102, tp: 96, atr: nil, now: 0, reason: "", equity: 10_000).changed, "shorts can be switched off")

let hourMs: Int64 = 3_600_000
let t0 = 100 * hourMs
func freshLong() -> Account {
    var a = Account(10_000)
    _ = Paper.open(&a, lim, "BTCUSDT", .long, 100, sl: 98, tp: 104, atr: nil, now: t0 + 60_000, reason: "", equity: 10_000)
    return a
}
let quiet = [bar(t0, 95, 101, 90, 100), bar(t0 + hourMs, 100, 103, 99, 102), bar(t0 + 2 * hourMs, 102, 103.5, 101, 103)]
acct = freshLong()
expect(Paper.sweep(&acct, lim, "BTCUSDT", quiet, now: t0 + 2 * hourMs) == nil, "the entry candle's earlier dip is not a stop-out")
same(acct.positions.first?.checkedAt, t0 + hourMs, "finished candles are not looked at twice")
acct = freshLong()
let hitTarget = Paper.sweep(&acct, lim, "BTCUSDT", quiet + [bar(t0 + 3 * hourMs, 103, 104.5, 102.5, 104.2)], now: t0 + 3 * hourMs)
same(hitTarget?.why, "tp", "the target is found")
near(hitTarget?.exit, 104, "at the target's price")
acct = freshLong()
let hitStop = Paper.sweep(&acct, lim, "BTCUSDT", [bar(t0, 95, 101, 90, 100), bar(t0 + hourMs, 100, 105, 97, 101)], now: t0 + hourMs)
same(hitStop?.why, "sl", "a candle that spans both counts as the stop")
expect((hitStop?.pnl ?? 0) < 0, "and is a loss")
acct = freshLong()
same(Paper.sweep(&acct, lim, "BTCUSDT", [bar(t0, 100, 101, 96, 97)], now: t0)?.why, "sl", "the price now under the stop closes the trade")

var two = Limits()
two.maxTradesPerDay = 2
acct = Account(10_000)
Paper.rollDay(&acct, "2026-11-20", 10_000)
expect(Paper.open(&acct, two, "AAAUSDT", .long, 100, sl: 98, tp: nil, atr: nil, now: 0, reason: "", equity: 10_000).changed
        && Paper.open(&acct, two, "BBBUSDT", .long, 100, sl: 98, tp: nil, atr: nil, now: 0, reason: "", equity: 10_000).changed, "two trades in a day")
res = Paper.open(&acct, two, "CCCUSDT", .long, 100, sl: 98, tp: nil, atr: nil, now: 0, reason: "", equity: 10_000)
expect(!res.changed && res.text.contains("حد الصفقات"), "the third is refused", res.text)
expect(Paper.apply(&acct, two, Call(symbol: "AAAUSDT", action: "close", sl: nil, tp: nil, confidence: 0, reason: ""), price: 101, atr: nil, now: 1, equity: 10_000).changed, "closing is always allowed")
Paper.rollDay(&acct, "2026-11-21", 10_000)
same(acct.tradesToday, 0, "a new day starts clean")
acct = Account(10_000)
Paper.rollDay(&acct, "2026-11-20", 10_000)
res = Paper.open(&acct, lim, "AAAUSDT", .long, 100, sl: 98, tp: nil, atr: nil, now: 0, reason: "", equity: 9_600)
expect(!res.changed && res.text.contains("خسارة اليوم"), "the daily loss limit stops new trades", res.text)

acct = Account(10_000)
func decide(_ action: String, _ conf: Int, _ price: Double = 100) -> Bool {
    Paper.apply(&acct, lim, Call(symbol: "BTCUSDT", action: action, sl: action == "sell" ? 103 : 98, tp: action == "sell" ? 97 : 104, confidence: conf, reason: "سبب"), price: price, atr: nil, now: 0, equity: 10_000).changed
}
expect(!decide("buy", 40), "low confidence opens nothing")
expect(!decide("hold", 90) && !decide("close", 90), "hold and an empty close change nothing")
expect(decide("BUY", 80), "a confident buy opens a long")
expect(!decide("buy", 80, 101), "buying again leaves the position alone")
expect(decide("sell", 80, 101) && acct.positions.isEmpty && acct.closed.first?.why == "agent", "sell while long closes it, without flipping")
expect(!decide("dance", 99), "an unknown action does nothing")

let climb = (0..<150).map { i -> Candle in
    let c = 100.0 * (1 + 0.001 * Double(i))
    return bar(Int64(i) * hourMs, c * 0.999, c * 1.002, c * 0.998, c, 50)
}
let brief = Analysis.brief("BTCUSDT", "1h", climb, "4h", Array(climb.suffix(80)))
same(brief?.higherTrend, "up", "the higher trend")
expect((brief?.rsi ?? 0) > 70 && (brief?.ema20 ?? 0) > (brief?.ema50 ?? 1e9) && (brief?.price ?? 0) > (brief?.ema20 ?? 1e9), "a climb reads as a climb")
near(brief?.change24h, 2.13, "24-hour change", 0.05)
expect(Analysis.brief("BTCUSDT", "1h", Array(climb.prefix(20)), "4h", climb) == nil, "too little history gives no brief")
if let b = brief {
    acct = Account(10_000)
    Paper.rollDay(&acct, "d", 10_000)
    _ = Paper.open(&acct, lim, "BTCUSDT", .long, b.price, sl: nil, tp: nil, atr: b.atr, now: 0, reason: "اتجاه صاعد", equity: 10_000)
    let shown = TradeText.user(now: "Friday 2026-11-20 17:40", acct, lim, ["BTCUSDT": b.price], [b], ["Bitcoin climbs"], nowMs: 60_000)
    for part in ["NOW: Friday 2026-11-20", "ACCOUNT: equity", "OPEN POSITIONS:", "BTCUSDT LONG", "MARKET:", "RSI14", "EMA20", "ATR14", "4h trend: up", "HEADLINES", "Bitcoin climbs", "trades today 1/6", "daily loss limit 3.0%"] {
        expect(shown.contains(part), "the model is shown «\(part)»", shown)
    }
}
expect(TradeText.system.contains("PAPER") && TradeText.system.contains("hold") && (J.parse(TradeText.toolSchema) as? JSON) != nil, "the trading prompt and its tool")

print(failures == 0 ? "LOGIC: OK (\(passed) checks)" : "LOGIC: FAILED (\(failures) failed, \(passed) passed)")
exit(failures == 0 ? 0 : 1)
