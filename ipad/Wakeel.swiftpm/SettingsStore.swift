import Foundation
import Combine
import Security

struct Preset: Identifiable {
    let id: String
    let name: String
    let base: String
    let fast: String
    let strong: String
    let keyPage: String

    static let all: [Preset] = [
        Preset(id: "anthropic", name: "Claude", base: "https://api.anthropic.com", fast: "claude-haiku-5-5", strong: "claude-sonnet-5-5", keyPage: "https://platform.claude.com/settings/keys"),
        Preset(id: "openai", name: "OpenAI", base: "https://api.openai.com/v1", fast: "", strong: "", keyPage: "https://platform.openai.com/api-keys"),
        Preset(id: "gemini", name: "Gemini", base: "https://generativelanguage.googleapis.com/v1beta/openai", fast: "", strong: "", keyPage: "https://aistudio.google.com/apikey"),
        Preset(id: "openrouter", name: "OpenRouter", base: "https://openrouter.ai/api/v1", fast: "", strong: "", keyPage: "https://openrouter.ai/keys"),
        Preset(id: "custom", name: "مخصص", base: "", fast: "", strong: "", keyPage: ""),
    ]

    static func of(_ id: String) -> Preset { all.first { $0.id == id } ?? all[0] }
}

/// The API key lives in the Keychain. If the Keychain cannot be used (it may be unavailable while the app
/// runs inside Swift Playgrounds), the key is kept in the app's preferences instead and the settings page says so.
enum Keychain {
    private static let service = "com.wakeel.ipad"

    static func get(_ account: String) -> String? {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
                                kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    @discardableResult
    static func set(_ account: String, _ value: String) -> Bool {
        let base: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(base as CFDictionary)
        if value.isEmpty { return true }
        var add = base
        add[kSecValueData as String] = Data(value.utf8)
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
}

/// Everything the owner can set. Plain values live in UserDefaults; the key lives in the Keychain.
final class SettingsStore: ObservableObject {
    private let d = UserDefaults.standard

    private func str(_ k: String, _ def: String) -> String { d.string(forKey: k) ?? def }
    private func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }
    private func num(_ k: String, _ def: Double) -> Double { d.object(forKey: k) == nil ? def : d.double(forKey: k) }
    private func put(_ k: String, _ v: Any) { d.set(v, forKey: k); objectWillChange.send() }

    // MARK: provider
    var provider: String { get { str("provider", "anthropic") } set { put("provider", newValue) } }
    var preset: Preset { Preset.of(provider) }

    var apiKey: String {
        get { Keychain.get("key_\(provider)") ?? str("key_fallback_\(provider)", "") }
        set {
            let v = newValue.trimmed
            if Keychain.set("key_\(provider)", v) { d.removeObject(forKey: "key_fallback_\(provider)") } else { d.set(v, forKey: "key_fallback_\(provider)") }
            objectWillChange.send()
        }
    }
    /// True when the key had to be kept outside the Keychain.
    var keyOutsideKeychain: Bool { !(d.string(forKey: "key_fallback_\(provider)") ?? "").isEmpty }

    var baseUrl: String { get { str("base_\(provider)", preset.base) } set { put("base_\(provider)", newValue.trimmed) } }
    var fastModel: String { get { str("fast_\(provider)", preset.fast) } set { put("fast_\(provider)", newValue.trimmed) } }
    var strongModel: String { get { str("strong_\(provider)", preset.strong) } set { put("strong_\(provider)", newValue.trimmed) } }
    var escalate: Bool { get { bool("escalate", true) } set { put("escalate", newValue) } }

    // MARK: budget (USD)
    /// Maximum model replies per task.
    var maxSteps: Int { get { Int(num("max_steps", 25)) } set { put("max_steps", Double(min(100, max(3, newValue)))) } }
    var taskBudget: Double { get { num("task_budget", 0.10) } set { put("task_budget", newValue) } }
    var dailyBudget: Double { get { num("daily_budget", 1.0) } set { put("daily_budget", newValue) } }
    // Prices per 1M tokens
    var fastIn: Double { get { num("p_fast_in", 0.10) } set { put("p_fast_in", newValue) } }
    var fastOut: Double { get { num("p_fast_out", 0.50) } set { put("p_fast_out", newValue) } }
    var strongIn: Double { get { num("p_strong_in", 2.0) } set { put("p_strong_in", newValue) } }
    var strongOut: Double { get { num("p_strong_out", 10.0) } set { put("p_strong_out", newValue) } }

    // MARK: behaviour
    var confirmSensitive: Bool { get { bool("confirm", true) } set { put("confirm", newValue) } }
    var allowScreenshots: Bool { get { bool("screenshots", true) } set { put("screenshots", newValue) } }
    var autoLearn: Bool { get { bool("auto_learn", true) } set { put("auto_learn", newValue) } }
    var mobileSites: Bool { get { bool("mobile_sites", false) } set { put("mobile_sites", newValue) } }
    /// google, duckduckgo or bing
    var searchEngine: String { get { str("search", "google") } set { put("search", newValue) } }
    var blockedSites: String { get { str("blocked_sites", Safety.defaultBlocked) } set { put("blocked_sites", newValue) } }
    var readOnlySites: String { get { str("readonly_sites", Safety.defaultReadOnly) } set { put("readonly_sites", newValue) } }
    /// teal, maroon, navy, dark or sand — the look of presentations when the task names none
    var deckTheme: String { get { str("deck_theme", "teal") } set { put("deck_theme", newValue) } }

    var searchURL: String {
        switch searchEngine {
        case "duckduckgo": return "https://duckduckgo.com/?q="
        case "bing": return "https://www.bing.com/search?q="
        default: return "https://www.google.com/search?q="
        }
    }

    var ready: Bool { !apiKey.isEmpty && !fastModel.isEmpty && !baseUrl.isEmpty }

    func cost(_ u: Usage, strong: Bool) -> Double {
        let pin = strong ? strongIn : fastIn
        let pout = strong ? strongOut : fastOut
        // Cached input is billed at a fraction; writing the cache slightly more
        return (Double(u.input) * pin + Double(u.cacheRead) * pin * 0.1 + Double(u.cacheWrite) * pin * 1.25 + Double(u.output) * pout) / 1_000_000
    }

    /// The calling code of the device's region, for local numbers in chat links.
    var dialCode: String? { Dial.code(Locale.current.region?.identifier) }
}
