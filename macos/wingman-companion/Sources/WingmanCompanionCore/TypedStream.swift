import Foundation

/// Best-effort plain-text recovery from `message.attributedBody` (an archived
/// `NSMutableAttributedString` in the legacy NeXT typedstream format). Clean-room port of
/// the spike's `typedstream.py`, which follows the community-published layout:
///
///     ... "NSString" <class version> ... 0x2B ('+') <length> <UTF-8 bytes> ...
///
/// with `<length>` one byte (< 0x80), `0x81` + UInt16 LE, or `0x82` + UInt32 LE.
/// Only the first NSString (the message text) is taken. VERIFIED against synthetic blobs;
/// decoding REAL Messages blobs is UNVERIFIED (manual M3). It never throws: a reader must
/// keep going past one bad row, and an undecodable body is skipped as `no_content`.
public enum TypedStream {
    public static let header: [UInt8] = Array("\u{04}\u{0B}streamtyped".utf8)
    static let nsString: [UInt8] = Array("NSString".utf8)
    static let plus: UInt8 = 0x2B
    static let maxScanAfterClass = 24
    static let maxTextBytes = 1_000_000

    public static func decodeAttributedBody(_ blob: [UInt8]?) -> String? {
        guard let blob, !blob.isEmpty, blob.starts(with: header) else { return nil }
        guard let cls = find(nsString, in: blob, from: header.count) else { return nil }
        let start = cls + nsString.count
        let windowEnd = min(start + maxScanAfterClass, blob.count)
        guard start < windowEnd, let plusAt = blob[start..<windowEnd].firstIndex(of: plus) else { return nil }
        var pos = plusAt + 1
        guard pos < blob.count else { return nil }
        let tag = blob[pos]
        let length: Int
        switch tag {
        case 0x81:
            guard pos + 3 <= blob.count else { return nil }
            length = Int(blob[pos + 1]) | Int(blob[pos + 2]) << 8
            pos += 3
        case 0x82:
            guard pos + 5 <= blob.count else { return nil }
            length = Int(blob[pos + 1]) | Int(blob[pos + 2]) << 8 | Int(blob[pos + 3]) << 16 | Int(blob[pos + 4]) << 24
            pos += 5
        case 0x80...:
            return nil
        default:
            length = Int(tag)
            pos += 1
        }
        guard length >= 0, length <= maxTextBytes, pos + length <= blob.count else { return nil }
        // Strict UTF-8: invalid bytes mean "not decodable", never a garbled message.
        return String(bytes: blob[pos..<(pos + length)], encoding: .utf8)
    }

    static func find(_ needle: [UInt8], in hay: [UInt8], from: Int) -> Int? {
        guard needle.count <= hay.count else { return nil }
        var i = from
        while i + needle.count <= hay.count {
            if hay[i] == needle[0] && Array(hay[i..<(i + needle.count)]) == needle { return i }
            i += 1
        }
        return nil
    }

    /// A SYNTHETIC blob with the documented layout (tests only); same bytes as the spike's
    /// `encode_attributed_body_for_fixture`.
    public static func encodeForFixture(_ text: String) -> [UInt8] {
        let data = Array(text.utf8)
        var length: [UInt8]
        if data.count < 0x80 {
            length = [UInt8(data.count)]
        } else if data.count <= 0xFFFF {
            length = [0x81, UInt8(data.count & 0xff), UInt8(data.count >> 8)]
        } else {
            length = [0x82] + (0..<4).map { UInt8((data.count >> ($0 * 8)) & 0xff) }
        }
        var prefix = header
        prefix += [0x81, 0xe8, 0x03, 0x84, 0x01, 0x40, 0x84, 0x84, 0x84, 0x12] + Array("NSAttributedString".utf8) + [0x00]
        prefix += [0x84, 0x84, 0x08] + Array("NSObject".utf8) + [0x00, 0x85, 0x92, 0x84, 0x84, 0x84, 0x08]
        prefix += Array("NSString".utf8) + [0x01, 0x94, 0x84, 0x01, 0x2B]
        var suffix: [UInt8] = [0x86, 0x84, 0x02, 0x69, 0x49, 0x01, 0x05, 0x92, 0x84, 0x84, 0x84, 0x0c]
        suffix += Array("NSDictionary".utf8) + [0x00, 0x94, 0x84, 0x01, 0x69, 0x01, 0x92, 0x84, 0x96, 0x96, 0x1d]
        suffix += Array("__kIMMessagePartAttributeName".utf8) + [0x86, 0x92, 0x84, 0x84, 0x84, 0x08]
        suffix += Array("NSNumber".utf8) + [0x00, 0x84, 0x84, 0x07] + Array("NSValue".utf8)
        suffix += [0x00, 0x94, 0x84, 0x01, 0x2A, 0x84, 0x99, 0x99, 0x00, 0x86, 0x86, 0x86]
        return prefix + length + data + suffix
    }
}
