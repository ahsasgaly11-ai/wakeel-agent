import Foundation
import Combine

enum TaskStatus: String, Codable { case done, failed, stopped }

enum LogKind: String, Codable { case info, think, action, local, success, warn, error }

struct LogEntry: Codable, Identifiable {
    var id = UUID()
    let kind: LogKind
    let text: String
    var at = Date()
}

struct Usage: Codable {
    var input = 0
    var output = 0
    var cacheRead = 0
    var cacheWrite = 0

    static func + (a: Usage, b: Usage) -> Usage {
        Usage(input: a.input + b.input, output: a.output + b.output, cacheRead: a.cacheRead + b.cacheRead, cacheWrite: a.cacheWrite + b.cacheWrite)
    }
}

/// One finished task, as kept in the history.
struct TaskRecord: Codable, Identifiable {
    var id = UUID()
    let task: String
    let startedAt: Date
    let endedAt: Date
    let status: TaskStatus
    /// Model requests made.
    let calls: Int
    let usage: Usage
    let cost: Double
    /// Actions carried out.
    let steps: Int
    /// Actions that cost nothing: local commands and replayed skills.
    let localSteps: Int
    let result: String
    let logs: [LogEntry]
    /// Names of the files this task created.
    var files: [String] = []
}

/// One step of a saved skill, replayed without the model.
struct SkillStep: Codable, Equatable {
    /// open, tap, type, enter, select
    var type: String
    /// What the element is called on the page; may hold {slots}.
    var label: String = ""
    /// A CSS hint for the same element, used when the label is not found.
    var sel: String = ""
    /// The address for `open`, the text for `type`, the option for `select`; may hold {slots}.
    var text: String = ""
    /// The step sends, pays or deletes: the owner confirms it…
    var confirm: Bool = false
    /// …unless they chose "always allow" for this step of this skill.
    var approved: Bool = false
}

/// A task the agent can repeat for free: learned from a run that worked, or taught by hand in the browser.
struct Skill: Codable, Identifiable {
    var id = UUID()
    /// The task with its changing parts as slots: "ابحث في المتجر عن {p1}".
    var pattern: String
    var params: [String] = []
    var steps: [SkillStep]
    var site: String = ""
    var uses = 0
    var successes = 0
    var failures = 0
    var taught = false
    var created = Date()
}

struct Scheduled: Codable, Identifiable {
    var id = UUID()
    let task: String
    let at: Date
}

/// One of the owner's Shortcuts that the agent may run.
struct ShortcutItem: Codable, Identifiable {
    var id = UUID()
    var name: String
    /// When to use it, in the owner's words — this is all the model knows about it.
    var about: String = ""
    var takesInput = false
}

/// A list kept in a JSON file in the app's own folder.
final class Store<T: Codable>: ObservableObject {
    @Published var items: [T] { didSet { save() } }
    private let url: URL

    init(_ name: String) {
        let dir = Store.folder()
        url = dir.appendingPathComponent(name + ".json")
        if let d = try? Data(contentsOf: url), let v = try? Store.decoder.decode([T].self, from: d) { items = v } else { items = [] }
    }

    private func save() {
        if let d = try? Store.encoder.encode(items) { try? d.write(to: url, options: .atomic) }
    }

    static func folder() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("Wakeel", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static var encoder: JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; return e }
    private static var decoder: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }
}

/// Where the files the agent creates are kept: the app's Documents folder, which the Files app can show.
enum FileBox {
    static var folder: URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("ملفات وكيل", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// A name that is safe as a file name and not yet taken.
    static func freshURL(name: String, ext: String) -> URL {
        var clean = name.trimmed.components(separatedBy: CharacterSet(charactersIn: "/\\:*?\"<>|\n\r\t")).joined(separator: " ")
        clean = clean.split(separator: " ").joined(separator: " ").take(60)
        if clean.isEmpty { clean = "ملف" }
        var url = folder.appendingPathComponent(clean + "." + ext)
        var n = 2
        while FileManager.default.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent("\(clean) \(n).\(ext)")
            n += 1
        }
        return url
    }

    static func list() -> [URL] {
        let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey]
        let all = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys, options: [.skipsHiddenFiles])) ?? []
        return all.sorted {
            let a = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let b = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return a > b
        }
    }
}
