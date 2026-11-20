import Foundation

typealias JSON = [String: Any]

/// Text helpers shared by the whole app: Arabic-aware normalisation, XML escaping, a small regex wrapper.
enum TextTools {
    /// Lower-case, Arabic letter forms unified, digits made ASCII, marks removed, spaces collapsed.
    /// Two strings that a person would call "the same words" normalise to the same string.
    static func norm(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.lowercased().unicodeScalars {
            switch u.value {
            case 0x064B...0x0652, 0x0670, 0x0640: continue                      // tashkeel, tatweel
            case 0x200B...0x200F, 0x202A...0x202E, 0x2066...0x2069, 0xFEFF: continue   // direction and zero-width marks
            case 0x0622, 0x0623, 0x0625, 0x0671: out.append("ا")
            case 0x0649: out.append("ي")
            case 0x0629: out.append("ه")
            case 0x0624: out.append("و")
            case 0x0626: out.append("ي")
            case 0x0660...0x0669: out.append(Unicode.Scalar(u.value - 0x0660 + 0x30)!)
            case 0x06F0...0x06F9: out.append(Unicode.Scalar(u.value - 0x06F0 + 0x30)!)
            case 0x2010...0x2013: out.append("-")
            case 0x00A0: out.append(" ")
            default: out.append(u)
            }
        }
        return String(out).split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" }).joined(separator: " ")
    }

    static func isRTL(_ s: String) -> Bool {
        for u in s.unicodeScalars where (0x0590...0x08FF).contains(u.value) || (0xFB1D...0xFEFC).contains(u.value) { return true }
        return false
    }

    static func xml(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count + 8)
        for u in s.unicodeScalars {
            switch u {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            default:
                // Control characters are not allowed in XML 1.0 and would make Office refuse the file
                if u.value < 0x20 && u != "\n" && u != "\t" && u != "\r" { continue }
                out.unicodeScalars.append(u)
            }
        }
        return out
    }

    /// `**bold**` marks → pieces of text with a bold flag.
    static func runs(_ text: String) -> [(String, Bool)] {
        var out: [(String, Bool)] = []
        var bold = false
        for piece in text.components(separatedBy: "**") {
            if !piece.isEmpty { out.append((piece, bold)) }
            bold.toggle()
        }
        return out.isEmpty ? [("", false)] : out
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    func take(_ n: Int) -> String { count <= n ? self : String(prefix(n)) }
}

/// JSON in and out of the loosely typed dictionaries the model APIs use.
enum J {
    static func parse(_ s: String) -> Any? {
        guard let d = s.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
    }

    static func string(_ v: Any) -> String {
        guard JSONSerialization.isValidJSONObject(v), let d = try? JSONSerialization.data(withJSONObject: v, options: []) else {
            if let s = v as? String, let d = try? JSONSerialization.data(withJSONObject: [s], options: []), let t = String(data: d, encoding: .utf8) {
                return String(t.dropFirst().dropLast())   // a bare string, quoted and escaped
            }
            return "null"
        }
        return String(data: d, encoding: .utf8) ?? "null"
    }

    /// The first {...} object in a text that may carry prose around it.
    static func object(in text: String) -> JSON? {
        if let o = parse(text) as? JSON { return o }
        guard let a = text.firstIndex(of: "{"), let z = text.lastIndex(of: "}"), a < z else { return nil }
        return parse(String(text[a...z])) as? JSON
    }

    static func int(_ v: Any?) -> Int? {
        if let n = v as? Int { return n }
        if let n = v as? Double { return Int(n) }
        if let s = v as? String { return Int(s.trimmed) }
        return nil
    }

    static func str(_ v: Any?) -> String? {
        if let s = v as? String { return s }
        if let n = v as? NSNumber { return n.stringValue }
        return nil
    }
}

/// A thin wrapper over NSRegularExpression (ICU syntax — the same patterns the Android app uses).
struct Rx {
    struct Match {
        let range: NSRange
        let groups: [String?]
        subscript(i: Int) -> String? { i < groups.count ? groups[i] : nil }
    }

    let re: NSRegularExpression

    init(_ pattern: String, ci: Bool = false) {
        // A pattern that does not compile is a programming error caught by the logic tests
        re = (try? NSRegularExpression(pattern: pattern, options: ci ? [.caseInsensitive] : [])) ?? NSRegularExpression()
    }

    func first(_ s: String) -> Match? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: ns.length)) else { return nil }
        var groups: [String?] = []
        for i in 0..<m.numberOfRanges {
            let r = m.range(at: i)
            groups.append(r.location == NSNotFound ? nil : ns.substring(with: r))
        }
        return Match(range: m.range, groups: groups)
    }

    func test(_ s: String) -> Bool {
        re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }

    func replace(_ s: String, with template: String) -> String {
        re.stringByReplacingMatches(in: s, options: [], range: NSRange(location: 0, length: (s as NSString).length), withTemplate: template)
    }
}
