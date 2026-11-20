import Foundation

/// A minimal ZIP writer (stored, no compression) — all an Office file needs.
/// Word, Excel and PowerPoint files are ZIP archives of XML parts; they accept uncompressed entries.
struct ZipWriter {
    private var data = Data()
    private var central = Data()
    private var count: UInt16 = 0

    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = (c & 1) != 0 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1) }
        return c
    }

    static func crc32(_ bytes: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for b in bytes { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFFFFFF
    }

    private static func le16(_ v: UInt16) -> Data { Data([UInt8(v & 0xFF), UInt8(v >> 8)]) }
    private static func le32(_ v: UInt32) -> Data { Data([UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)]) }

    mutating func add(_ name: String, _ text: String) { add(name, Data(text.utf8)) }

    mutating func add(_ name: String, _ content: Data) {
        let nameBytes = Data(name.utf8)
        let crc = ZipWriter.crc32(content)
        let offset = UInt32(data.count)
        let size = UInt32(content.count)
        // 0x0800: the name is UTF-8. Fixed date (1 Jan 2026) keeps the output reproducible.
        var common = Data()
        common += ZipWriter.le16(20) + ZipWriter.le16(0x0800) + ZipWriter.le16(0)
        common += ZipWriter.le16(0) + ZipWriter.le16(0x5C21)
        common += ZipWriter.le32(crc) + ZipWriter.le32(size) + ZipWriter.le32(size)
        common += ZipWriter.le16(UInt16(nameBytes.count)) + ZipWriter.le16(0)

        data += ZipWriter.le32(0x04034B50) + common + nameBytes + content

        central += ZipWriter.le32(0x02014B50) + ZipWriter.le16(20) + common
        central += ZipWriter.le16(0) + ZipWriter.le16(0) + ZipWriter.le16(0) + ZipWriter.le32(0) + ZipWriter.le32(offset)
        central += nameBytes
        count += 1
    }

    func finish() -> Data {
        var out = data
        let start = UInt32(out.count)
        out += central
        out += ZipWriter.le32(0x06054B50) + ZipWriter.le16(0) + ZipWriter.le16(0) + ZipWriter.le16(count) + ZipWriter.le16(count)
        out += ZipWriter.le32(UInt32(central.count)) + ZipWriter.le32(start) + ZipWriter.le16(0)
        return out
    }
}

/// Pieces every Office file shares.
enum OOXML {
    static let head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    static let relNS = "http://schemas.openxmlformats.org/package/2006/relationships"
    static let od = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    static let coreRel = "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties"
    static let coreType = "application/vnd.openxmlformats-package.core-properties+xml"

    static func rels(_ items: [(String, String)]) -> String {
        head + "<Relationships xmlns=\"\(relNS)\">" + items.enumerated().map { i, it in
            "<Relationship Id=\"rId\(i + 1)\" Type=\"\(it.0)\" Target=\"\(it.1)\"/>"
        }.joined() + "</Relationships>"
    }

    static func core(_ title: String) -> String {
        head + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
            "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>\(TextTools.xml(title))</dc:title><dc:creator>Wakeel</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">2026-01-01T00:00:00Z</dcterms:created></cp:coreProperties>"
    }

    static func types(_ overrides: [(String, String)]) -> String {
        head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            overrides.map { "<Override PartName=\"\($0.0)\" ContentType=\"\($0.1)\"/>" }.joined() + "</Types>"
    }

    /// A cell or list item as text, whatever JSON type the model used for it.
    static func text(_ v: Any?) -> String {
        guard let v = v, !(v is NSNull) else { return "" }
        if let s = v as? String { return s }
        if let n = v as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "نعم" : "لا" }
            let d = n.doubleValue
            return d == d.rounded() && abs(d) < 1e15 ? String(Int64(d)) : String(d)
        }
        return "\(v)"
    }

    static func strings(_ v: Any?) -> [String] { (v as? [Any])?.map { text($0) } ?? [] }
    static func rows(_ v: Any?) -> [[Any]] { (v as? [Any])?.map { ($0 as? [Any]) ?? [$0] } ?? [] }
}
