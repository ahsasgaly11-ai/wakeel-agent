import Foundation

/// What a task turns into when it needs no model at all.
enum Cmd: Equatable {
    /// Leaves the app: a chat link, a call, a mail, another app.
    case external(url: String, say: String)
    /// Opens in the agent's own browser.
    case browse(url: String, say: String)
    case answer(String)
}

/// Tasks the app can do by itself, for free: a WhatsApp chat with a ready message, a call, a mail, a map,
/// a search, one of the owner's Shortcuts, another app.
enum Commands {
    static func enc(_ s: String) -> String {
        var ok = CharacterSet.alphanumerics
        ok.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: ok) ?? s
    }

    private static let phone = Rx("\\+?[0-9٠-٩][0-9٠-٩ \\-]{6,}[0-9٠-٩]")
    private static let email = Rx("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private static let domain = Rx("^(?:https?://)?(?:[a-z0-9\\-]+\\.)+[a-z]{2,}(?:/\\S*)?$", ci: true)
    private static let shortcut = Rx("^(?:شغل|شغّل|نفذ|نفّذ|run)\\s+(?:ال)?(?:اختصار|shortcut)\\s+(.+?)(?:\\s*[:：]\\s*(.+))?$", ci: true)
    private static let leadIn = Rx("^(?:[:：،,\\-\\s]|وقل له|وقل لها|وقل|قل له|نصها|نصه|رساله|الرساله|تقول|بعنوان|message|saying)+", ci: true)
    private static let chained = Rx("(\\sثم\\s|[،,؛]|\\sو(?:لخص|قارن|اكتب|اعطني|اخبرني|انشئ|اعمل|حلل|استخرج|اجمع|رتب)|\\sand\\s+(?:then|summar|compare|write|tell|make|create))", ci: true)

    private static let sendVerbs: Set<String> = ["ارسل", "ابعث", "راسل", "ابعت", "دز", "send", "message", "text", "افتح", "open", "كلم"]
    private static let callVerbs: Set<String> = ["اتصل", "اتصال", "كلم", "دق", "call", "dial", "phone"]
    private static let openVerbs: Set<String> = ["افتح", "شغل", "اذهب", "ادخل", "open", "launch", "go"]

    /// Apps that can be opened by name. Only schemes that have been stable for years.
    private static let apps: [(names: [String], url: String, title: String)] = [
        (["واتساب", "الواتساب", "واتس", "whatsapp"], "whatsapp://", "واتساب"),
        (["انستجرام", "انستقرام", "الانستجرام", "انستا", "instagram"], "instagram://", "انستجرام"),
        (["يوتيوب", "اليوتيوب", "youtube"], "youtube://", "يوتيوب"),
        (["تيليجرام", "تلجرام", "تليجرام", "telegram"], "tg://", "تيليجرام"),
        (["تويتر", "اكس", "twitter", "x"], "twitter://", "X"),
        (["فيسبوك", "الفيسبوك", "facebook"], "fb://", "فيسبوك"),
        (["تيك توك", "تيكتوك", "tiktok"], "snssdk1233://", "تيك توك"),
        (["سناب", "سناب شات", "snapchat"], "snapchat://", "سناب شات"),
        (["الخرائط", "خرائط", "maps"], "maps://", "الخرائط"),
        (["الاختصارات", "اختصارات", "shortcuts"], "shortcuts://", "الاختصارات"),
        (["الموسيقى", "موسيقى", "music"], "music://", "الموسيقى"),
        (["الصور", "الاستوديو", "photos"], "photos-redirect://", "الصور"),
        (["الملفات", "files"], "shareddocuments://", "الملفات"),
        (["الملاحظات", "ملاحظات", "notes"], "mobilenotes://", "الملاحظات"),
        (["التذكيرات", "تذكيرات", "reminders"], "x-apple-reminderkit://", "التذكيرات"),
        (["التقويم", "تقويم", "calendar"], "calshow://", "التقويم"),
        (["البريد", "الايميل", "mail"], "message://", "البريد"),
        (["الرسائل", "رسائل", "messages", "imessage"], "sms://", "الرسائل"),
        (["فيس تايم", "facetime"], "facetime://", "فيس تايم"),
        (["المتجر", "اب ستور", "app store"], "itms-apps://", "App Store"),
        (["وورد", "الوورد", "word"], "ms-word://", "Word"),
        (["اكسل", "الاكسل", "excel"], "ms-excel://", "Excel"),
        (["بوربوينت", "باوربوينت", "البوربوينت", "powerpoint"], "ms-powerpoint://", "PowerPoint"),
    ]

    /// [dial]: the country calling code put in front of a local number in a WhatsApp link.
    /// [shortcuts]: names of the Shortcuts the owner registered; a task that is exactly such a name runs it.
    static func parse(_ task: String, dial: String? = nil, shortcuts: [String] = [], search: String = "https://www.google.com/search?q=") -> Cmd? {
        let raw = task.trimmed
        let n = TextTools.norm(raw)
        let tok = n.split(separator: " ").map(String.init)
        guard let verb = tok.first else { return nil }

        // One of the owner's Shortcuts, by name or with "شغّل اختصار …"
        if let m = shortcut.first(raw), let name = m[1]?.trimmed, !name.isEmpty {
            return .external(url: shortcutURL(name, input: m[2]?.trimmed), say: "شغّلتُ الاختصار «\(name)».")
        }
        if let name = shortcuts.first(where: { TextTools.norm($0) == n }) {
            return .external(url: shortcutURL(name, input: nil), say: "شغّلتُ الاختصار «\(name)».")
        }

        // The time and the date
        if Rx("^(كم الساعه|الساعه كم|كم الوقت|ما الوقت|what time is it|what's the time)( الان| now)?[؟?]?$").test(n) {
            return .answer("الساعة الآن \(Schedule.clock(Date())).")
        }
        if Rx("^(ما |كم )?(تاريخ اليوم|التاريخ|التاريخ اليوم|اليوم كم)[؟?]?$|^what('s| is) (the date|today's date)\\??$").test(n) {
            let f = DateFormatter()
            f.locale = Locale(identifier: "ar")
            f.dateFormat = "EEEE d MMMM yyyy"
            return .answer("اليوم \(f.string(from: Date())).")
        }

        let number = phone.first(raw).map { m -> String in
            TextTools.norm((raw as NSString).substring(with: m.range)).filter { $0.isNumber || $0 == "+" }
        }
        func after(_ range: NSRange) -> String {
            leadIn.replace((raw as NSString).substring(from: range.location + range.length), with: "").trimmed
        }

        // A mail to an address
        if let m = email.first(raw), sendVerbs.contains(verb) || n.contains("بريد") || n.contains("ايميل") || n.contains("mail") {
            let address = (raw as NSString).substring(with: m.range)
            let body = after(m.range)
            return .external(url: "mailto:\(address)" + (body.isEmpty ? "" : "?body=\(enc(body))"), say: "فتحتُ رسالة بريد إلى \(address)\(body.isEmpty ? "" : " والنص جاهز") — راجعها واضغط إرسال.")
        }

        if let num = number, let m = phone.first(raw) {
            let wa = tok.contains { ["واتساب", "واتس", "الواتساب", "الواتس", "بالواتساب", "بواتساب", "whatsapp"].contains($0) }
            if wa && sendVerbs.contains(verb) {
                let msg = after(m.range)
                let intl = Dial.international(num, code: dial)
                return .external(url: "https://wa.me/\(intl)" + (msg.isEmpty ? "" : "?text=\(enc(msg))"),
                                 say: "فتحتُ محادثة واتساب مع \(num)" + (msg.isEmpty ? "." : " والرسالة جاهزة — اضغط إرسال."))
            }
            if callVerbs.contains(verb) && !wa { return .external(url: "tel:\(num)", say: "بدأتُ الاتصال بـ \(num).") }
            if sendVerbs.contains(verb) && (n.contains("رساله") || n.contains("sms") || n.contains("نصيه") || n.contains("imessage")) {
                let msg = after(m.range)
                return .external(url: "sms:\(num)" + (msg.isEmpty ? "" : "&body=\(enc(msg))"), say: "فتحتُ رسالة إلى \(num)" + (msg.isEmpty ? "." : " والنص جاهز — اضغط إرسال."))
            }
        }

        // Anything below is a whole-task command: a longer instruction goes to the model
        if chained.test(n) || tok.count > 9 { return nil }

        // The words to look for are taken from the task as written, not from its normalised form
        if let m = Rx("^(?:[اأإ]بحث|دو[ّ]?ر|بحث|search)\\s+(?:في|على|ب|in|on)?\\s*(?:ال)?(?:يوتيوب|youtube)\\s+(?:عن|على|for)?\\s*(.+)$", ci: true).first(raw), let q = m[1]?.trimmed, !q.isEmpty {
            return .browse(url: "https://www.youtube.com/results?search_query=\(enc(q))", say: "هذه نتائج يوتيوب عن «\(q)».")
        }
        if let m = Rx("^(?:[أا]ين يقع|[أا]ين تقع|وين|الطريق [إا]ل[ىي]|خذني [إا]ل[ىي]|وصلني [إا]ل[ىي]|directions to|navigate to|where is|map of)\\s+(.+?)[؟?]?$", ci: true).first(raw),
           let q = m[1]?.trimmed, !q.isEmpty, !domain.test(q) {
            let route = n.hasPrefix("الطريق") || n.hasPrefix("خذني") || n.hasPrefix("وصلني") || n.hasPrefix("directions") || n.hasPrefix("navigate")
            return .external(url: "http://maps.apple.com/?" + (route ? "daddr=" : "q=") + enc(q), say: "فتحتُ الخرائط على «\(q)».")
        }
        if let m = Rx("^(?:[اأإ]بحث|دو[ّ]?ر|search)\\s+(?:في\\s+)?(?:جوجل|قوقل|google|النت|ال[إا]نترنت)?\\s*(?:عن|for)\\s+(.+)$", ci: true).first(raw), let q = m[1]?.trimmed, !q.isEmpty {
            return .browse(url: search + enc(q), say: "هذه نتائج البحث عن «\(q)».")
        }
        if openVerbs.contains(verb) {
            var rest = Array(tok.dropFirst())
            while let f = rest.first, ["الي", "علي", "موقع", "صفحه", "تطبيق", "برنامج", "to", "the", "app", "site", "website"].contains(f) { rest.removeFirst() }
            let name = rest.joined(separator: " ")
            if domain.test(name) {
                let url = name.hasPrefix("http") ? name : "https://\(name)"
                return .browse(url: url, say: "فتحتُ \(name).")
            }
            if let app = apps.first(where: { $0.names.contains(name) }) {
                return .external(url: app.url, say: "فتحتُ \(app.title).")
            }
        }
        return nil
    }

    static func shortcutURL(_ name: String, input: String?) -> String {
        "shortcuts://run-shortcut?name=\(enc(name))" + ((input ?? "").isEmpty ? "" : "&input=text&text=\(enc(input ?? ""))")
    }
}

/// Country calling codes. A WhatsApp chat link only works with the full international number, while people
/// write numbers the local way ("70776067"); the device's region fills the gap.
enum Dial {
    private static let codes: [String: String] = [
        "qa": "974", "sa": "966", "ae": "971", "kw": "965", "bh": "973", "om": "968", "ye": "967", "iq": "964",
        "jo": "962", "lb": "961", "sy": "963", "ps": "970", "eg": "20", "sd": "249", "ly": "218", "tn": "216",
        "dz": "213", "ma": "212", "mr": "222", "tr": "90", "ir": "98", "in": "91", "pk": "92", "bd": "880",
        "lk": "94", "np": "977", "ph": "63", "id": "62", "my": "60", "us": "1", "ca": "1", "gb": "44",
        "fr": "33", "de": "49", "it": "39", "es": "34", "nl": "31", "se": "46", "au": "61",
    ]

    static func code(_ iso: String?) -> String? { codes[(iso ?? "").trimmed.lowercased()] }

    /// "70776067" with "974" → "97470776067". A number written with + or 00, or too long to be a local one,
    /// already carries its country code and is only stripped of the prefix.
    static func international(_ number: String, code: String?) -> String {
        let digits = number.filter { $0 >= "0" && $0 <= "9" }
        if number.trimmed.hasPrefix("+") { return digits }
        if digits.hasPrefix("00") { return String(digits.dropFirst(2)) }
        guard let code = code else { return digits }
        if digits.hasPrefix("0") { return code + String(digits.drop(while: { $0 == "0" })) }
        return digits.count <= 10 ? code + digits : digits
    }

    private static let waMe = Rx("^(https?://wa\\.me/)(\\+?[0-9]{6,15})(.*)$", ci: true)

    /// The same for a chat link written by the model: https://wa.me/70776067?text=…
    static func waLink(_ url: String, code: String?) -> String {
        guard let m = waMe.first(url), let head = m[1], let num = m[2] else { return url }
        return head + international(num, code: code) + (m[3] ?? "")
    }
}

/// What needs the owner's OK, and which sites the agent keeps its hands off.
enum Safety {
    private static let sensitive = Rx(
        "(ارسال|ارسل|send|post|نشر|انشر|share|مشاركه|دفع|ادفع|pay|شراء|اشتر|buy|purchase|order|اطلب|checkout|" +
            "حذف|احذف|delete|remove|ازاله|تحويل|حول|transfer|تاكيد|اكد|confirm|subscribe|اشترك|" +
            "تسجيل الخروج|logout|log out|sign out|حظر|block|ابلاغ|report|place order|اتمام الطلب|submit|تقديم)",
        ci: true
    )
    private static let trade = Rx(
        "(buy|sell|long|short|order|confirm|submit|transfer|withdraw|deposit|send|pay|convert|swap|stake|redeem|borrow|repay|" +
            "close position|close all|cancel|place|leverage|margin|subscribe|" +
            "شراء|اشتر|بيع|بع |طلب|تاكيد|اكد|تحويل|سحب|ايداع|ارسال|دفع|تبديل|صفقه|مركز|اغلاق|الغاء|رافعه|هامش|اكتتاب|اقتراض|تسديد)",
        ci: true
    )
    private static let sends = Rx("(ارسال|ارسل|send|نشر|انشر|post|publish|reply|tweet)", ci: true)
    private static let searchLike = Rx("(search|بحث|ابحث|find|filter|symbol|coin|pair)", ci: true)

    static let defaultBlocked = "bank, qnb, qib, cbq, alrayan, masraf, dukhan, dohabank, ahlibank, paypal, 1password, bitwarden, lastpass, metamask, trustwallet, ledger"
    static let defaultReadOnly = "binance, mexc, bybit, okx, kucoin, bitget, gate.io, htx, kraken, coinbase, exness, etoro, tradingview, forex"

    static func isSensitiveLabel(_ label: String) -> Bool { !label.trimmed.isEmpty && sensitive.test(TextTools.norm(label)) }
    static func isSendLabel(_ label: String) -> Bool { !label.trimmed.isEmpty && sends.test(TextTools.norm(label)) }
    static func isSearchLabel(_ label: String) -> Bool { searchLike.test(TextTools.norm(label)) }

    static func isTradeLabel(_ label: String) -> Bool {
        if label.trimmed.isEmpty { return false }
        let l = TextTools.norm(label).replacingOccurrences(of: "order book", with: "").replacingOccurrences(of: "orderbook", with: "")
        return trade.test(l + " ")
    }

    enum Policy { case allowed, readOnly, blocked }

    /// The policy for a page, from the owner's keyword lists ("bank, paypal, …").
    static func policy(host: String, blocked: String, readOnly: String) -> Policy {
        let h = host.lowercased()
        func hit(_ list: String) -> Bool {
            list.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.contains { !$0.isEmpty && h.contains($0) }
        }
        if h.isEmpty { return .allowed }
        if hit(blocked) { return .blocked }
        if hit(readOnly) { return .readOnly }
        return .allowed
    }
}
