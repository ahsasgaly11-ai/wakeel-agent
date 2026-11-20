import SwiftUI

/// Where prices and headlines come from. The self-test puts a scripted market here.
protocol MarketSource {
    func candles(_ symbol: String, _ interval: String, _ limit: Int) async throws -> [Candle]
    func headlines(_ query: String) async throws -> [String]
}

enum Market {
    static var override: MarketSource?
}

/// Public market data: no account, no key, nothing that can place an order.
struct LiveMarket: MarketSource {
    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 20
        c.timeoutIntervalForResource = 40
        return URLSession(configuration: c)
    }()

    private func get(_ address: String) async throws -> String {
        guard let url = URL(string: address) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.setValue("Wakeel", forHTTPHeaderField: "User-Agent")
        let (data, response) = try await LiveMarket.session.data(for: req)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { throw URLError(.badServerResponse) }
        return String(data: data, encoding: .utf8) ?? ""
    }

    func candles(_ symbol: String, _ interval: String, _ limit: Int) async throws -> [Candle] {
        let sources = [
            "https://data-api.binance.vision/api/v3/klines?symbol=\(symbol)&interval=\(interval)&limit=\(limit)",
            "https://api.mexc.com/api/v3/klines?symbol=\(symbol)&interval=\(interval == "1h" ? "60m" : interval)&limit=\(limit)",
        ]
        var last: Error = URLError(.cannotLoadFromNetwork)
        for address in sources {
            do {
                let c = MarketText.parseKlines(try await get(address))
                if c.count >= 30 { return c }
            } catch {
                last = error
            }
        }
        throw last
    }

    func headlines(_ query: String) async throws -> [String] {
        let q = query.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "crypto"
        return MarketText.parseRss(try await get("https://news.google.com/rss/search?q=\(q)&hl=en-US&gl=US&ceid=US:en"))
    }
}

struct JournalEntry: Codable, Identifiable {
    var id = UUID()
    let time: Int64
    let symbol: String
    let action: String
    let confidence: Int
    let reason: String
    let outcome: String
    let changed: Bool
}

/// Everything the trading mode keeps between launches.
struct TradeBook: Codable {
    var account: Account
    var journal: [JournalEntry] = []
    var prices: [String: Double] = [:]
    var lastRun: Int64 = 0
    var note = ""
    var outlook = ""
    /// What the model cost today for trading decisions.
    var spendDay = ""
    var spend = 0.0
}

/// The trading mode: every cycle reads the market, lets the model decide, and carries the decision out in a
/// paper account kept on this iPad. It has no way to reach an exchange account — by construction, not by a setting.
@MainActor
final class Trader: ObservableObject {
    @Published private(set) var book: TradeBook
    @Published private(set) var running = false
    @Published private(set) var nextRun: Date?
    private let settings: SettingsStore
    private let url: URL
    private var timer: Timer?

    init(settings: SettingsStore) {
        self.settings = settings
        url = Store<Skill>.folder().appendingPathComponent("trade.json")
        if let d = try? Data(contentsOf: url), let b = try? JSONDecoder().decode(TradeBook.self, from: d) {
            book = b
        } else {
            book = TradeBook(account: Account(settings.tradeStart))
        }
        if settings.tradeAuto { plan() }
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    var equity: Double { Paper.equity(book.account, book.prices) }
    var stats: TradeStats { Paper.stats(book.account) }
    var symbols: [String] {
        let s = MarketText.symbols(settings.tradeSymbols)
        return s.isEmpty ? ["BTCUSDT"] : s
    }

    private func save() {
        if let d = try? JSONEncoder().encode(book) { try? d.write(to: url, options: .atomic) }
    }

    private static func ms() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static func today() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }

    private func journal(_ now: Int64, _ symbol: String, _ action: String, _ confidence: Int, _ reason: String, _ out: Applied) {
        book.journal.insert(JournalEntry(time: now, symbol: symbol, action: action, confidence: confidence, reason: String(reason.prefix(300)), outcome: out.text, changed: out.changed), at: 0)
        if book.journal.count > 200 { book.journal.removeLast(book.journal.count - 200) }
    }

    // MARK: - the cycle

    /// One full cycle: stops and targets, analysis, decision, execution. Returns a line for the owner.
    @discardableResult
    func cycle() async -> String {
        if running { return "دورة أخرى تعمل الآن." }
        running = true
        var line: String
        do {
            line = try await runCycle()
        } catch let e as LlmError {
            line = "تعذّرت الدورة: \(e.message)"
        } catch {
            line = "تعذّرت الدورة: \(error.localizedDescription)"
        }
        book.lastRun = Trader.ms()
        book.note = line
        save()
        running = false
        if settings.tradeAuto { plan() }
        return line
    }

    private func runCycle() async throws -> String {
        let now = Trader.ms()
        let lim = settings.tradeLimits
        let interval = settings.tradeInterval
        let higher = Analysis.higherOf(interval)
        let source: MarketSource = Market.override ?? LiveMarket()
        var briefs: [Brief] = []
        var candles: [String: [Candle]] = [:]
        // Positions on symbols the owner has since removed from the list are still looked after
        var wanted = symbols
        for p in book.account.positions where !wanted.contains(p.symbol) { wanted.append(p.symbol) }
        for s in wanted {
            guard let c = try? await source.candles(s, interval, 150), c.count >= 30 else { continue }
            let h = (try? await source.candles(s, higher, 80)) ?? []
            candles[s] = c
            book.prices[s] = c[c.count - 1].close
            if let b = Analysis.brief(s, interval, c, higher, h) { briefs.append(b) }
        }
        if briefs.isEmpty { return "تعذّر جلب أسعار السوق. تحقق من الإنترنت ومن رموز العملات." }

        // Stops and targets the market reached since the last look
        var swept = 0
        for p in book.account.positions {
            guard let c = candles[p.symbol], let t = Paper.sweep(&book.account, lim, p.symbol, c, now: now) else { continue }
            swept += 1
            journal(now, t.symbol, "close", 100, t.why == "sl" ? "بلغ السعر وقف الخسارة" : "بلغ السعر الهدف", Applied(text: Paper.describe(t), changed: true))
        }
        Paper.rollDay(&book.account, Trader.today(), equity)
        if book.spendDay != Trader.today() {
            book.spendDay = Trader.today()
            book.spend = 0
        }

        guard settings.ready || LLM.override != nil else {
            return "أضف مفتاح API واسم النموذج من الإعدادات ليقرر الوكيل. فُحصت أوامر الوقف والهدف فقط."
        }
        if book.spend >= settings.dailyBudget { return "بلغتَ حد الإنفاق اليومي على النموذج — لا قرارات جديدة اليوم. فُحصت أوامر الوقف والهدف." }

        var news: [String] = []
        if settings.tradeNews {
            news = (try? await source.headlines("crypto market " + briefs.map { $0.symbol.replacingOccurrences(of: "USDT", with: "") }.joined(separator: " "))) ?? []
        }
        let strong = settings.tradeStrong && !settings.strongModel.isEmpty
        let model = strong ? settings.strongModel : settings.fastModel
        let user = TradeText.user(now: Engine.nowText(), book.account, lim, book.prices, briefs, news, nowMs: now)
        let tool = ToolSpec(name: "trade", description: "Give this cycle's decision for every symbol.", schema: (J.parse(TradeText.toolSchema) as? JSON) ?? [:])
        let res = try await LLM.create(settings).callTool(model: model, system: TradeText.system, user: user, shots: [], tool: tool, maxTokens: 900, extraSystem: nil)
        book.spend += settings.cost(res.usage, strong: strong)
        guard let o = res.input else { return "لم يُعطِ النموذج قراراً هذه الدورة." }
        book.outlook = String((J.str(o["outlook"]) ?? "").prefix(300))
        var acted = 0
        var seen: [String] = []
        for item in (o["decisions"] as? [Any]) ?? [] {
            guard let d = item as? JSON, let symbol = J.str(d["symbol"])?.uppercased(), let b = briefs.first(where: { $0.symbol == symbol }), !seen.contains(symbol) else { continue }
            seen.append(symbol)
            func num(_ k: String) -> Double? {
                guard let n = d[k] as? NSNumber else { return nil }
                let v = n.doubleValue
                return v.isFinite && v > 0 ? v : nil
            }
            let call = Call(symbol: symbol, action: J.str(d["action"]) ?? "hold", sl: num("sl"), tp: num("tp"),
                            confidence: min(100, max(0, J.int(d["confidence"]) ?? 0)), reason: J.str(d["reason"]) ?? "")
            let out = Paper.apply(&book.account, lim, call, price: b.price, atr: b.atr, now: now, equity: equity)
            if out.changed { acted += 1 }
            journal(now, symbol, call.action.lowercased(), call.confidence, call.reason, out)
        }
        let eq = equity
        return (acted + swept == 0 ? "لا صفقات هذه الدورة" : "\(acted + swept) إجراء هذه الدورة") + " · الرصيد \(Fmt.money(eq)) USDT · اليوم \(Fmt.signed(eq - book.account.dayStartEquity))"
    }

    /// The owner closes a position by hand, at the latest price that can be had.
    func close(_ symbol: String) async {
        if running { return }
        let source: MarketSource = Market.override ?? LiveMarket()
        var price = book.prices[symbol]
        if let c = try? await source.candles(symbol, "15m", 30), let last = c.last { price = last.close }
        guard let px = price ?? book.account.positions.first(where: { $0.symbol == symbol })?.entry else { return }
        book.prices[symbol] = px
        let now = Trader.ms()
        if let t = Paper.close(&book.account, settings.tradeLimits, symbol, px, now: now, why: "manual") {
            journal(now, symbol, "close", 100, "أغلقتَها بيدك", Applied(text: Paper.describe(t), changed: true))
            save()
        }
    }

    /// A fresh paper account with the starting balance from the settings.
    func reset() {
        book = TradeBook(account: Account(settings.tradeStart))
        save()
    }

    // MARK: - the automatic cycle (while the app is open)

    func setAuto(_ on: Bool) {
        settings.tradeAuto = on
        if on {
            nextRun = Date()
        } else {
            nextRun = nil
        }
    }

    private func plan() {
        let every = TimeInterval(settings.tradeEveryMin * 60)
        let last = book.lastRun > 0 ? Date(timeIntervalSince1970: Double(book.lastRun) / 1000) : Date()
        nextRun = max(last.addingTimeInterval(every), Date().addingTimeInterval(5))
    }

    /// Starts the cycle that is due. iPadOS gives an app no time in the background: an overdue cycle runs
    /// when the app is opened again.
    func tick() {
        guard settings.tradeAuto, !running, let due = nextRun, Date() >= due else { return }
        Task { await cycle() }
    }
}
