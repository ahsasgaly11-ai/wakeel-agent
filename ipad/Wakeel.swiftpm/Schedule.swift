import Foundation

/// Finds a time in a task — "بعد 10 دقائق", "الساعة 8 مساءً", "غداً الساعة 7", "in 5 minutes" — so the task
/// can wait for it instead of being handed to the model, which cannot wait.
///
/// Only the instruction is searched. Text after a colon, or inside quotes or brackets, is the content of
/// a message ("أرسل له: سأصل بعد 5 دقائق") and is left alone.
enum Schedule {
    struct Plan {
        /// The task without its time phrase: what runs when the time comes.
        let task: String
        let at: Date
        let phrase: String
    }

    static let minDelay: TimeInterval = 3
    static let maxDelay: TimeInterval = 7 * 24 * 3600

    /// Alarms, timers and reminders carry a time of their own.
    private static let notASchedule = Rx("(منبه|نبهني|صحيني|موقت|تايمر|ذكرني|تذكير|alarm|timer|remind|stopwatch)")

    private static let B = "(?<![\\p{L}\\p{N}])"
    private static let E = "(?![\\p{L}\\p{N}])"
    private static let units = "ثاني[ةه]|ثانيتين|ثواني|ثوان|دقيق[ةه]|دقيقتين|دقائق|دقايق|ساع[ةه]|ساعتين|ساعات"
    private static let words = "واحد[ةه]?|[اإ]ثنين|اثنتين|ثلاث[ةه]?|[أا]ربع[ةه]?|خمس[ةه]?|ست[ةه]?|سبع[ةه]?|ثماني[ةه]?|ثمان|تسع[ةه]?|عشر[ةه]?|" +
        "عشرين|ثلاثين|[أا]ربعين|خمسين|ستين|نصف|ربع|ثلث"
    private static let day = "اليوم|غد[اً]*|بكر[ةهاى]"
    private static let ord = "الحادي[ةه]\\s+عشر[ةه]?|الثاني[ةه]\\s+عشر[ةه]?|الواحد[ةه]|الثاني[ةه]|الثالث[ةه]|الرابع[ةه]|الخامس[ةه]|" +
        "السادس[ةه]|السابع[ةه]|الثامن[ةه]|التاسع[ةه]|العاشر[ةه]"
    private static let part = "بعد\\s+الظهر|ص|م|صباح[اً]*|الصبح|الصباح|مساء[اً]*|المساء|ظهر[اً]*|الظهر|عصر[اً]*|العصر|ليل[اً]*|الليل|بالليل|" +
        "فجر[اً]*|الفجر|am|pm|a\\.m\\.?|p\\.m\\.?"

    // بعد [مرور] [10 | عشر | نصف] دقائق [ونصف] [من الآن]
    private static let relative = Rx(
        B + "بعد\\s+(?:مرور\\s+)?(?:(?:حوالي|تقريب[اً]*)\\s+)?(?:(\\d{1,3})\\s*|(" + words + ")\\s+)?(" + units + ")" + E +
            "(?:\\s*و\\s*(نصف|ربع|ثلث)" + E + ")?(?:\\s+من\\s+ال[اآ]ن" + E + ")?"
    )
    private static let relativeEn = Rx(
        "\\b(?:in|after)\\s+(?:(\\d{1,3})\\s*|(an?|one|two|three|five|ten|half\\s+an?)\\s+)(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b(?:\\s+from\\s+now\\b)?",
        ci: true
    )
    // [غداً] [في] [تمام] الساعة 8[:30] [والنصف] [إلا ربعاً] [مساءً] [غداً]
    private static let absolute = Rx(
        B + "(?:(" + day + ")\\s+)?(?:(?:في|عند|على)\\s+)?(?:تمام\\s+)?الساع[ةه]\\s+(\\d{1,2}(?!\\d)|(?:" + ord + ")" + E + ")" +
            "(?:\\s*[:.]\\s*(\\d{2})(?!\\d))?" +
            "(?:\\s*و\\s*(?:ال)?(نصف|ربع|ثلث)" + E + ")?" +
            "(?:\\s+[إا]لا\\s+(?:ال)?(ربع|ثلث)[اً]*" + E + ")?" +
            "(?:\\s*(" + part + ")" + E + ")?" +
            "(?:\\s+(" + day + ")" + E + ")?",
        ci: true
    )
    private static let absoluteEn = Rx(
        "\\b(?:(today|tomorrow)\\s+)?at\\s+(\\d{1,2})(?!\\d)(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?(?![\\p{L}\\p{N}])(?:\\s+(today|tomorrow)\\b)?",
        ci: true
    )
    private static let spaces = Rx("\\s+")
    private static let spaceBeforeMark = Rx("\\s+([،,.؛])")

    static func parse(_ raw: String, now: Date = Date(), zone: TimeZone = .current) -> Plan? {
        if raw.count < 6 || notASchedule.test(TextTools.norm(raw)) { return nil }
        let body = instruction(raw)     // same UTF-16 length as raw, so a match's range is a range in raw

        var at: Date?
        var range: NSRange?
        if let m = relative.first(body) {
            let unit = TextTools.norm(m[3] ?? "")
            let count: Double
            if let d = m[1], let n = Double(d) { count = n }
            else if let w = m[2] { count = wordNumber(w) }
            else { count = unit.hasSuffix("تين") ? 2 : 1 }
            let extra = m[4].map { wordNumber($0) } ?? 0
            at = now.addingTimeInterval((count + extra) * unitSeconds(unit))
            range = m.range
        } else if let m = relativeEn.first(body) {
            let u = (m[3] ?? "").lowercased()
            let per: Double = u.hasPrefix("s") ? 1 : u.hasPrefix("m") ? 60 : 3600
            let count: Double
            if let d = m[1], let n = Double(d) { count = n }
            else {
                switch (m[2] ?? "").lowercased().split(separator: " ").first.map(String.init) ?? "" {
                case "two": count = 2
                case "three": count = 3
                case "five": count = 5
                case "ten": count = 10
                case "half": count = 0.5
                default: count = 1
                }
            }
            at = now.addingTimeInterval(count * per)
            range = m.range
        } else if let m = absolute.first(body) {
            guard var hour = hourOf(m[2] ?? "") else { return nil }
            var minute = m[3].flatMap { Int($0) } ?? 0
            if let w = m[4] { minute = Int((wordNumber(w) * 60).rounded()) }               // والنصف
            if let w = m[5] {                                                               // إلا ربعاً
                minute = 60 - Int((wordNumber(w) * 60).rounded())
                hour = hour == 0 ? 23 : hour == 1 ? 12 : hour - 1
            }
            at = resolve(hour: hour, minute: minute, part: m[6], day: m[1] ?? m[7], now: now, zone: zone)
            range = m.range
        } else if let m = absoluteEn.first(body) {
            at = resolve(hour: Int(m[2] ?? "") ?? -1, minute: m[3].flatMap { Int($0) } ?? 0, part: m[4], day: m[1] ?? m[5], now: now, zone: zone)
            range = m.range
        }

        guard let time = at, let r = range else { return nil }
        let delay = time.timeIntervalSince(now)
        if delay < minDelay || delay > maxDelay { return nil }
        let ns = raw as NSString
        var task = ns.substring(to: r.location) + " " + ns.substring(from: r.location + r.length)
        task = spaceBeforeMark.replace(spaces.replace(task, with: " "), with: "$1")
        task = task.trimmingCharacters(in: CharacterSet(charactersIn: " ،,.؛-–—\n\t"))
        if task.count < 3 { return nil }
        return Plan(task: task, at: time, phrase: ns.substring(with: r).trimmed)
    }

    /// "3:05 م"
    static func clock(_ at: Date, zone: TimeZone = .current) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let c = cal.dateComponents([.hour, .minute], from: at)
        let h24 = c.hour ?? 0
        let h = h24 % 12 == 0 ? 12 : h24 % 12
        return "\(h):" + String(format: "%02d", c.minute ?? 0) + (h24 < 12 ? " ص" : " م")
    }

    /// How the owner is told when: "بعد 10 دقيقة (الساعة 3:20 ص)", "اليوم الساعة 8:00 م", "غداً الساعة 7:00 ص".
    static func describe(_ at: Date, now: Date = Date(), zone: TimeZone = .current) -> String {
        let d = at.timeIntervalSince(now)
        let t = clock(at, zone: zone)
        if d < 55 { return "بعد \(max(1, Int(d.rounded()))) ثانية" }
        if d < 90 { return "بعد دقيقة (الساعة \(t))" }
        if d < 3570 { return "بعد \(Int((d / 60).rounded())) دقيقة (الساعة \(t))" }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        if cal.isDate(at, inSameDayAs: now) { return "اليوم الساعة \(t)" }
        if let next = cal.date(byAdding: .day, value: 1, to: now), cal.isDate(at, inSameDayAs: next) { return "غداً الساعة \(t)" }
        let c = cal.dateComponents([.day, .month], from: at)
        return "يوم \(c.day ?? 0)/\(c.month ?? 0) الساعة \(t)"
    }

    // MARK: - parts

    /// Digits made ASCII, and everything that is message content blanked out — unit for unit, so positions stay the same.
    private static func instruction(_ s: String) -> String {
        var u = Array(s.utf16)
        for i in u.indices {
            if (0x0660...0x0669).contains(u[i]) { u[i] = u[i] - 0x0660 + 0x30 }
            else if (0x06F0...0x06F9).contains(u[i]) { u[i] = u[i] - 0x06F0 + 0x30 }
        }
        let closing: [UInt16: UInt16] = [0x00AB: 0x00BB, 0x201C: 0x201D, 0x22: 0x22, 0x28: 0x29, 0x5B: 0x5D, 0x7B: 0x7D]
        func digit(_ c: UInt16) -> Bool { c >= 0x30 && c <= 0x39 }
        var i = 0
        while i < u.count {
            let c = u[i]
            if let close = closing[c] {
                var j = i + 1
                while j < u.count && u[j] != close { j += 1 }
                let end = min(j, u.count - 1)
                for k in i...end { u[k] = 0x20 }
                i = end + 1
                continue
            }
            if c == 0x3A || c == 0xFF1A {
                let inClock = i > 0 && digit(u[i - 1]) && i + 1 < u.count && digit(u[i + 1])
                if !inClock {
                    for k in i..<u.count { u[k] = 0x20 }
                    break
                }
            }
            i += 1
        }
        return String(utf16CodeUnits: u, count: u.count)
    }

    private static func unitSeconds(_ unit: String) -> Double {
        if unit.hasPrefix("ثان") || unit.hasPrefix("ثوان") { return 1 }
        if unit.hasPrefix("دق") { return 60 }
        return 3600
    }

    private static func wordNumber(_ w: String) -> Double {
        switch TextTools.norm(w) {
        case "واحد", "واحده": return 1
        case "اثنين", "اثنتين": return 2
        case "ثلاث", "ثلاثه": return 3
        case "اربع", "اربعه": return 4
        case "خمس", "خمسه": return 5
        case "ست", "سته": return 6
        case "سبع", "سبعه": return 7
        case "ثمان", "ثماني", "ثمانيه": return 8
        case "تسع", "تسعه": return 9
        case "عشر", "عشره": return 10
        case "عشرين": return 20
        case "ثلاثين": return 30
        case "اربعين": return 40
        case "خمسين": return 50
        case "ستين": return 60
        case "نصف": return 0.5
        case "ربع": return 0.25
        case "ثلث": return 1.0 / 3
        default: return 1
        }
    }

    private static func hourOf(_ s: String) -> Int? {
        if let n = Int(s) { return n }
        let n = TextTools.norm(s)
        if n.hasPrefix("الحادي") { return 11 }
        if n.hasPrefix("الثاني") && n.contains("عشر") { return 12 }
        let table: [(String, Int)] = [("الواحد", 1), ("الثاني", 2), ("الثالث", 3), ("الرابع", 4), ("الخامس", 5), ("السادس", 6),
                                      ("السابع", 7), ("الثامن", 8), ("التاسع", 9), ("العاشر", 10)]
        return table.first { n.hasPrefix($0.0) }?.1
    }

    /// Turns "8", "8 مساءً", "غداً 7" into a moment. Without صباحاً/مساءً the next time the clock shows that hour
    /// is meant; for tomorrow, 1–6 is taken as the afternoon and 7–11 as the morning, as people say it.
    private static func resolve(hour hourRaw: Int, minute: Int, part: String?, day: String?, now: Date, zone: TimeZone) -> Date? {
        guard (0...23).contains(hourRaw), (0...59).contains(minute) else { return nil }
        let p = part.map { TextTools.norm($0).replacingOccurrences(of: ".", with: "") }
        let d = day.map { TextTools.norm($0) }
        let tomorrow = d.map { $0.hasPrefix("غد") || $0.hasPrefix("بكر") || $0 == "tomorrow" } ?? false
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        func at(_ dayOffset: Int, _ hour: Int) -> Date {
            let base = cal.date(byAdding: .day, value: dayOffset, to: now) ?? now
            var c = cal.dateComponents([.year, .month, .day], from: base)
            c.hour = hour
            c.minute = minute
            c.second = 0
            return cal.date(from: c) ?? base
        }
        let h12 = hourRaw % 12
        var fixed: Int?
        if hourRaw > 12 || hourRaw == 0 { fixed = hourRaw }
        else if let p = p {
            if p == "ص" || p == "am" || p.contains("صبح") || p.contains("صباح") || p.contains("فجر") { fixed = h12 }
            else if p.contains("ليل") { fixed = (hourRaw == 12 || hourRaw <= 4) ? h12 : h12 + 12 }
            else if p.contains("ظهر") && !p.hasPrefix("بعد") && (10...11).contains(hourRaw) { fixed = hourRaw }
            else { fixed = h12 + 12 }
        }
        if let f = fixed {
            if tomorrow { return at(1, f) }
            let t = at(0, f)
            return t.timeIntervalSince(now) > 1 ? t : at(1, f)
        }
        if tomorrow { return at(1, (1...6).contains(hourRaw) ? hourRaw + 12 : hourRaw) }
        return [at(0, h12), at(0, h12 + 12), at(1, h12), at(1, h12 + 12)].filter { $0.timeIntervalSince(now) > 1 }.min()
    }
}
