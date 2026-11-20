import SwiftUI

enum Brand {
    static let teal = Color(red: 0.06, green: 0.46, blue: 0.43)
    static let mint = Color(red: 0.24, green: 0.86, blue: 0.65)
    static let amber = Color(red: 0.85, green: 0.47, blue: 0.02)
    static let ruby = Color(red: 0.86, green: 0.15, blue: 0.27)

    static func color(_ kind: LogKind) -> Color {
        switch kind {
        case .success: return .green
        case .warn: return amber
        case .error: return ruby
        case .local: return teal
        case .think: return .secondary
        default: return .primary
        }
    }

    static func icon(_ kind: LogKind) -> String {
        switch kind {
        case .success: return "checkmark.circle.fill"
        case .warn: return "exclamationmark.triangle.fill"
        case .error: return "xmark.octagon.fill"
        case .local: return "bolt.fill"
        case .think: return "brain"
        case .action: return "hand.tap.fill"
        default: return "circle.fill"
        }
    }
}

/// A rounded card with an optional coloured edge.
struct Panel<Content: View>: View {
    var accent: Color?
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content() }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder((accent ?? Color.clear).opacity(0.45), lineWidth: 1.5))
    }
}

struct PanelTitle: View {
    let text: String
    let symbol: String
    var color: Color = Brand.teal

    var body: some View {
        Label(text, systemImage: symbol).font(.headline).foregroundStyle(color)
    }
}

struct Chip: View {
    let text: String
    var symbol: String?
    var color: Color = Brand.teal

    var body: some View {
        HStack(spacing: 5) {
            if let s = symbol { Image(systemName: s).font(.caption) }
            Text(text).font(.caption).lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(color.opacity(0.14))
        .foregroundStyle(color)
        .clipShape(Capsule())
    }
}

struct StatTile: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.weight(.bold)).foregroundStyle(Brand.teal).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(.tertiarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Shows an answer the way it was written: "## " headings, "- " bullets, **bold**, and small "|" tables.
struct ResultText: View {
    let text: String

    private func rich(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s, options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
    }

    var body: some View {
        let lines = text.components(separatedBy: "\n")
        VStack(alignment: .leading, spacing: 7) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, raw in
                let line = raw.trimmingCharacters(in: .whitespaces)
                if line.isEmpty {
                    Spacer().frame(height: 2)
                } else if line.hasPrefix("## ") || line.hasPrefix("# ") {
                    Text(rich(String(line.drop(while: { $0 == "#" || $0 == " " })))).font(.headline).foregroundStyle(Brand.teal).padding(.top, 4)
                } else if line.hasPrefix("- ") || line.hasPrefix("• ") || line.hasPrefix("* ") {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle().fill(Brand.teal).frame(width: 6, height: 6)
                        Text(rich(String(line.dropFirst(2)))).fixedSize(horizontal: false, vertical: true)
                    }
                } else if line.hasPrefix("|") {
                    let cells = line.split(separator: "|").map { $0.trimmingCharacters(in: .whitespaces) }
                    if !cells.allSatisfy({ $0.allSatisfy { $0 == "-" || $0 == ":" || $0 == " " } }) {
                        HStack(alignment: .top, spacing: 8) {
                            ForEach(Array(cells.enumerated()), id: \.offset) { _, c in
                                Text(rich(c)).font(.callout).frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                        .padding(.vertical, 4)
                        .padding(.horizontal, 8)
                        .background(Color(.tertiarySystemGroupedBackground))
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                } else {
                    Text(rich(line)).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension TaskStatus {
    var title: String {
        switch self {
        case .done: return "تمّت المهمة"
        case .failed: return "تعذّرت المهمة"
        case .stopped: return "أُوقفت المهمة"
        }
    }
    var color: Color {
        switch self {
        case .done: return .green
        case .failed: return Brand.ruby
        case .stopped: return Brand.amber
        }
    }
    var symbol: String {
        switch self {
        case .done: return "checkmark.seal.fill"
        case .failed: return "xmark.seal.fill"
        case .stopped: return "pause.circle.fill"
        }
    }
}

func money(_ v: Double) -> String { v < 0.0005 ? "$0" : String(format: "$%.3f", v) }
