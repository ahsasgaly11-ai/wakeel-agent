import Foundation

/// The reasoning behind saved skills, kept free of any screen or network so it can be tested on its own:
/// turning one successful run into a reusable pattern, and recognising a later task that fits it.
enum SkillLogic {
    private static let notWord = Rx("[^\\p{L}\\p{N}]+")

    private static func words(_ s: String) -> [String] { s.split(whereSeparator: { $0.isWhitespace }).map(String.init) }
    private static func bare(_ s: String) -> String { notWord.replace(TextTools.norm(s), with: " ").trimmed }

    /// Where the words of [value] sit in [task], as a range of word indices.
    private static func locate(_ value: String, in taskWords: [String]) -> Range<Int>? {
        let v = bare(value).split(separator: " ").map(String.init)
        if v.isEmpty || v.joined().count < 2 { return nil }
        let t = taskWords.map { bare($0) }
        guard t.count >= v.count else { return nil }
        for start in 0...(t.count - v.count) where Array(t[start..<(start + v.count)]) == v { return start..<(start + v.count) }
        return nil
    }

    /// Turns a task and the steps that carried it out into a pattern with slots.
    /// What was typed or chosen and also appears in the task becomes a slot, so "ابحث عن قهوة" teaches "ابحث عن {p1}".
    static func generalize(task: String, steps: [SkillStep]) -> (pattern: String, params: [String], steps: [SkillStep]) {
        var taskWords = words(task)
        var out = steps
        var params: [String] = []

        func fixedWords(_ ws: [String]) -> Int { ws.filter { !$0.hasPrefix("{") && bare($0).count >= 2 }.count }

        // Typed texts first, longest first; then names tapped in a list
        var values: [(String, Bool)] = []       // (value, isTyped)
        for s in steps where (s.type == "type" || s.type == "select") && !s.text.trimmed.isEmpty { values.append((s.text.trimmed, true)) }
        for s in steps where s.type == "tap" && s.label.count >= 3 { values.append((s.label, false)) }
        var seen = Set<String>()
        values = values.filter { seen.insert(bare($0.0)).inserted }.sorted { ($0.1 ? 1 : 0, $0.0.count) > ($1.1 ? 1 : 0, $1.0.count) }

        for (value, isTyped) in values {
            guard let r = locate(value, in: taskWords) else { continue }
            var trial = taskWords
            trial.replaceSubrange(r, with: ["{p\(params.count + 1)}"])
            // A pattern needs words of its own, or it would swallow unrelated tasks
            if fixedWords(trial) < (isTyped ? 1 : 2) { continue }
            let slot = "{p\(params.count + 1)}"
            params.append("p\(params.count + 1)")
            taskWords = trial
            let enc = Commands.enc(value)
            for i in out.indices {
                switch out[i].type {
                case "type", "select":
                    if bare(out[i].text) == bare(value) { out[i].text = slot }
                case "tap":
                    if !isTyped && bare(out[i].label) == bare(value) { out[i].label = slot; out[i].sel = "" }
                case "open":
                    // Only after the site's own address: a typed "amazon" must not turn amazon.com into a slot
                    let url = out[i].text
                    let cut = url.range(of: "://").flatMap { url.range(of: "/", range: $0.upperBound..<url.endIndex)?.lowerBound } ?? url.endIndex
                    let rest = String(url[cut...]).replacingOccurrences(of: enc, with: slot)
                        .replacingOccurrences(of: value.replacingOccurrences(of: " ", with: "+"), with: slot)
                    out[i].text = String(url[..<cut]) + rest
                default:
                    break
                }
            }
        }
        return (taskWords.joined(separator: " "), params, out)
    }

    /// Does [task] fit [pattern]? Returns the slot values (in the owner's own spelling), or nil.
    static func match(task: String, pattern: String) -> [String: String]? {
        let p = words(pattern)
        let t = words(task)
        let tn = t.map { bare($0) }
        var values: [String: String] = [:]

        func go(_ pi: Int, _ ti: Int) -> Bool {
            if pi == p.count { return ti == t.count }
            let token = p[pi]
            if token.hasPrefix("{") && token.hasSuffix("}") {
                let name = String(token.dropFirst().dropLast())
                if ti >= t.count { return false }
                // The shortest run of words that lets the rest of the pattern fit
                for end in (ti + 1)...t.count {
                    values[name] = t[ti..<end].joined(separator: " ").trimmingCharacters(in: CharacterSet(charactersIn: " :،,؛.«»\"'()"))
                    if !(values[name] ?? "").isEmpty && go(pi + 1, end) { return true }
                }
                values[name] = nil
                return false
            }
            if ti < t.count && tn[ti] == bare(token) { return go(pi + 1, ti + 1) }
            return false
        }
        return go(0, 0) ? values : nil
    }

    /// Puts slot values into a step's text. In an address the value is percent-encoded.
    static func fill(_ s: String, _ values: [String: String], encode: Bool = false) -> String {
        var out = s
        for (k, v) in values { out = out.replacingOccurrences(of: "{\(k)}", with: encode ? Commands.enc(v) : v) }
        return out
    }

    /// Why a demonstration is not worth saving, or nil when it is complete.
    static func incomplete(_ steps: [SkillStep], sawSecret: Bool) -> String? {
        if sawSecret { return "فيها كتابة كلمة مرور، وهذه لا أحفظها ولا أعيدها." }
        let acts = steps.filter { $0.type != "open" }
        if acts.isEmpty { return "لم تنفّذ فيها أي خطوة بعد فتح الصفحة." }
        if let last = steps.last, last.type == "type" { return "انتهت بكتابة نص دون إرساله أو تأكيده." }
        return nil
    }

    /// The best saved skill for a task: an exact fit of its pattern.
    static func find(_ task: String, in skills: [Skill]) -> (Skill, [String: String])? {
        var best: (Skill, [String: String])?
        for s in skills {
            guard let v = match(task: task, pattern: s.pattern) else { continue }
            // Prefer the most specific pattern (fewest slots), then the one that worked most
            if let b = best {
                if (s.params.count, -s.successes) < (b.0.params.count, -b.0.successes) { best = (s, v) }
            } else { best = (s, v) }
        }
        return best
    }
}
