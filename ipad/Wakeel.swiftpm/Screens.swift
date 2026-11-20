import SwiftUI
import QuickLook

/// The files the agent made, newest first.
struct FilesScreen: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var engine: Engine
    @State private var files: [URL] = []
    @State private var preview: URL?

    private let starters: [(String, String, String)] = [
        ("عرض تقديمي", "rectangle.on.rectangle.angled.fill", "أنشئ عرضاً تقديمياً من 8 شرائح عن "),
        ("مستند Word", "doc.richtext.fill", "اكتب مستند Word منسّقاً عن "),
        ("جدول Excel", "tablecells.fill", "أنشئ جدول Excel لـ "),
    ]

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        ForEach(starters, id: \.0) { s in
                            Button { model.compose(s.2) } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: s.1).font(.title2)
                                    Text(s.0).font(.caption)
                                }
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                            }
                            .buttonStyle(.bordered)
                        }
                    }
                } header: {
                    Text("ملف جديد")
                } footer: {
                    Text("اكتب الموضوع في الصفحة الرئيسية، ويبني وكيل الملف كاملاً على الآيباد. من «فتح في…» ترسله إلى Word أو PowerPoint أو Excel أو تحفظه في «الملفات».")
                }
                Section("ملفاتي") {
                    if files.isEmpty { Text("لا توجد ملفات بعد.").foregroundStyle(.secondary) }
                    ForEach(files, id: \.self) { url in
                        HStack(spacing: 12) {
                            Image(systemName: FileCard.symbol(url.lastPathComponent)).font(.title2).foregroundStyle(FileCard.tint(url.lastPathComponent)).frame(width: 34)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(url.lastPathComponent).lineLimit(2)
                                Text(detail(url)).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            ShareLink(item: url) { Image(systemName: "square.and.arrow.up") }.buttonStyle(.borderless)
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { preview = url }
                        .swipeActions {
                            Button(role: .destructive) {
                                try? FileManager.default.removeItem(at: url)
                                reload()
                            } label: { Label("حذف", systemImage: "trash") }
                        }
                    }
                }
            }
            .navigationTitle("الملفات")
            .quickLookPreview($preview)
            .onAppear { reload() }
            .onChange(of: engine.last?.id) { reload() }
        }
    }

    private func reload() { files = FileBox.list() }

    private func detail(_ url: URL) -> String {
        let v = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
        let size = max(1, (v?.fileSize ?? 0) / 1024)
        let f = DateFormatter()
        f.locale = Locale(identifier: "ar")
        f.dateStyle = .medium
        f.timeStyle = .short
        return "\(size) ك.ب · " + f.string(from: v?.contentModificationDate ?? Date())
    }
}

/// What the agent has learned, and the Shortcuts it may run.
struct KnowledgeScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        KnowledgeList(skills: model.skills, shortcuts: model.shortcuts)
    }
}

private struct KnowledgeList: View {
    @ObservedObject var skills: Store<Skill>
    @ObservedObject var shortcuts: Store<ShortcutItem>
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var about = ""
    @State private var takesInput = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if skills.items.isEmpty { Text("لا توجد مهارات بعد. كل مهمة تصفّح تنجح تُحفظ هنا تلقائياً.").foregroundStyle(.secondary) }
                    ForEach(skills.items) { s in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(s.pattern).font(.subheadline.weight(.semibold))
                            HStack(spacing: 6) {
                                if s.taught { Chip(text: "علّمتَني إياها", symbol: "hand.point.up.left.fill", color: Brand.ruby) }
                                if !s.site.isEmpty { Chip(text: s.site, symbol: "globe") }
                                Chip(text: "\(s.steps.count) خطوات", symbol: "list.number")
                                Chip(text: "نجحت \(s.successes)", symbol: "checkmark", color: .green)
                            }
                            Button("جرّبها") { model.compose(s.pattern) }.font(.footnote)
                        }
                        .padding(.vertical, 2)
                    }
                    .onDelete { skills.items.remove(atOffsets: $0) }
                    Button { model.tab = 1 } label: { Label("علّمني مهمة بيدك في المتصفح", systemImage: "graduationcap.fill") }
                } header: {
                    Text("المهارات")
                } footer: {
                    Text("المهارة تُعاد دون أي استهلاك حين تكتب جملتها، مع تغيير ما بين القوسين {…}. اسحب لحذف مهارة.")
                }

                Section {
                    ForEach(shortcuts.items) { s in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(s.name).font(.subheadline.weight(.semibold))
                            if !s.about.isEmpty { Text(s.about).font(.caption).foregroundStyle(.secondary) }
                            if s.takesInput { Text("يستقبل نصاً").font(.caption2).foregroundStyle(Brand.teal) }
                        }
                    }
                    .onDelete { shortcuts.items.remove(atOffsets: $0) }
                    TextField("اسم الاختصار كما هو في تطبيق «الاختصارات»", text: $name)
                    TextField("متى يُستعمل؟ (مثال: يرسل النص إلى مجموعة المعلمين)", text: $about)
                    Toggle("يستقبل نصاً من وكيل", isOn: $takesInput)
                    Button("إضافة الاختصار") {
                        shortcuts.items.append(ShortcutItem(name: name.trimmed, about: about.trimmed, takesInput: takesInput))
                        name = ""
                        about = ""
                        takesInput = false
                    }
                    .disabled(name.trimmed.isEmpty)
                } header: {
                    Text("اختصاراتي")
                } footer: {
                    Text("الاختصارات هي طريق وكيل إلى التطبيقات الأخرى: جهّز الاختصار في تطبيق «الاختصارات» وسجّل اسمه هنا، ثم قل «شغّل اختصار كذا» أو دع وكيل يختاره حين يناسب المهمة.")
                }
            }
            .navigationTitle("المعرفة")
        }
    }
}

struct SettingsScreen: View {
    @EnvironmentObject var settings: SettingsStore
    @State private var key = ""
    @State private var testing = false
    @State private var testResult = ""

    private func bind<T>(_ path: ReferenceWritableKeyPath<SettingsStore, T>) -> Binding<T> {
        Binding(get: { settings[keyPath: path] }, set: { settings[keyPath: path] = $0 })
    }

    private func price(_ title: String, _ path: ReferenceWritableKeyPath<SettingsStore, Double>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0", value: bind(path), format: .number.precision(.fractionLength(0...3)))
                .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 110)
                .environment(\.layoutDirection, .leftToRight)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("المزوّد", selection: bind(\.provider)) {
                        ForEach(Preset.all) { Text($0.name).tag($0.id) }
                    }
                    SecureField("مفتاح API", text: $key)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onSubmit { settings.apiKey = key }
                    Button(settings.apiKey.isEmpty ? "حفظ المفتاح" : "تحديث المفتاح") {
                        settings.apiKey = key
                        key = ""
                    }
                    .disabled(key.trimmed.isEmpty)
                    if !settings.apiKey.isEmpty {
                        Label(settings.keyOutsideKeychain ? "المفتاح محفوظ في إعدادات التطبيق (تعذّر استعمال Keychain هنا)" : "المفتاح محفوظ في Keychain على هذا الجهاز",
                              systemImage: settings.keyOutsideKeychain ? "exclamationmark.triangle" : "lock.fill")
                            .font(.footnote).foregroundStyle(settings.keyOutsideKeychain ? Brand.amber : .green)
                        Button("حذف المفتاح", role: .destructive) { settings.apiKey = "" }
                    }
                    if !settings.preset.keyPage.isEmpty, let u = URL(string: settings.preset.keyPage) {
                        Link("من أين أحصل على المفتاح؟", destination: u)
                    }
                    TextField("عنوان الخادم", text: bind(\.baseUrl)).textInputAutocapitalization(.never).autocorrectionDisabled().environment(\.layoutDirection, .leftToRight)
                    TextField("النموذج السريع (لكل المهام)", text: bind(\.fastModel)).textInputAutocapitalization(.never).autocorrectionDisabled().environment(\.layoutDirection, .leftToRight)
                    TextField("النموذج الأقوى (عند التعثّر)", text: bind(\.strongModel)).textInputAutocapitalization(.never).autocorrectionDisabled().environment(\.layoutDirection, .leftToRight)
                    Toggle("الانتقال إلى النموذج الأقوى عند التعثّر", isOn: bind(\.escalate))
                    Button(testing ? "جارٍ الاختبار…" : "اختبار الاتصال") { test() }.disabled(testing || !settings.ready)
                    if !testResult.isEmpty { Text(testResult).font(.footnote).foregroundStyle(testResult.hasPrefix("✓") ? .green : Brand.ruby) }
                } header: {
                    Text("مزوّد الذكاء الاصطناعي")
                } footer: {
                    Text("المفتاح مفتاحك أنت، ولا يغادر هذا الجهاز إلا إلى المزوّد الذي اخترته.")
                }

                Section {
                    Stepper("أقصى عدد طلبات للمهمة: \(settings.maxSteps)", value: bind(\.maxSteps), in: 3...100)
                    price("ميزانية المهمة (دولار)", \.taskBudget)
                    price("الميزانية اليومية (دولار)", \.dailyBudget)
                    price("سعر إدخال السريع / مليون", \.fastIn)
                    price("سعر إخراج السريع / مليون", \.fastOut)
                    price("سعر إدخال الأقوى / مليون", \.strongIn)
                    price("سعر إخراج الأقوى / مليون", \.strongOut)
                } header: {
                    Text("الميزانية")
                } footer: {
                    Text("الأسعار لحساب التكلفة التقريبية فقط؛ عدّلها بحسب تسعيرة نموذجك.")
                }

                Section("السلوك") {
                    Toggle("تأكيد الأفعال الحساسة (إرسال، دفع، حذف)", isOn: bind(\.confirmSensitive))
                    Toggle("حفظ مهام التصفح الناجحة كمهارات", isOn: bind(\.autoLearn))
                    Toggle("إرسال لقطة للصفحة عند التعثّر", isOn: bind(\.allowScreenshots))
                    Toggle("نسخة الجوال من المواقع (بعد إعادة فتح التطبيق)", isOn: bind(\.mobileSites))
                    Picker("محرك البحث", selection: bind(\.searchEngine)) {
                        Text("Google").tag("google")
                        Text("DuckDuckGo").tag("duckduckgo")
                        Text("Bing").tag("bing")
                    }
                    Picker("ألوان العروض التقديمية", selection: bind(\.deckTheme)) {
                        Text("أخضر مزرق").tag("teal")
                        Text("عنّابي").tag("maroon")
                        Text("كحلي").tag("navy")
                        Text("داكن").tag("dark")
                        Text("رملي").tag("sand")
                    }
                }

                Section {
                    TextField("مواقع ممنوعة", text: bind(\.blockedSites), axis: .vertical).environment(\.layoutDirection, .leftToRight)
                    TextField("مواقع للقراءة فقط", text: bind(\.readOnlySites), axis: .vertical).environment(\.layoutDirection, .leftToRight)
                } header: {
                    Text("المواقع")
                } footer: {
                    Text("كلمات تفصلها فواصل. موقع يحوي عنوانه كلمة ممنوعة لا يفتحه وكيل؛ ومواقع القراءة فقط (التداول) يقرؤها ويحلّلها ولا ينفّذ فيها.")
                }

                Section("حدود يجب أن تعرفها") {
                    Label("لا يرى وكيل شاشات التطبيقات الأخرى ولا يضغط فيها — نظام الآيباد لا يسمح بذلك لأي تطبيق.", systemImage: "hand.raised")
                    Label("طريقه إلى التطبيقات الأخرى: الروابط (واتساب، اتصال، بريد) واختصاراتك.", systemImage: "link")
                    Label("المهام المجدولة تبدأ والتطبيق مفتوح؛ وهو مغلق يصلك تنبيه.", systemImage: "alarm")
                    Label("لا يكتب كلمات المرور: سجّل الدخول بنفسك في تبويب المتصفح مرة واحدة.", systemImage: "key")
                }
                .font(.footnote)
            }
            .navigationTitle("الإعدادات")
        }
    }

    private func test() {
        testing = true
        testResult = ""
        Task {
            let schema: JSON = ["type": "object", "properties": ["ok": ["type": "boolean"] as JSON] as JSON, "required": ["ok"]]
            do {
                let r = try await LLM.create(settings).callTool(model: settings.fastModel, system: "Reply by calling the tool.", user: "ping", shots: [],
                                                                tool: ToolSpec(name: "ok", description: "Confirm that you received the message.", schema: schema), maxTokens: 300, extraSystem: nil)
                testResult = r.input != nil ? "✓ يعمل. النموذج «\(settings.fastModel)» ردّ بشكل صحيح." : "وصل ردّ، لكن النموذج لم يستعمل الأداة. جرّب نموذجاً آخر."
            } catch {
                testResult = (error as? LlmError)?.message ?? error.localizedDescription
            }
            testing = false
        }
    }
}
