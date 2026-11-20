import Foundation

/// Builds a PowerPoint deck from a plain description:
/// {title, subtitle, theme, slides: [{layout: title|section|bullets|two|table|quote, title, …}]}.
/// Every slide is drawn with explicit shapes, so it looks the same in PowerPoint, Keynote and previews.
enum Pptx {
    private static let a = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private static let p = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private static var nsAttrs: String { "xmlns:a=\"\(a)\" xmlns:r=\"\(OOXML.od)\" xmlns:p=\"\(p)\"" }
    private static let slideW = 12192000
    private static let slideH = 6858000

    struct Theme {
        let bg: String, title: String, text: String, accent: String, soft: String, on: String
    }

    static let themes: [String: Theme] = [
        "teal": Theme(bg: "FFFFFF", title: "0F766E", text: "1F2937", accent: "0F766E", soft: "E6F4F1", on: "FFFFFF"),
        "maroon": Theme(bg: "FFFFFF", title: "8A1538", text: "1F2937", accent: "8A1538", soft: "F6E7EB", on: "FFFFFF"),
        "navy": Theme(bg: "FFFFFF", title: "1E3A8A", text: "1F2937", accent: "1E3A8A", soft: "E8EEFB", on: "FFFFFF"),
        "dark": Theme(bg: "0F172A", title: "5EEAD4", text: "E2E8F0", accent: "14B8A6", soft: "1E293B", on: "0F172A"),
        "sand": Theme(bg: "FBF7F0", title: "92400E", text: "292524", accent: "B45309", soft: "F3E8D6", on: "FFFFFF"),
    ]

    private struct Para {
        var text: String
        var size: Double
        var color: String
        var bold = false
        var bullet: Int? = nil
        var align: String? = nil
        var accent: String? = nil
    }

    private static func para(_ q: Para) -> String {
        let rtl = TextTools.isRTL(q.text)
        let algn = q.align ?? (rtl ? "r" : "l")
        var attrs = " algn=\"\(algn)\" rtl=\"\(rtl ? 1 : 0)\""
        if let b = q.bullet { attrs = " marL=\"\(b)\"" + (rtl ? " marR=\"\(b)\"" : "") + " indent=\"-\(b)\"" + attrs }
        let bu = q.bullet != nil
            ? "<a:buClr><a:srgbClr val=\"\(q.accent ?? q.color)\"/></a:buClr><a:buFont typeface=\"Arial\"/><a:buChar char=\"&#8226;\"/>"
            : "<a:buNone/>"
        let ppr = "<a:pPr\(attrs)><a:lnSpc><a:spcPct val=\"110000\"/></a:lnSpc><a:spcBef><a:spcPts val=\"\(bu.hasPrefix("<a:buClr") ? 600 : 0)\"/></a:spcBef>\(bu)</a:pPr>"
        let runs = TextTools.runs(q.text).map { piece -> String in
            "<a:r><a:rPr lang=\"\(rtl ? "ar-SA" : "en-US")\" sz=\"\(Int(q.size * 100))\" b=\"\((piece.1 || q.bold) ? 1 : 0)\" dirty=\"0\"><a:solidFill><a:srgbClr val=\"\(q.color)\"/></a:solidFill>" +
                "<a:latin typeface=\"Arial\"/><a:cs typeface=\"Arial\"/></a:rPr><a:t>\(TextTools.xml(piece.0))</a:t></a:r>"
        }.joined()
        return "<a:p>\(ppr)\(runs)</a:p>"
    }

    private final class Canvas {
        var shapes: [String] = []
        private var n = 1
        func nextId() -> Int { n += 1; return n }

        func rect(_ x: Int, _ y: Int, _ w: Int, _ h: Int, _ color: String, round: Bool = false) {
            let i = nextId()
            shapes.append("<p:sp><p:nvSpPr><p:cNvPr id=\"\(i)\" name=\"Shape \(i)\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"\(x)\" y=\"\(y)\"/><a:ext cx=\"\(w)\" cy=\"\(h)\"/></a:xfrm>" +
                "<a:prstGeom prst=\"\(round ? "roundRect" : "rect")\"><a:avLst>" + (round ? "<a:gd name=\"adj\" fmla=\"val 5000\"/>" : "") + "</a:avLst></a:prstGeom>" +
                "<a:solidFill><a:srgbClr val=\"\(color)\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
                "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>")
        }

        func text(_ x: Int, _ y: Int, _ w: Int, _ h: Int, _ paras: [Para], anchor: String = "t") {
            let i = nextId()
            shapes.append("<p:sp><p:nvSpPr><p:cNvPr id=\"\(i)\" name=\"Text \(i)\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"\(x)\" y=\"\(y)\"/><a:ext cx=\"\(w)\" cy=\"\(h)\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" rtlCol=\"0\" anchor=\"\(anchor)\"><a:noAutofit/></a:bodyPr><a:lstStyle/>" +
                paras.map { Pptx.para($0) }.joined() + "</p:txBody></p:sp>")
        }
    }

    /// Font size for a list: fewer and shorter items get larger type.
    private static func fit(_ items: [String], big: Double, small: Double, room: Double = 9) -> Double {
        let load = Double(items.reduce(0) { $0 + 1 + $1.count / 55 })
        return load <= room * 0.6 ? big : load <= room ? (big + small) / 2 : small
    }

    private static func table(_ c: Canvas, x: Int, y: Int, w: Int, header: [String], rows: [[String]], th: Theme, maxH: Int) {
        let n = max(1, max(header.count, rows.map { $0.count }.max() ?? 0))
        let rtl = TextTools.isRTL(header.joined(separator: " ") + rows.prefix(3).map { $0.joined(separator: " ") }.joined(separator: " "))
        let count = rows.count + (header.isEmpty ? 0 : 1)
        let rh = min(520000, maxH / max(1, count))
        let size: Double = count <= 7 ? 16 : count <= 10 ? 13 : 11
        func tc(_ v: String, head: Bool, band: Bool) -> String {
            let fill = head ? th.accent : (band ? th.soft : th.bg)
            let ln = ["lnL", "lnR", "lnT", "lnB"].map { "<a:\($0) w=\"6350\"><a:solidFill><a:srgbClr val=\"\(th.accent)\"/></a:solidFill></a:\($0)>" }.joined()
            return "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/>" + para(Para(text: v, size: size, color: head ? th.on : th.text, bold: head, align: head ? "ctr" : nil)) +
                "</a:txBody><a:tcPr marL=\"91440\" marR=\"91440\" marT=\"45720\" marB=\"45720\" anchor=\"ctr\">\(ln)<a:solidFill><a:srgbClr val=\"\(fill)\"/></a:solidFill></a:tcPr></a:tc>"
        }
        func tr(_ vals: [String], head: Bool, band: Bool) -> String {
            let padded = (vals + Array(repeating: "", count: n)).prefix(n)
            return "<a:tr h=\"\(rh)\">" + padded.map { tc($0, head: head, band: band) }.joined() + "</a:tr>"
        }
        let i = c.nextId()
        c.shapes.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"\(i)\" name=\"Table \(i)\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>" +
            "<p:xfrm><a:off x=\"\(x)\" y=\"\(y)\"/><a:ext cx=\"\(w)\" cy=\"\(rh * count)\"/></p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">" +
            "<a:tbl><a:tblPr rtl=\"\(rtl ? 1 : 0)\" firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>" + String(repeating: "<a:gridCol w=\"\(w / n)\"/>", count: n) + "</a:tblGrid>" +
            (header.isEmpty ? "" : tr(header, head: true, band: false)) + rows.enumerated().map { tr($0.element, head: false, band: $0.offset % 2 == 1) }.joined() +
            "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
    }

    private static let group = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private static func slide(_ sp: JSON, th: Theme, index: Int, total: Int, deck: String) -> String {
        let c = Canvas()
        let layout = J.str(sp["layout"]) ?? "bullets"
        let t = J.str(sp["title"]) ?? ""
        let sub = J.str(sp["subtitle"]) ?? ""
        let m = 640000
        let cw = slideW - 2 * m
        var back = th.bg

        if layout == "title" {
            back = th.accent
            c.rect(TextTools.isRTL(t) ? slideW - m - 1400000 : m, 2560000, 1400000, 60000, th.on)
            c.text(m, 2700000, cw, 1500000, [Para(text: t, size: t.count < 40 ? 48 : 38, color: th.on, bold: true)])
            if !sub.isEmpty { c.text(m, 4250000, cw, 900000, [Para(text: sub, size: 22, color: th.on)]) }
        } else if layout == "section" {
            back = th.soft
            c.rect(0, 0, 260000, slideH, th.accent)
            c.text(m, 2300000, cw, 1500000, [Para(text: t, size: 42, color: th.title, bold: true)], anchor: "ctr")
            if !sub.isEmpty { c.text(m, 3800000, cw, 800000, [Para(text: sub, size: 22, color: th.text)]) }
        } else {
            c.rect(0, 0, slideW, 110000, th.accent)
            c.text(m, 360000, cw, 950000, [Para(text: t, size: t.count < 48 ? 34 : 28, color: th.title, bold: true)], anchor: "ctr")
            c.rect(TextTools.isRTL(t) ? slideW - m - 900000 : m, 1330000, 900000, 45000, th.accent)
            let top = 1560000
            let h = slideH - top - 620000
            switch layout {
            case "two":
                let gap = 300000
                let colW = (cw - gap) / 2
                let cols = [(sp["left"] as? JSON) ?? [:], (sp["right"] as? JSON) ?? [:]]
                // In an Arabic deck the first column is the one on the right
                let xs = TextTools.isRTL(t) ? [m + colW + gap, m] : [m, m + colW + gap]
                for (col, x) in zip(cols, xs) {
                    c.rect(x, top, colW, h, th.soft, round: true)
                    var items = OOXML.strings(col["bullets"])
                    if items.isEmpty, let one = J.str(col["text"]), !one.isEmpty { items = [one] }
                    let size = fit(items, big: 22, small: 16, room: 7)
                    var ps: [Para] = []
                    if let ct = J.str(col["title"]), !ct.isEmpty { ps.append(Para(text: ct, size: 22, color: th.title, bold: true)) }
                    ps += items.map { Para(text: $0, size: size, color: th.text, bullet: 300000, accent: th.accent) }
                    if ps.isEmpty { ps = [Para(text: "", size: 18, color: th.text)] }
                    c.text(x + 150000, top + 120000, colW - 300000, h - 240000, ps)
                }
            case "table":
                let tb = (sp["table"] as? JSON) ?? sp
                table(c, x: m, y: top, w: cw, header: OOXML.strings(tb["header"]), rows: OOXML.rows(tb["rows"]).map { $0.map { OOXML.text($0) } }, th: th, maxH: h)
            case "quote":
                let q = J.str(sp["text"]) ?? OOXML.strings(sp["bullets"]).first ?? ""
                c.rect(m, top, cw, h, th.soft, round: true)
                c.text(m + 400000, top + 200000, cw - 800000, h - 1000000, [Para(text: q, size: q.count < 120 ? 30 : 24, color: th.title, bold: true, align: "ctr")], anchor: "ctr")
                if let by = J.str(sp["by"]), !by.isEmpty {
                    c.text(m + 400000, top + h - 800000, cw - 800000, 600000, [Para(text: "— " + by, size: 18, color: th.text, align: "ctr")], anchor: "ctr")
                }
            default:
                let items = OOXML.strings(sp["bullets"])
                let size = fit(items, big: 26, small: 18)
                var ps: [Para] = []
                if let text = J.str(sp["text"]), !text.isEmpty { ps.append(Para(text: text, size: 22, color: th.text)) }
                ps += items.map { Para(text: $0, size: size, color: th.text, bullet: 342900, accent: th.accent) }
                if ps.isEmpty { ps = [Para(text: "", size: 18, color: th.text)] }
                c.text(m, top, cw, h, ps)
            }
            c.text(m, slideH - 520000, cw, 380000, [Para(text: "\(deck)  ·  \(index) / \(total)", size: 11, color: "94A3B8", align: "ctr")], anchor: "ctr")
        }
        return OOXML.head + "<p:sld \(nsAttrs)><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"\(back)\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg><p:spTree>" + group +
            c.shapes.joined() + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"
    }

    private static func themeXML(_ th: Theme) -> String {
        func c(_ n: String, _ v: String) -> String { "<a:\(n)><a:srgbClr val=\"\(v)\"/></a:\(n)>" }
        func font(_ n: String) -> String { "<a:\(n)><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"Arial\"/></a:\(n)>" }
        let fill3 = String(repeating: "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>", count: 3)
        let ln3 = [6350, 12700, 19050].map { "<a:ln w=\"\($0)\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>" }.joined()
        let eff3 = String(repeating: "<a:effectStyle><a:effectLst/></a:effectStyle>", count: 3)
        return OOXML.head + "<a:theme xmlns:a=\"\(a)\" name=\"Wakeel\"><a:themeElements><a:clrScheme name=\"Wakeel\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            c("dk2", "1F2937") + c("lt2", th.soft) + c("accent1", th.accent) + c("accent2", th.title) + c("accent3", "64748B") + c("accent4", "D97706") +
            c("accent5", "2563EB") + c("accent6", "16A34A") + c("hlink", "2563EB") + c("folHlink", "7C3AED") + "</a:clrScheme>" +
            "<a:fontScheme name=\"Wakeel\">" + font("majorFont") + font("minorFont") + "</a:fontScheme>" +
            "<a:fmtScheme name=\"Wakeel\"><a:fillStyleLst>\(fill3)</a:fillStyleLst><a:lnStyleLst>\(ln3)</a:lnStyleLst><a:effectStyleLst>\(eff3)</a:effectStyleLst><a:bgFillStyleLst>\(fill3)</a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"
    }

    private static func levels(_ size: Int) -> String {
        (1...9).map { i -> String in
            "<a:lvl\(i)pPr marL=\"\((i - 1) * 457200)\" algn=\"l\" defTabSz=\"914400\" rtl=\"0\" eaLnBrk=\"1\" latinLnBrk=\"0\" hangingPunct=\"1\"><a:defRPr sz=\"\(size)\" kern=\"1200\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill>" +
                "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"+mn-cs\"/></a:defRPr></a:lvl\(i)pPr>"
        }.joined()
    }

    static func build(_ spec: JSON, defaultTheme: String = "teal") -> Data {
        let themeName = (J.str(spec["theme"]) ?? defaultTheme).lowercased()
        let th = themes[themeName] ?? themes[defaultTheme] ?? themes["teal"]!
        var slides = (spec["slides"] as? [Any])?.compactMap { $0 as? JSON } ?? []
        let title = J.str(spec["title"]) ?? ""
        if !title.isEmpty && (slides.isEmpty || J.str(slides[0]["layout"]) != "title") {
            slides.insert(["layout": "title", "title": title, "subtitle": J.str(spec["subtitle"]) ?? ""], at: 0)
        }
        if slides.isEmpty { slides = [["layout": "title", "title": title]] }
        let n = slides.count

        let master = OOXML.head + "<p:sldMaster \(nsAttrs)><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>\(group)</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle>\(levels(4400))</p:titleStyle><p:bodyStyle>\(levels(2400))</p:bodyStyle><p:otherStyle>\(levels(1800))</p:otherStyle></p:txStyles></p:sldMaster>"
        let layout = OOXML.head + "<p:sldLayout \(nsAttrs) type=\"blank\" preserve=\"1\"><p:cSld name=\"Blank\"><p:spTree>\(group)</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
        let pres = OOXML.head + "<p:presentation \(nsAttrs) saveSubsetFonts=\"1\"" + (TextTools.isRTL(title) ? " rtl=\"1\"" : "") + "><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
            (0..<n).map { "<p:sldId id=\"\(256 + $0)\" r:id=\"rId\($0 + 2)\"/>" }.joined() + "</p:sldIdLst><p:sldSz cx=\"\(slideW)\" cy=\"\(slideH)\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
            "<p:defaultTextStyle>\(levels(1800))</p:defaultTextStyle></p:presentation>"

        let pt = "application/vnd.openxmlformats-officedocument.presentationml."
        var zip = ZipWriter()
        zip.add("[Content_Types].xml", OOXML.types([("/ppt/presentation.xml", pt + "presentation.main+xml"), ("/ppt/slideMasters/slideMaster1.xml", pt + "slideMaster+xml"),
                                                   ("/ppt/slideLayouts/slideLayout1.xml", pt + "slideLayout+xml"), ("/ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml"),
                                                   ("/docProps/core.xml", OOXML.coreType)] + (0..<n).map { ("/ppt/slides/slide\($0 + 1).xml", pt + "slide+xml") }))
        zip.add("_rels/.rels", OOXML.rels([(OOXML.od + "/officeDocument", "ppt/presentation.xml"), (OOXML.coreRel, "docProps/core.xml")]))
        zip.add("docProps/core.xml", OOXML.core(title))
        zip.add("ppt/presentation.xml", pres)
        zip.add("ppt/_rels/presentation.xml.rels", OOXML.rels([(OOXML.od + "/slideMaster", "slideMasters/slideMaster1.xml")] +
            (0..<n).map { (OOXML.od + "/slide", "slides/slide\($0 + 1).xml") } + [(OOXML.od + "/theme", "theme/theme1.xml")]))
        zip.add("ppt/slideMasters/slideMaster1.xml", master)
        zip.add("ppt/slideMasters/_rels/slideMaster1.xml.rels", OOXML.rels([(OOXML.od + "/slideLayout", "../slideLayouts/slideLayout1.xml"), (OOXML.od + "/theme", "../theme/theme1.xml")]))
        zip.add("ppt/slideLayouts/slideLayout1.xml", layout)
        zip.add("ppt/slideLayouts/_rels/slideLayout1.xml.rels", OOXML.rels([(OOXML.od + "/slideMaster", "../slideMasters/slideMaster1.xml")]))
        zip.add("ppt/theme/theme1.xml", themeXML(th))
        for (i, sp) in slides.enumerated() {
            zip.add("ppt/slides/slide\(i + 1).xml", slide(sp, th: th, index: i + 1, total: n, deck: title))
            zip.add("ppt/slides/_rels/slide\(i + 1).xml.rels", OOXML.rels([(OOXML.od + "/slideLayout", "../slideLayouts/slideLayout1.xml")]))
        }
        return zip.finish()
    }
}
