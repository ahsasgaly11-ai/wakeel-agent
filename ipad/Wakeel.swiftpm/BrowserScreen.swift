import SwiftUI

/// The agent's browser, in front of the owner: they can watch it work, take over, sign in, or teach a task.
struct BrowserScreen: View {
    @EnvironmentObject var model: AppModel
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var browser: BrowserController
    @EnvironmentObject var teacher: Teacher
    @EnvironmentObject var settings: SettingsStore
    @State private var address = ""
    @State private var askPhrase = false
    @State private var phrase = ""
    @FocusState private var editing: Bool
    private let clock = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(spacing: 0) {
            bar
            if teacher.active { teachBar }
            ZStack {
                BrowserSurface(browser: browser)
                if browser.url.isEmpty || browser.url == "about:blank" { welcome }
            }
            if engine.isRunning { MissionPanel(compact: true).padding(10).background(.bar) }
            if let note = teacher.outcome {
                HStack {
                    Text(note).font(.footnote)
                    Spacer()
                    Button("تم") { teacher.outcome = nil }
                }
                .padding(10)
                .background(Brand.teal.opacity(0.15))
            }
        }
        .onReceive(clock) { _ in browser.refresh() }
        .onChange(of: browser.url) { if !editing { address = browser.url } }
        .alert(item: $browser.dialog) { d in
            if d.isConfirm {
                return Alert(title: Text("الصفحة تسأل"), message: Text(d.message), primaryButton: .default(Text("موافق")) { d.answer(true) }, secondaryButton: .cancel(Text("إلغاء")) { d.answer(false) })
            }
            return Alert(title: Text("رسالة من الصفحة"), message: Text(d.message), dismissButton: .default(Text("حسناً")) { d.answer(true) })
        }
        .alert("علّمني مهمة", isPresented: $askPhrase) {
            TextField("الجملة التي ستقولها لاحقاً", text: $phrase)
            Button("ابدأ التسجيل") {
                let p = phrase.trimmed
                if !p.isEmpty { Task { await teacher.start(p) } }
            }
            Button("إلغاء", role: .cancel) {}
        } message: {
            Text("اكتب الجملة، ثم نفّذ المهمة بيدك في هذا المتصفح مرة واحدة واضغط «حفظ المهارة». ما تكتبه في الصفحة ويرد في جملتك يصير متغيّراً.")
        }
    }

    private var bar: some View {
        HStack(spacing: 10) {
            Button { Task { await browser.goBack() } } label: { Image(systemName: "chevron.right") }.disabled(!browser.canGoBack)
            Button { browser.webView.goForward() } label: { Image(systemName: "chevron.left") }.disabled(!browser.canGoForward)
            Button {
                if browser.loading { browser.webView.stopLoading() } else { browser.webView.reload() }
            } label: {
                Image(systemName: browser.loading ? "xmark" : "arrow.clockwise")
            }
            TextField("عنوان موقع أو كلمات للبحث", text: $address)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.webSearch)
                .environment(\.layoutDirection, .leftToRight)
                .focused($editing)
                .onSubmit { go() }
            if browser.loading { ProgressView() }
            Button { phrase = ""; askPhrase = true } label: { Label("علّمني", systemImage: "graduationcap.fill") }
                .buttonStyle(.bordered)
                .disabled(teacher.active || engine.isRunning)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var teachBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "record.circle.fill").foregroundStyle(Brand.ruby)
            VStack(alignment: .leading, spacing: 1) {
                Text("أتعلّم: «\(teacher.phrase)»").font(.subheadline.weight(.semibold)).lineLimit(1)
                Text("سُجّلت \(teacher.steps.count) خطوات — نفّذ المهمة ثم احفظ").font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button("إلغاء", role: .cancel) { Task { await teacher.stop(save: false) } }.buttonStyle(.bordered)
            Button("حفظ المهارة") { Task { await teacher.stop(save: true) } }.buttonStyle(.borderedProminent).tint(Brand.ruby)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Brand.ruby.opacity(0.12))
    }

    private var welcome: some View {
        VStack(spacing: 14) {
            Image(systemName: "safari").font(.system(size: 54)).foregroundStyle(Brand.teal)
            Text("متصفح وكيل").font(.title2.weight(.bold))
            Text("هنا يعمل الوكيل على المواقع أمامك. يمكنك التصفح بنفسك، وتسجيل الدخول إلى حساباتك مرة واحدة ليجدها جاهزة، أو تعليمه مهمة بيدك.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 460)
            HStack {
                ForEach([("Google", "https://www.google.com"), ("ويكيبيديا", "https://ar.wikipedia.org"), ("يوتيوب", "https://www.youtube.com")], id: \.0) { item in
                    Button(item.0) { address = item.1; go() }.buttonStyle(.bordered)
                }
            }
        }
        .padding(30)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }

    private func go() {
        let target = BrowserController.address(from: address, search: settings.searchURL)
        editing = false
        teacher.opened(target)
        Task { await browser.load(target) }
    }
}
