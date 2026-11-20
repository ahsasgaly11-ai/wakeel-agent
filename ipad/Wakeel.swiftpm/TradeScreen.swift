import SwiftUI

/// The trading mode: a paper account the agent trades by itself, and everything the owner needs to judge it.
struct TradeScreen: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var trader: Trader
    @EnvironmentObject var settings: SettingsStore
    @State private var confirmReset = false

    private static let stampFormat: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "d/M HH:mm"
        return f
    }()

    private func stamp(_ ms: Int64) -> String { TradeScreen.stampFormat.string(from: Date(timeIntervalSince1970: Double(ms) / 1000)) }
    private func tone(_ v: Double) -> Color { v >= 0 ? .green : Brand.ruby }

    private func bind<T>(_ path: ReferenceWritableKeyPath<SettingsStore, T>) -> Binding<T> {
        Binding(get: { settings[keyPath: path] }, set: { settings[keyPath: path] = $0 })
    }

    private func number(_ title: String, _ path: ReferenceWritableKeyPath<SettingsStore, Double>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", value: bind(path), format: .number.precision(.fractionLength(0...2)))
                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 120)
                .textFieldStyle(.roundedBorder).environment(\.layoutDirection, .leftToRight)
        }
    }

    private func whole(_ title: String, _ path: ReferenceWritableKeyPath<SettingsStore, Int>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", value: bind(path), format: .number)
                .keyboardType(.numberPad).multilineTextAlignment(.trailing).frame(width: 120)
                .textFieldStyle(.roundedBorder).environment(\.layoutDirection, .leftToRight)
        }
    }

    private func tile(_ value: String, _ label: String, _ color: Color = Brand.teal) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.weight(.bold)).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(.tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func word(_ action: String) -> String {
        switch action {
        case "buy": return "شراء"
        case "sell": return "بيع"
        case "close": return "إغلاق"
        default: return "انتظار"
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    account
                    positions
                    journal
                    if !trader.book.account.closed.isEmpty { closed }
                    limits
                    setup
                }
                .padding(16)
                .frame(maxWidth: 860)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("التداول")
            .alert("تصفير الحساب التجريبي؟", isPresented: $confirmReset) {
                Button("صفّر", role: .destructive) { trader.reset() }
                Button("إلغاء", role: .cancel) {}
            } message: {
                Text("تُمحى الصفقات والسجل ويعود الرصيد إلى \(Fmt.money(settings.tradeStart)) USDT.")
            }
        }
    }

    private var account: some View {
        let eq = trader.equity
        let day = eq - trader.book.account.dayStartEquity
        let total = eq - settings.tradeStart
        let stats = trader.stats
        return Panel(accent: Brand.amber) {
            HStack {
                PanelTitle(text: "الحساب التجريبي", symbol: "chart.line.uptrend.xyaxis")
                Spacer()
                Chip(text: "لا مال حقيقي", symbol: "shield.lefthalf.filled", color: Brand.amber)
            }
            HStack(spacing: 8) {
                tile(Fmt.money(eq), "الرصيد USDT")
                tile(Fmt.signed(day), "اليوم", tone(day))
                tile(Fmt.signed(total), "منذ البداية", tone(total))
                tile(stats.trades == 0 ? "—" : "\(stats.wins) من \(stats.trades)", "صفقات رابحة")
            }
            Toggle(isOn: Binding(get: { settings.tradeAuto }, set: { trader.setAuto($0) })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("التداول التلقائي").font(.subheadline.weight(.semibold))
                    Text(settings.tradeAuto && trader.nextRun != nil
                         ? "دورة كل \(settings.tradeEveryMin) دقيقة والتطبيق مفتوح · القادمة \(Schedule.clock(trader.nextRun ?? Date()))"
                         : "يحلل ويقرر وينفّذ وحده كل \(settings.tradeEveryMin) دقيقة ما دام التطبيق مفتوحاً")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .tint(Brand.teal)
            HStack(spacing: 10) {
                Button { Task { await trader.cycle() } } label: { Label(trader.running ? "يحلل السوق…" : "دورة الآن", systemImage: "play.fill") }
                    .buttonStyle(.borderedProminent).tint(Brand.teal).disabled(trader.running)
                if trader.running { ProgressView() }
                Spacer()
                if trader.book.lastRun > 0 { Text("آخر دورة \(stamp(trader.book.lastRun))").font(.caption).foregroundStyle(.secondary) }
            }
            if !trader.book.note.isEmpty { Text(trader.book.note).font(.subheadline) }
            if !trader.book.outlook.isEmpty { Text("نظرة الوكيل: \(trader.book.outlook)").font(.caption).foregroundStyle(.secondary) }
        }
    }

    private var positions: some View {
        Panel {
            PanelTitle(text: "الصفقات المفتوحة (\(trader.book.account.positions.count))", symbol: "arrow.left.arrow.right")
            if trader.book.account.positions.isEmpty { Text("لا صفقات مفتوحة الآن.").font(.subheadline).foregroundStyle(.secondary) }
            ForEach(trader.book.account.positions) { p in
                let price = trader.book.prices[p.symbol] ?? p.entry
                let pnl = Paper.unrealized(p, price)
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(p.symbol).font(.headline)
                        Chip(text: p.side == .long ? "شراء" : "بيع", symbol: p.side == .long ? "arrow.up.right" : "arrow.down.right", color: p.side == .long ? .green : Brand.ruby)
                        Spacer()
                        Text(Fmt.signed(pnl) + " USDT").font(.headline).foregroundStyle(tone(pnl))
                    }
                    Text("الكمية \(Fmt.qty(p.qty)) · الدخول \(Fmt.price(p.entry)) · الآن \(Fmt.price(price))").font(.caption)
                    Text("وقف الخسارة \(Fmt.price(p.sl)) · الهدف \(Fmt.price(p.tp)) · فُتحت \(stamp(p.openedAt))").font(.caption).foregroundStyle(.secondary)
                    if !p.reason.isEmpty { Text(p.reason).font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        Button("إغلاق الآن") { Task { await trader.close(p.symbol) } }.buttonStyle(.bordered)
                        Button("أرسلها إلى موقع تجريبي") {
                            model.compose("افتح موقع التداول التجريبي ونفّذ هناك أمر \(p.side == .long ? "شراء" : "بيع") \(p.symbol) بالكمية \(Fmt.qty(p.qty)) مع وقف خسارة \(Fmt.price(p.sl)) وهدف \(Fmt.price(p.tp))، وتأكد قبل التنفيذ أن الصفحة حساب تجريبي")
                        }
                        .buttonStyle(.borderless).font(.footnote)
                    }
                }
                .padding(.vertical, 4)
                Divider()
            }
        }
    }

    private var journal: some View {
        Panel {
            PanelTitle(text: "قرارات الوكيل", symbol: "list.bullet.rectangle", color: Brand.amber)
            if trader.book.journal.isEmpty {
                Text("لم يقرر الوكيل شيئاً بعد. اضغط «دورة الآن» أو شغّل التداول التلقائي.").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(trader.book.journal.prefix(30)) { j in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(j.symbol).font(.subheadline.weight(.bold))
                        Chip(text: word(j.action) + (j.action == "buy" || j.action == "sell" ? " · ثقة \(j.confidence)" : ""), color: j.changed ? .green : .secondary)
                        Spacer()
                        Text(stamp(j.time)).font(.caption2).foregroundStyle(.secondary)
                    }
                    if !j.reason.isEmpty { Text(j.reason).font(.caption) }
                    Text(j.outcome).font(.caption).foregroundStyle(.secondary)
                }
                .padding(.vertical, 3)
                Divider()
            }
        }
    }

    private var closed: some View {
        let stats = trader.stats
        return Panel {
            PanelTitle(text: "الصفقات المغلقة (\(stats.trades))", symbol: "checkmark.rectangle.stack")
            Text("المجموع \(Fmt.signed(stats.pnl)) USDT · أفضل صفقة \(Fmt.signed(stats.best)) · أسوأ صفقة \(Fmt.signed(stats.worst))" + (stats.profitFactor.map { " · معامل الربح \(Fmt.two($0))" } ?? ""))
                .font(.caption).foregroundStyle(.secondary)
            ForEach(trader.book.account.closed.prefix(20)) { t in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(t.symbol) · \(t.side == .long ? "شراء" : "بيع")").font(.subheadline.weight(.medium))
                        Text("\(Fmt.price(t.entry)) ← \(Fmt.price(t.exit)) · \(t.why == "sl" ? "وقف الخسارة" : t.why == "tp" ? "الهدف" : t.why == "manual" ? "بيدك" : "قرار الوكيل") · \(stamp(t.closedAt))")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(Fmt.signed(t.pnl)).font(.headline).foregroundStyle(tone(t.pnl))
                }
                Divider()
            }
        }
    }

    private var limits: some View {
        Panel(accent: Brand.ruby) {
            PanelTitle(text: "الضوابط", symbol: "shield.fill", color: Brand.ruby)
            Text("هذه الحدود يطبّقها التطبيق بعد قرار النموذج، فلا يستطيع تجاوزها: الحجم يُحسب من وقف الخسارة، والوقف إلزامي.")
                .font(.caption).foregroundStyle(.secondary)
            number("أقصى حجم للصفقة (USDT)", \.tradeMaxPosition)
            number("مخاطرة الصفقة الواحدة (%)", \.tradeRiskPct)
            number("أبعد وقف خسارة (%)", \.tradeMaxSlPct)
            number("حد خسارة اليوم (%)", \.tradeDailyLossPct)
            whole("عدد الصفقات في اليوم", \.tradeMaxPerDay)
            whole("أدنى ثقة لفتح صفقة (0–100)", \.tradeMinConfidence)
            Toggle("البيع على المكشوف (كما في العقود الآجلة)", isOn: bind(\.tradeShort)).tint(Brand.teal)
        }
    }

    private var setup: some View {
        Panel {
            PanelTitle(text: "ما يتداوله", symbol: "slider.horizontal.3")
            TextField("الرموز، مثل BTCUSDT, ETHUSDT", text: bind(\.tradeSymbols))
                .textFieldStyle(.roundedBorder).textInputAutocapitalization(.characters).autocorrectionDisabled()
                .environment(\.layoutDirection, .leftToRight)
            Picker("حجم الشمعة", selection: bind(\.tradeInterval)) {
                Text("15 دقيقة").tag("15m")
                Text("ساعة").tag("1h")
                Text("4 ساعات").tag("4h")
            }
            .pickerStyle(.segmented)
            whole("دورة كل (دقيقة، 15 على الأقل)", \.tradeEveryMin)
            number("رصيد البداية (USDT)", \.tradeStart)
            Toggle("عناوين الأخبار خلفيةً للقرار", isOn: bind(\.tradeNews)).tint(Brand.teal)
            Toggle("القرار بالنموذج الأقوى (أدق وأغلى)", isOn: bind(\.tradeStrong)).tint(Brand.teal)
            Button("تصفير الحساب التجريبي", role: .destructive) { confirmReset = true }.buttonStyle(.bordered)
            Text("الأسعار من بيانات السوق العامة (بلا حساب ولا مفتاح). لا يملك هذا الوضع أي طريق إلى حساب تداول حقيقي. نتائج الحساب التجريبي لا تضمن شيئاً في السوق الحقيقي، وهذه ليست نصيحة مالية. الدورة التلقائية تعمل والتطبيق مفتوح أمامك فقط: هذا قيد من iPadOS.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }
}
