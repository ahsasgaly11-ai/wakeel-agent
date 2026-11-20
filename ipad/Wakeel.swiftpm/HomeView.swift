import SwiftUI
import PhotosUI
import QuickLook
import UniformTypeIdentifiers

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var keyboard: KeyboardHub

    var body: some View {
        VStack(spacing: 0) {
            tabs
            if keyboard.visible { KeyboardView().transition(.move(edge: .bottom)) }
        }
        // The app's keyboard is part of this layout: whatever room the system keeps for its own is not wanted
        .ignoresSafeArea(model.settings.inAppKeyboard ? .keyboard : [], edges: .bottom)
        .environment(\.layoutDirection, .rightToLeft)
        .onChange(of: engine.showBrowser) { model.tab = 1 }
    }

    private var tabs: some View {
        TabView(selection: $model.tab) {
            HomeView().tabItem { Label("الرئيسية", systemImage: "sparkles") }.tag(0)
            BrowserScreen().tabItem { Label("المتصفح", systemImage: "safari") }.tag(1)
            FilesScreen().tabItem { Label("الملفات", systemImage: "doc.on.doc") }.tag(2)
            KnowledgeScreen().tabItem { Label("المعرفة", systemImage: "graduationcap") }.tag(3)
            SettingsScreen().tabItem { Label("الإعدادات", systemImage: "gearshape") }.tag(4)
        }
        .tint(Brand.teal)
    }
}

/// What the agent is doing now, with the stop button — and the question it is waiting on, if any.
struct MissionPanel: View {
    @EnvironmentObject var engine: Engine
    var compact = false
    @State private var answer = ""

    var body: some View {
        switch engine.state {
        case .idle:
            EmptyView()
        case .running(let text):
            Panel(accent: Brand.teal) {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(text).font(.subheadline).lineLimit(2)
                    Spacer()
                    Button(role: .destructive) { engine.stop() } label: { Label("إيقاف", systemImage: "stop.fill") }
                        .buttonStyle(.bordered)
                }
                if !compact {
                    ForEach(engine.live.suffix(6)) { e in
                        Label(e.text, systemImage: Brand.icon(e.kind)).font(.caption).foregroundStyle(Brand.color(e.kind)).lineLimit(2)
                    }
                }
            }
        case .confirm(let q):
            Panel(accent: Brand.amber) {
                PanelTitle(text: q.title, symbol: "exclamationmark.shield.fill", color: Brand.amber)
                Text(q.text).fixedSize(horizontal: false, vertical: true)
                HStack {
                    Button { engine.answer(true) } label: { Text(q.yes).frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).tint(Brand.teal)
                    Button { engine.answer(false) } label: { Text(q.no).frame(maxWidth: .infinity) }.buttonStyle(.bordered).tint(Brand.ruby)
                }
                if q.canAlways {
                    Button("سماح دائماً لهذه الخطوة من المهارة") { engine.answer(true, always: true) }.font(.footnote)
                }
            }
        case .ask(let question):
            Panel(accent: Brand.amber) {
                PanelTitle(text: "سؤال من وكيل", symbol: "questionmark.bubble.fill", color: Brand.amber)
                Text(question).fixedSize(horizontal: false, vertical: true)
                HStack {
                    TextField("جوابك", text: $answer, axis: .vertical).textFieldStyle(.roundedBorder).onSubmit { send() }
                    Button("إرسال") { send() }.buttonStyle(.borderedProminent).tint(Brand.teal).disabled(answer.trimmed.isEmpty)
                }
                Button("إيقاف المهمة", role: .destructive) { engine.stop() }.font(.footnote)
            }
        }
    }

    private func send() {
        let a = answer.trimmed
        if a.isEmpty { return }
        answer = ""
        engine.reply(a)
    }
}

/// A file the agent made: a card that previews it and shares it.
struct FileCard: View {
    let name: String
    @State private var preview: URL?

    private var url: URL { FileBox.folder.appendingPathComponent(name) }

    static func symbol(_ name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "docx": return "doc.richtext.fill"
        case "pptx": return "rectangle.on.rectangle.angled.fill"
        case "xlsx": return "tablecells.fill"
        default: return "doc.fill"
        }
    }

    static func tint(_ name: String) -> Color {
        switch (name as NSString).pathExtension.lowercased() {
        case "docx": return .blue
        case "pptx": return .orange
        case "xlsx": return .green
        default: return .secondary
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: FileCard.symbol(name)).font(.title2).foregroundStyle(FileCard.tint(name)).frame(width: 34)
            Text(name).font(.subheadline.weight(.medium)).lineLimit(2)
            Spacer()
            Button { preview = url } label: { Label("معاينة", systemImage: "eye") }.buttonStyle(.bordered)
            ShareLink(item: url) { Label("فتح في…", systemImage: "square.and.arrow.up") }.buttonStyle(.borderedProminent).tint(Brand.teal)
        }
        .labelStyle(.titleAndIcon)
        .padding(10)
        .background(Color(.tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .quickLookPreview($preview)
    }
}

struct RecordCard: View {
    let record: TaskRecord
    var full = false

    var body: some View {
        Panel(accent: record.status.color) {
            HStack {
                PanelTitle(text: record.status.title, symbol: record.status.symbol, color: record.status.color)
                Spacer()
                Text(record.endedAt, style: .time).font(.caption).foregroundStyle(.secondary)
            }
            Text(record.task).font(.footnote).foregroundStyle(.secondary).lineLimit(full ? nil : 2)
            ResultText(text: full ? record.result : record.result.take(1800))
            ForEach(record.files, id: \.self) { FileCard(name: $0) }
            HStack(spacing: 8) {
                StatTile(value: money(record.cost), label: "التكلفة")
                StatTile(value: "\(record.calls)", label: "طلبات")
                StatTile(value: "\(record.localSteps)", label: "مجانية")
                StatTile(value: "\(max(1, Int(record.endedAt.timeIntervalSince(record.startedAt))))ث", label: "المدة")
            }
            if full {
                Divider()
                Text("سجل الخطوات").font(.subheadline.weight(.semibold))
                ForEach(record.logs) { e in
                    Label(e.text, systemImage: Brand.icon(e.kind)).font(.caption).foregroundStyle(Brand.color(e.kind)).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var settings: SettingsStore
    @EnvironmentObject var scheduler: Scheduler
    @State private var attachments: [Attachment] = []
    @State private var picking = false
    @State private var photo: PhotosPickerItem?
    @State private var opened: TaskRecord?
    @FocusState private var typing: Bool

    private let ideas: [(String, String, String)] = [
        ("عرض تقديمي", "rectangle.on.rectangle.angled", "أنشئ عرضاً تقديمياً من 8 شرائح عن "),
        ("مستند Word", "doc.richtext", "اكتب مستند Word منسّقاً عن "),
        ("جدول Excel", "tablecells", "أنشئ جدول Excel لـ "),
        ("بحث وتلخيص", "magnifyingglass", "ابحث في الإنترنت عن  ولخّص أهم ما وجدت"),
        ("واتساب", "message", "أرسل واتساب إلى : "),
        ("لوقت لاحق", "clock", "بعد 10 دقائق "),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    if !settings.ready {
                        Panel(accent: Brand.amber) {
                            PanelTitle(text: "أضف مفتاح الذكاء الاصطناعي", symbol: "key.fill", color: Brand.amber)
                            Text("المهام التي تحتاج تفكيراً (التصفح، إنشاء الملفات، التحليل) تعمل بمفتاح API خاص بك. الأوامر المحلية والجدولة تعمل دونه.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Button("فتح الإعدادات") { model.tab = 4 }.buttonStyle(.borderedProminent).tint(Brand.teal)
                        }
                    }
                    composer
                    MissionPanel()
                    ScheduledPanel(store: scheduler.store)
                    if let last = engine.last, !engine.isRunning {
                        RecordCard(record: last).onTapGesture { opened = last }
                    }
                    HistoryPanel(history: model.history, opened: $opened)
                }
                .padding(16)
                .frame(maxWidth: 860)
                .frame(maxWidth: .infinity)
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("وكيل")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { TodayChip(history: model.history) }
            }
            .sheet(item: $opened) { r in
                NavigationStack {
                    ScrollView { RecordCard(record: r, full: true).padding(16) }
                        .background(Color(.systemGroupedBackground))
                        .navigationTitle("تفاصيل المهمة")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("تم") { opened = nil } } }
                }
                .environment(\.layoutDirection, .rightToLeft)
            }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.pdf, .plainText, .image, .commaSeparatedText, .json, .html], allowsMultipleSelection: true) { result in
                if case .success(let urls) = result { attachments += urls.compactMap { AttachmentLoader.load($0) } }
            }
            .onChange(of: model.focusComposer) {
                typing = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { FocusHelp.ensureEditor() }
            }
            .onChange(of: photo) {
                guard let item = photo else { return }
                photo = nil
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data),
                       let a = AttachmentLoader.picture(image, name: "صورة \(attachments.count + 1)") { attachments.append(a) }
                }
            }
        }
    }

    private var composer: some View {
        Panel {
            ZStack(alignment: .topLeading) {
                if model.draft.isEmpty {
                    Text("ماذا تريد أن أفعل؟ مثال: «أنشئ عرضاً عن دورة الماء للصف الثالث» أو «بعد 10 دقائق ذكّرني…»")
                        .foregroundStyle(.tertiary).padding(.top, 8).padding(.horizontal, 5)
                }
                TextEditor(text: $model.draft).focused($typing).frame(minHeight: 96, maxHeight: 220).scrollContentBackground(.hidden)
            }
            if !attachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(attachments) { a in
                            Button { attachments.removeAll { $0.id == a.id } } label: {
                                Chip(text: a.name, symbol: a.image != nil ? "photo" : "doc.text")
                            }
                        }
                    }
                }
            }
            HStack {
                Menu {
                    Button { picking = true } label: { Label("ملف (PDF أو نص)", systemImage: "doc") }
                    PhotosPicker(selection: $photo, matching: .images) { Label("صورة من الصور", systemImage: "photo") }
                } label: { Label("إرفاق", systemImage: "paperclip") }
                .buttonStyle(.bordered)
                Spacer()
                Button { run() } label: { Label("نفّذ", systemImage: "paperplane.fill").padding(.horizontal, 8) }
                    .buttonStyle(.borderedProminent).tint(Brand.teal)
                    .disabled(model.draft.trimmed.isEmpty || engine.isRunning)
                    .keyboardShortcut(.return, modifiers: .command)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(ideas, id: \.0) { idea in
                        Button { model.draft = idea.2; typing = true } label: { Chip(text: idea.0, symbol: idea.1) }
                    }
                }
            }
        }
    }

    private func run() {
        let text = model.draft.trimmed
        if text.isEmpty { return }
        typing = false
        if engine.start(text, attachments: attachments) {
            model.draft = ""
            attachments = []
        }
    }
}

private struct TodayChip: View {
    @ObservedObject var history: Store<TaskRecord>

    var body: some View {
        let cost = history.items.filter { Calendar.current.isDateInToday($0.startedAt) }.reduce(0) { $0 + $1.cost }
        Chip(text: "اليوم \(money(cost))", symbol: "creditcard")
    }
}

private struct ScheduledPanel: View {
    @ObservedObject var store: Store<Scheduled>
    @EnvironmentObject var scheduler: Scheduler

    var body: some View {
        if !store.items.isEmpty {
            Panel(accent: Brand.teal) {
                PanelTitle(text: "مهام مجدولة", symbol: "alarm.fill")
                ForEach(store.items) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.task).font(.subheadline).lineLimit(2)
                            Text(Schedule.describe(item.at)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button(role: .destructive) { scheduler.cancel(item.id) } label: { Image(systemName: "xmark.circle.fill") }
                            .accessibilityLabel("إلغاء المهمة المجدولة")
                    }
                }
                Text("تبدأ في موعدها ما دام التطبيق مفتوحاً. إن كان مغلقاً يصلك تنبيه، وتبدأ حين تفتحه.").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}

private struct HistoryPanel: View {
    @ObservedObject var history: Store<TaskRecord>
    @Binding var opened: TaskRecord?

    var body: some View {
        Panel {
            PanelTitle(text: "السجل", symbol: "clock.arrow.circlepath")
            if history.items.isEmpty {
                Text("لا توجد مهام بعد.").font(.subheadline).foregroundStyle(.secondary)
            }
            ForEach(history.items.prefix(30)) { r in
                Button { opened = r } label: {
                    HStack(spacing: 10) {
                        Image(systemName: r.status.symbol).foregroundStyle(r.status.color)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(r.task).font(.subheadline).lineLimit(1).foregroundStyle(.primary)
                            Text(r.result).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        Text(money(r.cost)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
            }
            if !history.items.isEmpty {
                Button("مسح السجل", role: .destructive) { history.items = [] }.font(.footnote)
            }
        }
    }
}
