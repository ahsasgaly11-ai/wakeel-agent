import SwiftUI
import UIKit
import PDFKit

/// Everything the app is made of, created once.
@MainActor
final class AppModel: ObservableObject {
    let settings = SettingsStore()
    let history = Store<TaskRecord>("history")
    let skills = Store<Skill>("skills")
    let shortcuts = Store<ShortcutItem>("shortcuts")
    let procedures = Store<Procedure>("procedures")
    let browser: BrowserController
    let engine: Engine
    let scheduler: Scheduler
    let teacher: Teacher
    let keyboard: KeyboardHub

    /// 0 home, 1 browser, 2 files, 3 knowledge, 4 settings
    @Published var tab = 0
    /// The task being written on the home screen (other screens may prefill it).
    @Published var draft = ""
    /// Raised to put the cursor in the task field.
    @Published var focusComposer = 0
    /// Raised to put the cursor in the browser's address bar.
    @Published var focusAddress = 0

    init() {
        browser = BrowserController(mobile: settings.mobileSites)
        engine = Engine(settings: settings, browser: browser, history: history, skills: skills, shortcuts: shortcuts)
        scheduler = Scheduler(engine: engine, history: history)
        teacher = Teacher(browser: browser, skills: skills)
        keyboard = KeyboardHub(browser: browser, settings: settings)
        let scheduler = self.scheduler
        let teacher = self.teacher
        engine.schedule = { task, at in scheduler.add(task, at: at) }
        engine.isTeaching = { teacher.active }
        engine.procedures = procedures
        let engine = self.engine
        keyboard.busy = { engine.isRunning }
        browser.onExternal = { url in UIApplication.shared.open(url) }
    }

    func compose(_ text: String) {
        draft = text
        tab = 0
    }
}

/// Turns a file or a photo the owner picked into something the model can read.
enum AttachmentLoader {
    static func load(_ url: URL) -> Attachment? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let name = url.lastPathComponent
        let ext = url.pathExtension.lowercased()
        if ["png", "jpg", "jpeg", "heic", "gif", "webp", "bmp", "tiff"].contains(ext) {
            guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
            return picture(image, name: name)
        }
        if ext == "pdf" {
            guard let doc = PDFDocument(url: url) else { return nil }
            var text = ""
            for i in 0..<min(doc.pageCount, 40) {
                text += (doc.page(at: i)?.string ?? "") + "\n"
                if text.count > 20000 { break }
            }
            return Attachment(name: name, text: text.trimmed.isEmpty ? "(هذا الملف صور ممسوحة بلا نص يمكن قراءته)" : text)
        }
        guard let data = try? Data(contentsOf: url) else { return nil }
        if let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .utf16) { return Attachment(name: name, text: text) }
        return nil
    }

    static func picture(_ image: UIImage, name: String) -> Attachment? {
        let longest = max(image.size.width, image.size.height)
        let scale = longest > 1400 ? 1400 / longest : 1
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = small.jpegData(compressionQuality: 0.7) else { return nil }
        return Attachment(name: name, image: Shot(base64: jpeg.base64EncodedString()))
    }
}
