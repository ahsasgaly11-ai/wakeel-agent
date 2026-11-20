import Foundation

/// Builds a Word document from a plain description:
/// {title, subtitle, blocks: [{type: heading|paragraph|bullets|numbered|table|quote|page_break, …}]}.
/// Arabic text is laid out right to left, paragraph by paragraph.
enum Docx {
    private static let w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    static let accent = "0F766E"

    private static func run(_ text: String, bold: Bool, rtl: Bool, color: String?, size: Int?) -> String {
        var rp = bold ? "<w:b/><w:bCs/>" : ""
        if let c = color { rp += "<w:color w:val=\"\(c)\"/>" }
        if let s = size { rp += "<w:sz w:val=\"\(s)\"/><w:szCs w:val=\"\(s)\"/>" }
        if rtl { rp += "<w:rtl/>" }
        return "<w:r>" + (rp.isEmpty ? "" : "<w:rPr>\(rp)</w:rPr>") + "<w:t xml:space=\"preserve\">\(TextTools.xml(text))</w:t></w:r>"
    }

    private static func para(_ text: String, style: String? = nil, rtl: Bool, num: Int? = nil, jc: String? = nil,
                             color: String? = nil, bold: Bool = false, size: Int? = nil) -> String {
        var pp = ""
        if let s = style { pp += "<w:pStyle w:val=\"\(s)\"/>" }
        if let n = num { pp += "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"\(n)\"/></w:numPr>" }
        if rtl { pp += "<w:bidi/>" }
        if let j = jc { pp += "<w:jc w:val=\"\(j)\"/>" }
        return "<w:p>" + (pp.isEmpty ? "" : "<w:pPr>\(pp)</w:pPr>") +
            TextTools.runs(text).map { run($0.0, bold: $0.1 || bold, rtl: rtl, color: color, size: size) }.joined() + "</w:p>"
    }

    private static let hasLetters = Rx("[A-Za-z\\u0590-\\u08FF]")

    static func build(_ spec: JSON) -> Data {
        let title = J.str(spec["title"]) ?? ""
        let blocks = (spec["blocks"] as? [Any])?.compactMap { $0 as? JSON } ?? []
        let sample = title + " " + blocks.prefix(6).map { J.string($0) }.joined(separator: " ")
        let docRTL = (spec["rtl"] as? Bool) ?? TextTools.isRTL(sample)
        // A paragraph follows its own language; one with no letters at all follows the document
        func r(_ s: String) -> Bool { hasLetters.test(s) ? TextTools.isRTL(s) : docRTL }

        var body: [String] = []
        var nums: [Int] = []          // one numbering instance per numbered list, so each restarts at 1
        if !title.isEmpty { body.append(para(title, style: "Title", rtl: r(title), jc: "center")) }
        if let sub = J.str(spec["subtitle"]), !sub.isEmpty { body.append(para(sub, style: "Subtitle", rtl: r(sub), jc: "center")) }

        for b in blocks {
            let type = J.str(b["type"]) ?? "paragraph"
            let text = OOXML.text(b["text"])
            switch type {
            case "heading":
                let level = min(3, max(1, J.int(b["level"]) ?? 1))
                body.append(para(text, style: "Heading\(level)", rtl: r(text)))
            case "bullets":
                for i in OOXML.strings(b["items"]) { body.append(para(i, style: "ListParagraph", rtl: r(i), num: 1)) }
            case "numbered":
                nums.append(nums.count + 2)
                for i in OOXML.strings(b["items"]) { body.append(para(i, style: "ListParagraph", rtl: r(i), num: nums[nums.count - 1])) }
            case "quote":
                body.append(para(text, style: "Quote", rtl: r(text)))
            case "page_break":
                body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
            case "table":
                let header = OOXML.strings(b["header"])
                let rows = OOXML.rows(b["rows"])
                let n = max(1, max(header.count, rows.map { $0.count }.max() ?? 0))
                func cell(_ v: String, _ head: Bool) -> String {
                    let shade = head ? "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"\(accent)\"/>" : ""
                    return "<w:tc><w:tcPr><w:tcW w:w=\"\(5000 / n)\" w:type=\"pct\"/>\(shade)</w:tcPr>" +
                        para(v, rtl: r(v), color: head ? "FFFFFF" : nil, bold: head) + "</w:tc>"
                }
                func row(_ vals: [String], _ head: Bool) -> String {
                    let padded = (vals + Array(repeating: "", count: n)).prefix(n)
                    return "<w:tr>" + (head ? "<w:trPr><w:tblHeader/></w:trPr>" : "") + padded.map { cell($0, head) }.joined() + "</w:tr>"
                }
                body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>" + (docRTL ? "<w:bidiVisual/>" : "") +
                    "<w:tblW w:w=\"5000\" w:type=\"pct\"/></w:tblPr><w:tblGrid>" + String(repeating: "<w:gridCol w:w=\"\(9000 / n)\"/>", count: n) + "</w:tblGrid>" +
                    (header.isEmpty ? "" : row(header, true)) + rows.map { row($0.map { OOXML.text($0) }, false) }.joined() + "</w:tbl><w:p/>")
            default:
                body.append(para(text, rtl: r(text)))
            }
        }

        let document = OOXML.head + "<w:document xmlns:w=\"\(w)\"><w:body>" + body.joined() +
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>" +
            (docRTL ? "<w:bidi/>" : "") + "</w:sectPr></w:body></w:document>"

        let wt = "application/vnd.openxmlformats-officedocument.wordprocessingml."
        var zip = ZipWriter()
        zip.add("[Content_Types].xml", OOXML.types([("/word/document.xml", wt + "document.main+xml"), ("/word/styles.xml", wt + "styles+xml"),
                                                   ("/word/numbering.xml", wt + "numbering+xml"), ("/docProps/core.xml", OOXML.coreType)]))
        zip.add("_rels/.rels", OOXML.rels([(OOXML.od + "/officeDocument", "word/document.xml"), (OOXML.coreRel, "docProps/core.xml")]))
        zip.add("docProps/core.xml", OOXML.core(title))
        zip.add("word/document.xml", document)
        zip.add("word/styles.xml", styles())
        zip.add("word/numbering.xml", numbering(nums))
        zip.add("word/_rels/document.xml.rels", OOXML.rels([(OOXML.od + "/styles", "styles.xml"), (OOXML.od + "/numbering", "numbering.xml")]))
        return zip.finish()
    }

    private static func sz(_ n: Int) -> String { "<w:sz w:val=\"\(n)\"/><w:szCs w:val=\"\(n)\"/>" }

    private static func style(_ id: String, _ name: String, based: String?, ppr: String, rpr: String, isDefault: Bool = false) -> String {
        "<w:style w:type=\"paragraph\"" + (isDefault ? " w:default=\"1\"" : "") + " w:styleId=\"\(id)\"><w:name w:val=\"\(name)\"/>" +
            (based.map { "<w:basedOn w:val=\"\($0)\"/>" } ?? "") + (id != "Normal" ? "<w:next w:val=\"Normal\"/>" : "") + "<w:qFormat/>" +
            (ppr.isEmpty ? "" : "<w:pPr>\(ppr)</w:pPr>") + (rpr.isEmpty ? "" : "<w:rPr>\(rpr)</w:rPr>") + "</w:style>"
    }

    private static func styles() -> String {
        let borders = ["top", "left", "bottom", "right", "insideH", "insideV"].map { "<w:\($0) w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"94A3B8\"/>" }.joined()
        var s = OOXML.head + "<w:styles xmlns:w=\"\(w)\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Arial\" w:cs=\"Arial\"/>" + sz(24) +
            "<w:lang w:val=\"en-US\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
        s += style("Normal", "Normal", based: nil, ppr: "", rpr: "", isDefault: true)
        s += style("Title", "Title", based: "Normal", ppr: "<w:spacing w:before=\"0\" w:after=\"120\"/><w:jc w:val=\"center\"/>", rpr: "<w:b/><w:bCs/><w:color w:val=\"\(accent)\"/>" + sz(56))
        s += style("Subtitle", "Subtitle", based: "Normal", ppr: "<w:spacing w:after=\"360\"/><w:jc w:val=\"center\"/>", rpr: "<w:color w:val=\"5B6770\"/>" + sz(28))
        s += style("Heading1", "heading 1", based: "Normal", ppr: "<w:keepNext/><w:spacing w:before=\"360\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/>", rpr: "<w:b/><w:bCs/><w:color w:val=\"\(accent)\"/>" + sz(36))
        s += style("Heading2", "heading 2", based: "Normal", ppr: "<w:keepNext/><w:spacing w:before=\"280\" w:after=\"100\"/><w:outlineLvl w:val=\"1\"/>", rpr: "<w:b/><w:bCs/><w:color w:val=\"134E4A\"/>" + sz(30))
        s += style("Heading3", "heading 3", based: "Normal", ppr: "<w:keepNext/><w:spacing w:before=\"220\" w:after=\"80\"/><w:outlineLvl w:val=\"2\"/>", rpr: "<w:b/><w:bCs/><w:color w:val=\"334155\"/>" + sz(26))
        s += style("ListParagraph", "List Paragraph", based: "Normal", ppr: "<w:spacing w:after=\"80\"/><w:contextualSpacing/>", rpr: "")
        s += style("Quote", "Quote", based: "Normal",
                   ppr: "<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"12\" w:color=\"\(accent)\"/><w:right w:val=\"single\" w:sz=\"18\" w:space=\"12\" w:color=\"\(accent)\"/></w:pBdr><w:spacing w:before=\"160\" w:after=\"200\"/><w:ind w:left=\"360\" w:right=\"360\"/>",
                   rpr: "<w:i/><w:iCs/><w:color w:val=\"475569\"/>")
        s += "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
        s += "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:tblPr><w:tblBorders>" + borders +
            "</w:tblBorders><w:tblCellMar><w:top w:w=\"80\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"80\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style></w:styles>"
        return s
    }

    private static func level(_ fmt: String, _ text: String, font: String?) -> String {
        "<w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"\(fmt)\"/><w:lvlText w:val=\"\(text)\"/><w:lvlJc w:val=\"left\"/>" +
            "<w:pPr><w:ind w:left=\"720\" w:hanging=\"360\"/></w:pPr>" +
            (font.map { "<w:rPr><w:rFonts w:ascii=\"\($0)\" w:hAnsi=\"\($0)\" w:hint=\"default\"/></w:rPr>" } ?? "") + "</w:lvl>"
    }

    private static func numbering(_ nums: [Int]) -> String {
        OOXML.head + "<w:numbering xmlns:w=\"\(w)\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" + level("bullet", "\u{2022}", font: "Arial") + "</w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>" + level("decimal", "%1.", font: nil) + "</w:abstractNum>" +
            "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            nums.map { "<w:num w:numId=\"\($0)\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>" }.joined() +
            "</w:numbering>"
    }
}
