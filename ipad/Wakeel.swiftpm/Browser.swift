import SwiftUI
import WebKit
import UIKit

/// One thing on a page that can be acted on.
struct PageElement {
    let id: Int
    let role: String
    let label: String
    let value: String
    let flags: String
    let sel: String

    var editable: Bool { flags.contains("e") }
    var password: Bool { flags.contains("p") }
    var disabled: Bool { flags.contains("d") }
}

/// What the agent sees of a page: what can be acted on, and an excerpt of what it says.
struct PageSnapshot {
    let url: String
    let title: String
    let elements: [PageElement]
    let text: String
    let below: Int
    let above: Int

    var host: String { URL(string: url)?.host ?? "" }

    /// Changes when the page changes in a way the agent would notice.
    var hash: String {
        url + "|" + elements.map { "\($0.role):\($0.label):\($0.value):\($0.flags)" }.joined(separator: "¦") + "|" + String(text.prefix(300))
    }

    func byId(_ id: Int) -> PageElement? { elements.first { $0.id == id } }

    /// The page as the model reads it.
    func render() -> String {
        var s = "PAGE \(title.take(80)) — \(url.take(160))\n"
        if elements.isEmpty { s += "(nothing to act on)\n" }
        for e in elements { s += "\(e.id)|\(e.role)|\(e.label)|\(e.value)|\(e.flags)\n" }
        if above > 0 { s += "↑ \(above) more above\n" }
        if below > 0 { s += "↓ \(below) more below\n" }
        if !text.isEmpty { s += "TEXT: \(text)\n" }
        return s
    }
}

/// A question the page itself asks (alert, confirm, prompt). The owner answers it; the agent never does.
struct PageDialog: Identifiable {
    let id = UUID()
    let message: String
    let isConfirm: Bool
    let answer: (Bool) -> Void
}

/// The agent's browser: one web view that the owner sees and the agent drives.
@MainActor
final class BrowserController: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    let webView: WKWebView
    @Published var url = ""
    @Published var title = ""
    @Published var loading = false
    @Published var canGoBack = false
    @Published var canGoForward = false
    @Published var dialog: PageDialog?

    /// Called with what the owner does while teaching a task.
    var onRecord: ((JSON) -> Void)?
    /// Called when a field on the page gains or loses the focus (on, isPassword): the app's keyboard follows it.
    var onFocus: ((Bool, Bool) -> Void)?
    /// Called when a page asks to open another app (tel:, mailto:, an app's own link).
    var onExternal: ((URL) -> Void)?
    /// Self-test: page dialogs are accepted without asking.
    var autoAcceptDialogs = false
    private(set) var recording = false
    /// The page used by the self-test, served under a pretend address.
    static let sandboxURL = "https://sandbox.wakeel/"
    var sandboxHTML: String?

    init(mobile: Bool) {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()          // sign-ins survive restarts, as in a normal browser
        config.allowsInlineMediaPlayback = true
        config.defaultWebpagePreferences.preferredContentMode = mobile ? .mobile : .recommended
        let controller = WKUserContentController()
        controller.addUserScript(WKUserScript(source: PageScript.source, injectionTime: .atDocumentEnd, forMainFrameOnly: true, in: .defaultClient))
        config.userContentController = controller
        webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1024, height: 768), configuration: config)
        super.init()
        controller.add(self, contentWorld: .defaultClient, name: "wk")
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }

    /// Copies what the web view knows (address, title, loading) to what the screens show.
    func refresh() {
        let u = webView.url?.absoluteString ?? ""
        if u != url { url = u }
        let t = webView.title ?? ""
        if t != title { title = t }
        if loading != webView.isLoading { loading = webView.isLoading }
        if canGoBack != webView.canGoBack { canGoBack = webView.canGoBack }
        if canGoForward != webView.canGoForward { canGoForward = webView.canGoForward }
    }

    var hasPage: Bool { webView.url != nil && webView.url?.absoluteString != "about:blank" }

    // MARK: - navigation

    /// A typed address or a few words: an address is opened, words are searched for.
    static func address(from input: String, search: String) -> String {
        let t = input.trimmed
        if t.hasPrefix("http://") || t.hasPrefix("https://") { return t }
        if !t.contains(" ") && t.contains(".") { return "https://" + t }
        return search + Commands.enc(t)
    }

    @discardableResult
    func load(_ address: String) async -> Bool {
        if address == BrowserController.sandboxURL, let html = sandboxHTML {
            webView.loadHTMLString(html, baseURL: URL(string: BrowserController.sandboxURL))
        } else {
            guard let u = URL(string: address) ?? address.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed).flatMap({ URL(string: $0) }) else { return false }
            webView.load(URLRequest(url: u))
        }
        try? await Task.sleep(nanoseconds: 300_000_000)
        await waitIdle(maxMs: 20_000)
        return webView.url != nil
    }

    func goBack() async {
        guard webView.canGoBack else { return }
        webView.goBack()
        try? await Task.sleep(nanoseconds: 300_000_000)
        await waitIdle(maxMs: 12_000)
    }

    /// Waits until the page has finished loading and has stopped changing for a moment.
    func waitIdle(maxMs: Int) async {
        let start = Date()
        var calm = 0
        while Date().timeIntervalSince(start) * 1000 < Double(maxMs) {
            if Task.isCancelled { return }
            try? await Task.sleep(nanoseconds: 150_000_000)
            if webView.isLoading { calm = 0; continue }
            let quiet = (await js("window.__wk ? __wk.quiet() : 9999") as? NSNumber)?.intValue ?? 9999
            if quiet >= 350 { calm += 1 } else { calm = 0 }
            if calm >= 2 { break }
        }
        refresh()
    }

    // MARK: - script bridge

    /// Runs script in the agent's own JavaScript world. Nil when the page gave nothing usable back.
    func js(_ code: String) async -> Any? {
        await withCheckedContinuation { (c: CheckedContinuation<Any?, Never>) in
            webView.evaluateJavaScript(code, in: nil, in: .defaultClient) { result in
                switch result {
                case .success(let v): c.resume(returning: v)
                case .failure: c.resume(returning: nil)
                }
            }
        }
    }

    /// The same, in the page's own world — only the self-test uses it, to play the part of the owner.
    func pageJS(_ code: String) async -> Any? {
        await withCheckedContinuation { (c: CheckedContinuation<Any?, Never>) in
            webView.evaluateJavaScript(code, in: nil, in: .page) { result in
                switch result {
                case .success(let v): c.resume(returning: v)
                case .failure: c.resume(returning: nil)
                }
            }
        }
    }

    private func ensureScript() async {
        if (await js("window.__wk ? 1 : 0") as? NSNumber)?.intValue != 1 { _ = await js(PageScript.source + "; 1") }
        if recording { _ = await js("__wk.rec(true); 1") }
    }

    func snapshot() async -> PageSnapshot? {
        guard hasPage else { return nil }
        await ensureScript()
        guard let raw = await js("__wk.snap()") as? String, let o = J.parse(raw) as? JSON else { return nil }
        let els = ((o["els"] as? [Any]) ?? []).compactMap { item -> PageElement? in
            guard let a = item as? [Any], a.count >= 6, let id = J.int(a[0]) else { return nil }
            return PageElement(id: id, role: J.str(a[1]) ?? "", label: J.str(a[2]) ?? "", value: J.str(a[3]) ?? "", flags: J.str(a[4]) ?? "", sel: J.str(a[5]) ?? "")
        }
        return PageSnapshot(url: J.str(o["url"]) ?? "", title: J.str(o["title"]) ?? "", elements: els, text: J.str(o["text"]) ?? "",
                            below: J.int(o["below"]) ?? 0, above: J.int(o["above"]) ?? 0)
    }

    /// Finds an element anywhere on the page by what it is called (or by a CSS hint). Returns its id.
    func find(_ label: String, editable: Bool = false) async -> Int? {
        await ensureScript()
        guard let n = (await js("__wk.find(\(J.string(label)), \(editable))") as? NSNumber)?.intValue, n > 0 else { return nil }
        return n
    }

    func info(_ id: Int) async -> PageElement? {
        guard let raw = await js("__wk.info(\(id))") as? String, let o = J.parse(raw) as? JSON else { return nil }
        return PageElement(id: id, role: J.str(o["role"]) ?? "", label: J.str(o["label"]) ?? "", value: J.str(o["value"]) ?? "", flags: J.str(o["flags"]) ?? "", sel: J.str(o["sel"]) ?? "")
    }

    func tap(_ id: Int) async -> Bool { (await js("__wk.tap(\(id))") as? String) == "ok" }

    /// Returns whether the text is now in the field, and what the field holds.
    func type(_ id: Int, _ text: String) async -> (ok: Bool, now: String, why: String) {
        guard let raw = await js("__wk.type(\(id), \(J.string(text)))") as? String, let o = J.parse(raw) as? JSON else { return (false, "", "no answer") }
        return ((o["ok"] as? Bool) ?? false, J.str(o["now"]) ?? "", J.str(o["why"]) ?? "")
    }

    func enter(_ id: Int?) async -> Bool { (await js("__wk.enter(\(id ?? 0))") as? String) == "ok" }
    func select(_ id: Int, _ option: String) async -> String { (await js("__wk.select(\(id), \(J.string(option)))") as? String) ?? "gone" }
    func scroll(down: Bool) async -> Bool { (await js("__wk.scroll(\(down)) ? 1 : 0") as? NSNumber)?.intValue == 1 }
    func pageText() async -> String { await ensureScript(); return (await js("__wk.text()") as? String) ?? "" }

    func screenshot() async -> Shot? {
        let config = WKSnapshotConfiguration()
        config.snapshotWidth = 900
        let image: UIImage? = await withCheckedContinuation { c in
            webView.takeSnapshot(with: config) { img, _ in c.resume(returning: img) }
        }
        guard let data = image?.jpegData(compressionQuality: 0.6) else { return nil }
        return Shot(base64: data.base64EncodedString())
    }

    func setRecording(_ on: Bool) async {
        recording = on
        await ensureScript()
        _ = await js("__wk.rec(\(on)); 1")
    }

    // MARK: - WKScriptMessageHandler

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? String, let o = J.parse(body) as? JSON else { return }
        if J.str(o["k"]) == "focus" {
            onFocus?((o["on"] as? Bool) ?? false, (o["secure"] as? Bool) ?? false)
        } else {
            onRecord?(o)
        }
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let u = navigationAction.request.url, let scheme = u.scheme?.lowercased() else { decisionHandler(.allow); return }
        if ["http", "https", "about", "blob", "data", "file"].contains(scheme) { decisionHandler(.allow); return }
        // tel:, mailto:, an app's own link — another app's business
        decisionHandler(.cancel)
        onExternal?(u)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { refresh() }
    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) { refresh() }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { refresh() }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { refresh() }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        refresh()
        if recording { Task { await self.ensureScript() } }
    }

    // MARK: - WKUIDelegate

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // Links that ask for a new window open in this one: the agent has a single page
        if navigationAction.targetFrame == nil { webView.load(navigationAction.request) }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        if autoAcceptDialogs { completionHandler(); return }
        dialog?.answer(false)        // a page may ask again before the first question was answered
        dialog = PageDialog(message: message, isConfirm: false) { _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        if autoAcceptDialogs { completionHandler(true); return }
        dialog?.answer(false)
        dialog = PageDialog(message: message, isConfirm: true) { ok in completionHandler(ok) }
    }

    func webView(_ webView: WKWebView, runJavaScriptTextInputPanelWithPrompt prompt: String, defaultText: String?, initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (String?) -> Void) {
        completionHandler(defaultText)
    }
}

/// Shows the agent's browser inside SwiftUI.
struct BrowserSurface: UIViewRepresentable {
    let browser: BrowserController

    func makeUIView(context: Context) -> UIView {
        let host = UIView()
        attach(to: host)
        return host
    }

    func updateUIView(_ host: UIView, context: Context) {
        if browser.webView.superview !== host { attach(to: host) }
    }

    private func attach(to host: UIView) {
        let web = browser.webView
        web.removeFromSuperview()
        web.translatesAutoresizingMaskIntoConstraints = false
        host.addSubview(web)
        NSLayoutConstraint.activate([
            web.topAnchor.constraint(equalTo: host.topAnchor), web.bottomAnchor.constraint(equalTo: host.bottomAnchor),
            web.leadingAnchor.constraint(equalTo: host.leadingAnchor), web.trailingAnchor.constraint(equalTo: host.trailingAnchor),
        ])
    }
}
