import SwiftUI

@main
struct WakeelApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environmentObject(model.engine)
                .environmentObject(model.settings)
                .environmentObject(model.browser)
                .environmentObject(model.teacher)
                .environmentObject(model.scheduler)
                .environmentObject(model.keyboard)
                .task { await launch() }
                .onChange(of: phase) { if phase == .active { model.scheduler.tick() } }
        }
    }

    /// Launch switches used by the automated checks: `-selftest` runs the built-in tests, `-demo` fills the
    /// app with sample content for screenshots, `-tab N` opens a given tab.
    private func launch() async {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-selftest") {
            await SelfTest.run(model)
            return
        }
        if args.contains("-demo") { await SelfTest.demo(model) }
        if let i = args.firstIndex(of: "-tab"), i + 1 < args.count, let t = Int(args[i + 1]) { model.tab = t }
        if args.contains("-keyboard") {
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            model.focusComposer += 1
            if args.contains("-english") { model.keyboard.switchLanguage() }
        }
    }
}
