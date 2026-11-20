import SwiftUI
import UIKit

extension UIResponder {
    private static weak var wkFound: UIResponder?

    /// The view that currently receives typing, whatever screen it is on.
    static func wkFirst() -> UIResponder? {
        wkFound = nil
        UIApplication.shared.sendAction(#selector(UIResponder.wkReport(_:)), to: nil, from: nil, for: nil)
        return wkFound
    }

    @objc private func wkReport(_ sender: Any?) { UIResponder.wkFound = self }
}

/// What a field is given in place of the system keyboard: nothing, with no height and no touches.
final class NoSystemKeyboard: UIView {
    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: 0) }
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool { false }

    static func make() -> NoSystemKeyboard {
        let v = NoSystemKeyboard(frame: .zero)
        v.autoresizingMask = []
        v.isUserInteractionEnabled = false
        v.backgroundColor = .clear
        return v
    }
}

enum FocusHelp {
    static var window: UIWindow? {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.first { $0.isKeyWindow }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.first
    }

    private static func textView(in view: UIView) -> UITextView? {
        if let t = view as? UITextView, t.isEditable, !t.isHidden, t.window != nil { return t }
        for sub in view.subviews { if let t = textView(in: sub) { return t } }
        return nil
    }

    /// Puts the cursor in the screen's multi-line field when the usual request for the focus was not honoured.
    static func ensureEditor() {
        if UIResponder.wkFirst() is UITextView { return }
        if let w = window, let t = textView(in: w) { t.becomeFirstResponder() }
    }

    private static func textField(in view: UIView) -> UITextField? {
        if let t = view as? UITextField, t.isEnabled, !t.isHidden, t.window != nil { return t }
        for sub in view.subviews { if let t = textField(in: sub) { return t } }
        return nil
    }

    /// The same for the screen's first one-line field.
    static func ensureField() {
        if UIResponder.wkFirst() is UITextField { return }
        if let w = window, let t = textField(in: w) { t.becomeFirstResponder() }
    }

    /// For the self-test's report: what holds the focus now.
    static var report: String {
        let r = UIResponder.wkFirst()
        let state = UIApplication.shared.applicationState
        let input = (r as? UITextView)?.inputView ?? (r as? UITextField)?.inputView
        var host = ""
        var v = input?.superview
        while let s = v { host += " \(type(of: s))=\(Int(s.frame.height))"; v = s.superview }
        return "focus=\(r.map { String(describing: type(of: $0)) } ?? "none") app=\(state == .active ? "active" : state == .inactive ? "inactive" : "background") key=\(window?.isKeyWindow ?? false) screen=\(Int(window?.bounds.width ?? 0))x\(Int(window?.bounds.height ?? 0)) insets=\(Int(window?.safeAreaInsets.bottom ?? 0)) input=\(input.map { "\(Int($0.frame.width))x\(Int($0.frame.height))" } ?? "none") host:\(host)"
    }
}

/// The app's own keyboard.
///
/// It exists because the system keyboard may not appear at all for apps run from Swift Playgrounds.
/// It types into whatever field has the focus: the app's own fields through the same calls the system
/// keyboard makes, and fields on web pages through the page script.
@MainActor
final class KeyboardHub: ObservableObject {
    enum Layer { case arabic, english, symbols }

    @Published private(set) var visible = false
    @Published var layer: Layer = .arabic
    @Published var shift = false
    @Published var caps = false
    /// The layer to come back to from the symbols.
    private var letters: Layer = .arabic
    private var nativeActive = false
    private var webActive = false
    private var generation = 0
    private let browser: BrowserController
    private let settings: SettingsStore
    /// True while the agent itself is working in the browser: its typing needs no keyboard.
    var busy: () -> Bool = { false }
    private var observers: [NSObjectProtocol] = []
    /// Where the app's keyboard is on the screen, and where the system says its own is: for the self-test.
    var frame: CGRect = .zero
    private(set) var systemFrame: CGRect = .zero

    init(browser: BrowserController, settings: SettingsStore) {
        self.browser = browser
        self.settings = settings
        let center = NotificationCenter.default
        for name in [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] note in
                let field = note.object as? UIView
                Task { @MainActor in self?.began(field) }
            })
        }
        for name in [UITextField.textDidEndEditingNotification, UITextView.textDidEndEditingNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.ended() }
            })
        }
        observers.append(center.addObserver(forName: UIResponder.keyboardDidChangeFrameNotification, object: nil, queue: .main) { [weak self] note in
            let f = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue ?? .zero
            Task { @MainActor in self?.systemFrame = f }
        })
        browser.onFocus = { [weak self] on, secure in self?.webFocus(on, secure: secure) }
    }

    private func refresh() {
        let show = settings.inAppKeyboard && (nativeActive || (webActive && !busy()))
        if show != visible { withAnimation(.easeOut(duration: 0.18)) { visible = show } }
    }

    private func began(_ field: UIView?) {
        generation += 1
        guard settings.inAppKeyboard else { return }
        if let f = field, f.isDescendant(of: browser.webView) { return }
        nativeActive = true
        // The system keyboard must not come up as well: this field's input view is an empty one
        if let tf = field as? UITextField {
            tf.inputAssistantItem.leadingBarButtonGroups = []
            tf.inputAssistantItem.trailingBarButtonGroups = []
            if tf.inputView == nil { tf.inputView = NoSystemKeyboard.make(); tf.reloadInputViews() }
            if tf.isSecureTextEntry || [.URL, .webSearch, .emailAddress, .asciiCapable].contains(tf.keyboardType) { show(.english) }
            else if [.decimalPad, .numberPad, .numbersAndPunctuation, .phonePad].contains(tf.keyboardType) { layer = .symbols }
        } else if let tv = field as? UITextView {
            // With no system keyboard there is no typing language to place the cursor of an empty field by:
            // in the Arabic interface it starts on the right, where the first letter will appear
            if tv.effectiveUserInterfaceLayoutDirection == .rightToLeft && tv.textAlignment == .natural { tv.textAlignment = .right }
            tv.inputAssistantItem.leadingBarButtonGroups = []
            tv.inputAssistantItem.trailingBarButtonGroups = []
            if tv.inputView == nil { tv.inputView = NoSystemKeyboard.make(); tv.reloadInputViews() }
        }
        refresh()
        // The keyboard takes the lower part of the screen: bring the field into what is left
        if let f = field {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
                var v: UIView? = f.superview
                while let s = v {
                    if let scroll = s as? UIScrollView, scroll.isScrollEnabled, !(f is UITextView && s === f) {
                        scroll.scrollRectToVisible(f.convert(f.bounds, to: scroll).insetBy(dx: 0, dy: -24), animated: true)
                        break
                    }
                    v = s.superview
                }
            }
        }
    }

    private func ended() {
        let mine = generation
        // Moving from one field to another ends the first and begins the second: wait and see
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self = self, self.generation == mine else { return }
            self.nativeActive = false
            self.refresh()
        }
    }

    private func webFocus(_ on: Bool, secure: Bool) {
        if on {
            webActive = true
            if secure { show(.english) }
            refresh()
        } else {
            let mine = generation
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                guard let self = self, self.generation == mine else { return }
                self.webActive = false
                self.refresh()
            }
        }
        generation += on ? 1 : 0
    }

    private func show(_ l: Layer) {
        layer = l
        if l != .symbols { letters = l }
        shift = false
        caps = false
    }

    func switchLanguage() { show(layer == .arabic || (layer == .symbols && letters == .arabic) ? .english : .arabic) }
    func toggleSymbols() { if layer == .symbols { show(letters) } else { layer = .symbols; shift = false; caps = false } }

    func tapShift() {
        if caps { caps = false; shift = false } else if shift { caps = true } else { shift = true }
    }

    /// Where typing goes now: a field of the app, or the page in the browser.
    private func target() -> (native: UIKeyInput?, web: Bool) {
        let r = UIResponder.wkFirst()
        if let v = r as? UIView, v.isDescendant(of: browser.webView) { return (nil, true) }
        if let k = r as? UIKeyInput { return (k, false) }
        return (nil, webActive)
    }

    private func page(_ code: String) {
        let browser = self.browser
        Task { _ = await browser.js(code + "; 1") }
    }

    func insert(_ text: String) {
        let t = target()
        if let k = t.native { k.insertText(text) } else if t.web { page("__wk.key(\(J.string(text)))") }
        if shift && !caps { shift = false }
    }

    func backspace() {
        let t = target()
        if let k = t.native { k.deleteBackward() } else if t.web { page("__wk.del()") }
    }

    func enter() {
        let r = UIResponder.wkFirst()
        if let v = r as? UIView, v.isDescendant(of: browser.webView) { page("__wk.enterKey()"); return }
        if let tf = r as? UITextField {
            // What the Return key does in a one-line field: the field's own "submit"
            if tf.delegate?.textFieldShouldReturn?(tf) ?? true { tf.sendActions(for: .editingDidEndOnExit) }
        } else if let k = r as? UIKeyInput {
            k.insertText("\n")
        } else if webActive {
            page("__wk.enterKey()")
        }
    }

    func paste() {
        if let s = UIPasteboard.general.string, !s.isEmpty { insert(s) }
    }

    /// Puts the keyboard away, as the system one's own key does.
    func hide() {
        generation += 1
        let r = UIResponder.wkFirst()
        if let v = r as? UIView, v.isDescendant(of: browser.webView) { page("document.activeElement && document.activeElement.blur()") } else { r?.resignFirstResponder() }
        if webActive { page("document.activeElement && document.activeElement.blur()") }
        nativeActive = false
        webActive = false
        refresh()
    }
}

/// The keyboard itself, laid out like the iPad's: four rows, Arabic and English letters, numbers and symbols.
struct KeyboardView: View {
    @EnvironmentObject var hub: KeyboardHub
    @Environment(\.colorScheme) private var scheme

    private enum Kind { case char, shift, delete, enter, symbols, language, space, paste, hide }

    private struct Key: Identifiable {
        let id: String
        let label: String
        var kind: Kind = .char
        var weight: CGFloat = 1
    }

    private static let arabic = ["ض ص ث ق ف غ ع ه خ ح ج د", "ش س ي ب ل ا ت ن م ك ط ذ", "ئ ء ؤ ر لا ى ة و ز ظ ، ."]
    private static let arabicShift = ["َ ً ُ ٌ ِ ٍ ْ ّ ـ ؛ × ÷", "أ إ آ ٱ لأ لإ لآ « » : \" '", "؟ ! ( ) - _ / @ # % ، ."]
    private static let english = ["q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m , . ?"]
    private static let symbols = ["1 2 3 4 5 6 7 8 9 0", "@ # $ & * ( ) ' \" -", "% _ + = / ; : ! ? ،"]
    private static let symbolsShift = ["١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩ ٠", "[ ] { } < > ^ ~ | \\", "€ £ ¥ ° • … « » ؟ ؛"]

    private func rows() -> [[Key]] {
        let upper = hub.shift || hub.caps
        let base: [String]
        switch hub.layer {
        case .arabic: base = upper ? KeyboardView.arabicShift : KeyboardView.arabic
        case .english: base = KeyboardView.english
        case .symbols: base = upper ? KeyboardView.symbolsShift : KeyboardView.symbols
        }
        func chars(_ line: String, _ row: Int) -> [Key] {
            line.split(separator: " ").enumerated().map { i, c in
                let s = hub.layer == .english && upper ? String(c).uppercased() : String(c)
                return Key(id: "\(row)-\(i)-\(s)", label: s)
            }
        }
        let shiftKey = Key(id: "shift", label: hub.caps ? "capslock.fill" : (hub.shift ? "shift.fill" : "shift"), kind: .shift, weight: 1.4)
        let row1 = chars(base[0], 0) + [Key(id: "delete", label: "delete.left", kind: .delete, weight: 1.5)]
        let row2 = chars(base[1], 1) + [Key(id: "enter", label: "return", kind: .enter, weight: 1.7)]
        let row3 = [shiftKey] + chars(base[2], 2) + [Key(id: "shift2", label: shiftKey.label, kind: .shift, weight: 1.4)]
        var row4 = [
            Key(id: "sym", label: hub.layer == .symbols ? "أ ب ABC" : "123", kind: .symbols, weight: 1.7),
            Key(id: "lang", label: hub.layer == .arabic ? "English" : "عربي", kind: .language, weight: 1.7),
            Key(id: "space", label: "", kind: .space, weight: 6),
        ]
        if hub.layer == .english { row4 += [Key(id: "4-at", label: "@"), Key(id: "4-dot", label: ".com", weight: 1.3), Key(id: "4-slash", label: "/")] }
        row4 += [Key(id: "paste", label: "لصق", kind: .paste, weight: 1.5), Key(id: "hide", label: "keyboard.chevron.compact.down", kind: .hide, weight: 1.4)]
        return [row1, row2, row3, row4]
    }

    var body: some View {
        GeometryReader { geo in
            let gap: CGFloat = 6
            let all = rows()
            let rowH = max(40, min(54, (geo.size.height - gap * 5) / 4))
            VStack(spacing: gap) {
                ForEach(Array(all.enumerated()), id: \.offset) { _, row in
                    let total = row.reduce(CGFloat(0)) { $0 + $1.weight }
                    let unit = (geo.size.width - gap * CGFloat(row.count + 1)) / max(total, 1)
                    HStack(spacing: gap) {
                        ForEach(row) { key in
                            cap(key).frame(width: max(10, unit * key.weight), height: rowH)
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onAppear { hub.frame = geo.frame(in: .global) }
            .onChange(of: geo.frame(in: .global)) { hub.frame = geo.frame(in: .global) }
        }
        .frame(height: 236)
        .padding(.bottom, 4)
        .background(Color(uiColor: scheme == .dark ? .systemGray6 : .systemGray4).ignoresSafeArea(edges: .bottom))
        .environment(\.layoutDirection, .leftToRight)
    }

    @ViewBuilder
    private func cap(_ key: Key) -> some View {
        let special = key.kind != .char && key.kind != .space
        let active = (key.kind == .shift && (hub.shift || hub.caps))
        let fill = active ? Color(uiColor: .systemBackground) : special ? Color(uiColor: scheme == .dark ? .systemGray3 : .systemGray2) : Color(uiColor: scheme == .dark ? .systemGray2 : .white)
        let face = Group {
            switch key.kind {
            case .shift, .delete, .enter, .hide:
                Image(systemName: key.label).font(.system(size: 20, weight: .regular))
            case .space:
                Text(hub.layer == .arabic ? "مسافة" : "space").font(.system(size: 15)).foregroundStyle(.secondary)
            case .char:
                Text(key.label).font(.system(size: key.label.count > 1 ? 18 : 24))
            default:
                Text(key.label).font(.system(size: 15, weight: .medium))
            }
        }
        if key.kind == .delete {
            RepeatKey(fill: fill) { hub.backspace() } label: { face }
        } else {
            Button { press(key) } label: { face.frame(maxWidth: .infinity, maxHeight: .infinity) }
                .buttonStyle(CapStyle(fill: fill))
                .accessibilityLabel(key.kind == .space ? "مسافة" : key.kind == .enter ? "إدخال" : key.kind == .hide ? "إخفاء لوحة المفاتيح" : key.kind == .shift ? "Shift" : key.label)
        }
    }

    private func press(_ key: Key) {
        switch key.kind {
        case .char: hub.insert(key.label)
        case .space: hub.insert(" ")
        case .shift: hub.tapShift()
        case .enter: hub.enter()
        case .symbols: hub.toggleSymbols()
        case .language: hub.switchLanguage()
        case .paste: hub.paste()
        case .hide: hub.hide()
        case .delete: hub.backspace()
        }
    }
}

private struct CapStyle: ButtonStyle {
    let fill: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(.primary)
            .background(configuration.isPressed ? Color(uiColor: .systemGray) : fill)
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 0, x: 0, y: 1)
    }
}

/// The delete key: one character on a tap, then faster and faster while it is held.
private struct RepeatKey<Label: View>: View {
    let fill: Color
    let action: () -> Void
    @ViewBuilder let label: () -> Label
    @State private var pressed = false
    @State private var timer: Timer?

    var body: some View {
        label()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .foregroundStyle(.primary)
            .background(pressed ? Color(uiColor: .systemGray) : fill)
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 0, x: 0, y: 1)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    if pressed { return }
                    pressed = true
                    action()
                    let started = Date()
                    timer = Timer.scheduledTimer(withTimeInterval: 0.09, repeats: true) { _ in
                        if Date().timeIntervalSince(started) > 0.45 { action() }
                    }
                }
                .onEnded { _ in
                    pressed = false
                    timer?.invalidate()
                    timer = nil
                })
            .accessibilityLabel("مسح")
            .accessibilityAddTraits(.isButton)
    }
}
