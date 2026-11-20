import Foundation

/// Builds an Excel workbook from a plain description:
/// {sheets: [{name, header: [...], rows: [[...]]}]}. A cell that starts with "=" is a formula,
/// "85%" becomes a percentage, numbers stay numbers. Arabic sheets open right to left.
enum Xlsx {
    private static let ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private static let badName = Rx("[\\[\\]:*?/\\\\]")
    private static let percent = Rx("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*%\\s*$")
    private static let number = Rx("^\\s*-?\\d+(?:\\.\\d+)?\\s*$")
    private static let leadingZero = Rx("^\\s*0\\d+\\s*$")

    static func column(_ index: Int) -> String {
        var i = index + 1
        var s = ""
        while i > 0 {
            let r = (i - 1) % 26
            s = String(UnicodeScalar(UInt8(65 + r))) + s
            i = (i - 1) / 26
        }
        return s
    }

    private static func num(_ d: Double) -> String {
        d == d.rounded() && abs(d) < 1e15 ? String(Int64(d)) : String(d)
    }

    static func build(_ spec: JSON) -> Data {
        var sheets = (spec["sheets"] as? [Any])?.compactMap { $0 as? JSON } ?? []
        if sheets.isEmpty { sheets = [spec] }
        var names: [String] = []
        var parts: [(String, String)] = []

        for (si, sh) in sheets.enumerated() {
            var name = badName.replace(J.str(sh["name"]) ?? "", with: " ").trimmed.take(31)
            if name.isEmpty { name = "Sheet\(si + 1)" }
            while names.contains(name) { name = name.take(28) + " \(si + 1)" }
            names.append(name)

            let header = OOXML.strings(sh["header"])
            let rows = OOXML.rows(sh["rows"])
            let n = max(1, max(header.count, rows.map { $0.count }.max() ?? 0))
            let sample = name + header.joined(separator: " ") + rows.prefix(5).map { $0.map { OOXML.text($0) }.joined(separator: " ") }.joined(separator: " ")
            let rtl = (sh["rtl"] as? Bool) ?? TextTools.isRTL(sample)
            var widths = Array(repeating: 8.0, count: n)

            func cell(_ ci: Int, _ ri: Int, _ v: Any?, head: Bool) -> String {
                let ref = "\(column(ci))\(ri)"
                let text = OOXML.text(v)
                if text.isEmpty { return head ? "<c r=\"\(ref)\" s=\"1\"/>" : "" }
                widths[ci] = max(widths[ci], min(60.0, Double(text.count) * 1.25 + 3))
                let inline = "t=\"inlineStr\"><is><t xml:space=\"preserve\">\(TextTools.xml(text))</t></is></c>"
                if head { return "<c r=\"\(ref)\" s=\"1\" " + inline }
                if let nmb = v as? NSNumber {
                    if CFGetTypeID(nmb) == CFBooleanGetTypeID() { return "<c r=\"\(ref)\" t=\"b\"><v>\(nmb.boolValue ? 1 : 0)</v></c>" }
                    let d = nmb.doubleValue
                    return "<c r=\"\(ref)\" s=\"\(d == d.rounded() ? 0 : 2)\"><v>\(num(d))</v></c>"
                }
                if text.hasPrefix("=") && text.count > 1 { return "<c r=\"\(ref)\" s=\"3\"><f>\(TextTools.xml(String(text.dropFirst())))</f></c>" }
                if let m = percent.first(text), let p = m[1], let d = Double(p) { return "<c r=\"\(ref)\" s=\"4\"><v>\(d / 100)</v></c>" }
                if number.test(text) && !leadingZero.test(text) {
                    return "<c r=\"\(ref)\" s=\"\(text.contains(".") ? 2 : 0)\"><v>\(text.trimmed)</v></c>"
                }
                return "<c r=\"\(ref)\" " + inline
            }

            var data: [String] = []
            var ri = 1
            if !header.isEmpty {
                let padded = header + Array(repeating: "", count: n - header.count)
                data.append("<row r=\"1\" ht=\"24\" customHeight=\"1\">" + (0..<n).map { cell($0, 1, padded[$0], head: true) }.joined() + "</row>")
                ri = 2
            }
            for r in rows {
                data.append("<row r=\"\(ri)\">" + r.prefix(n).enumerated().map { cell($0.offset, ri, $0.element, head: false) }.joined() + "</row>")
                ri += 1
            }
            let last = "\(column(n - 1))\(max(1, ri - 1))"
            let view = "<sheetView" + (rtl ? " rightToLeft=\"1\"" : "") + (si == 0 ? " tabSelected=\"1\"" : "") + " workbookViewId=\"0\">" +
                (header.isEmpty ? "" : "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>") + "</sheetView>"
            let cols = widths.enumerated().map { "<col min=\"\($0.offset + 1)\" max=\"\($0.offset + 1)\" width=\"" + String(format: "%.1f", $0.element) + "\" customWidth=\"1\"/>" }.joined()
            let xml = OOXML.head + "<worksheet xmlns=\"\(ns)\"><dimension ref=\"A1:\(last)\"/><sheetViews>\(view)</sheetViews><sheetFormatPr defaultRowHeight=\"18\"/><cols>\(cols)</cols><sheetData>" +
                data.joined() + "</sheetData>" + (!header.isEmpty && !rows.isEmpty ? "<autoFilter ref=\"A1:\(last)\"/>" : "") +
                "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>"
            parts.append(("xl/worksheets/sheet\(si + 1).xml", xml))
        }

        let workbook = OOXML.head + "<workbook xmlns=\"\(ns)\" xmlns:r=\"\(OOXML.od)\"><bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews><sheets>" +
            names.enumerated().map { "<sheet name=\"\(TextTools.xml($0.element))\" sheetId=\"\($0.offset + 1)\" r:id=\"rId\($0.offset + 1)\"/>" }.joined() +
            "</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>"

        let xt = "application/vnd.openxmlformats-officedocument.spreadsheetml."
        let k = names.count
        var zip = ZipWriter()
        zip.add("[Content_Types].xml", OOXML.types([("/xl/workbook.xml", xt + "sheet.main+xml"), ("/xl/styles.xml", xt + "styles+xml")] +
            (0..<k).map { ("/xl/worksheets/sheet\($0 + 1).xml", xt + "worksheet+xml") }))
        zip.add("_rels/.rels", OOXML.rels([(OOXML.od + "/officeDocument", "xl/workbook.xml")]))
        zip.add("xl/workbook.xml", workbook)
        zip.add("xl/_rels/workbook.xml.rels", OOXML.rels((0..<k).map { (OOXML.od + "/worksheet", "worksheets/sheet\($0 + 1).xml") } + [(OOXML.od + "/styles", "styles.xml")]))
        zip.add("xl/styles.xml", styles())
        for p in parts { zip.add(p.0, p.1) }
        return zip.finish()
    }

    /// Cell styles: 0 text/number, 1 header, 2 two decimals, 3 formula (bold on a tint), 4 percentage.
    private static func styles() -> String {
        func font(_ extra: String) -> String { "<font>\(extra)<sz val=\"12\"/><name val=\"Arial\"/><family val=\"2\"/></font>" }
        func xf(_ numFmt: Int, _ fontId: Int, _ fill: Int, _ align: String) -> String {
            "<xf numFmtId=\"\(numFmt)\" fontId=\"\(fontId)\" fillId=\"\(fill)\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">\(align)</xf>"
        }
        let border = "<border>" + ["left", "right", "top", "bottom"].map { "<\($0) style=\"thin\"><color rgb=\"FFCBD5E1\"/></\($0)>" }.joined() + "<diagonal/></border>"
        return OOXML.head + "<styleSheet xmlns=\"\(ns)\"><fonts count=\"3\">" + font("") + font("<b/><color rgb=\"FFFFFFFF\"/>") + font("<b/>") + "</fonts>" +
            "<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF\(Docx.accent)\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE6F4F1\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
            "<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" + border + "</borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"5\">" +
            xf(0, 0, 0, "<alignment vertical=\"center\" wrapText=\"1\"/>") + xf(0, 1, 2, "<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/>") +
            xf(2, 0, 0, "<alignment vertical=\"center\"/>") + xf(0, 2, 3, "<alignment vertical=\"center\"/>") + xf(9, 0, 0, "<alignment vertical=\"center\"/>") +
            "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
    }
}
