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
    /// The app's own keyboard, for when the system one does not come up (apps run from Swift Playgrounds).
    var inAppKeyboard: Bool { get { bool("in_app_keyboard", true) } set { put("in_app_keyboard", newValue) } }
    /// google, duckduckgo or bing
    var searchEngine: String { get { str("search", "google") } set { put("search", newValue) } }
    var blockedSites: String { get { str("blocked_sites", Safety.defaultBlocked) } set { put("blocked_sites", newValue) } }
    var readOnlySites: String { get { str("readonly_sites", Safety.defaultReadOnly) } set { put("readonly_sites", newValue) } }
    /// teal, maroon, navy, dark or sand — the look of presentations when the task names none
    var deckTheme: String { get { str("deck_theme", "teal") } set { put("deck_theme", newValue) } }

    // MARK: trading mode (a paper account inside the app; never real money)
    /// The agent runs a cycle by itself every `tradeEveryMin` minutes while the app is open.
    var tradeAuto: Bool { get { bool("trade_auto", false) } set { put("trade_auto", newValue) } }
    var tradeEveryMin: Int { get { Int(num("trade_every", 60)) } set { put("trade_every", Double(min(1440, max(15, newValue)))) } }
    var tradeSymbols: String { get { str("trade_symbols", "BTCUSDT, ETHUSDT") } set { put("trade_symbols", newValue) } }
    /// Candle size the decisions are made on: 15m, 1h or 4h.
    var tradeInterval: String { get { str("trade_interval", "1h") } set { put("trade_interval", newValue) } }
    var tradeNews: Bool { get { bool("trade_news", true) } set { put("trade_news", newValue) } }
    /// Decide with the stronger model (dearer; one request per cycle either way).
    var tradeStrong: Bool { get { bool("trade_strong", false) } set { put("trade_strong", newValue) } }
    var tradeStart: Double { get { num("trade_start", 10_000) } set { put("trade_start", min(10_000_000, max(100, newValue))) } }
    var tradeMaxPosition: Double { get { num("trade_max_pos", 2_000) } set { put("trade_max_pos", max(10, newValue)) } }
    var tradeRiskPct: Double { get { num("trade_risk", 1) } set { put("trade_risk", min(5, max(0.1, newValue))) } }
    var tradeMaxSlPct: Double { get { num("trade_max_sl", 3) } set { put("trade_max_sl", min(15, max(0.2, newValue))) } }
    var tradeMaxPerDay: Int { get { Int(num("trade_per_day", 6)) } set { put("trade_per_day", Double(min(50, max(1, newValue)))) } }
    var tradeDailyLossPct: Double { get { num("trade_day_loss", 3) } set { put("trade_day_loss", min(50, max(0.5, newValue))) } }
    var tradeMinConfidence: Int { get { Int(num("trade_min_conf", 60)) } set { put("trade_min_conf", Double(min(100, max(0, newValue)))) } }
    var tradeShort: Bool { get { bool("trade_short", true) } set { put("trade_short", newValue) } }

    var tradeLimits: Limits {
        Limits(startBalance: tradeStart, maxPositionUsd: tradeMaxPosition, riskPct: tradeRiskPct, maxSlPct: tradeMaxSlPct,
               maxTradesPerDay: tradeMaxPerDay, dailyLossPct: tradeDailyLossPct, minConfidence: tradeMinConfidence, allowShort: tradeShort)
    }

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
