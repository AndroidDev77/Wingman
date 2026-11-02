import Foundation

/// chat.db dates count from 2001-01-01T00:00:00Z (Foundation's reference date): nanoseconds
/// since macOS 10.13, seconds on older databases. 1e11 separates the two safely (spike
/// `timeconv.py`). Port of `apple_to_datetime` / `to_contract_timestamp`.
public enum TimeConv {
    static let nsThreshold: Int64 = 100_000_000_000

    /// `nil` for 0/NULL ("unset").
    public static func appleToDate(_ raw: Int64?) -> Date? {
        guard let raw, raw != 0 else { return nil }
        if abs(raw) >= nsThreshold {
            let (seconds, nanos) = raw.quotientAndRemainder(dividingBy: 1_000_000_000)
            // Truncate to microseconds like the spike, so timestamps match byte for byte.
            return Date(timeIntervalSinceReferenceDate: Double(seconds) + Double(nanos / 1000) / 1_000_000)
        }
        return Date(timeIntervalSinceReferenceDate: Double(raw))
    }

    /// Inverse for nanosecond databases (fixtures).
    public static func dateToAppleNs(_ date: Date) -> Int64 {
        let micros = (date.timeIntervalSinceReferenceDate * 1_000_000).rounded()
        return Int64(micros) * 1000
    }

    /// UTC RFC 3339 with a literal `Z` and at most 6 fractional digits (contract pattern).
    public static func contractTimestamp(_ date: Date) -> String {
        var micros = Int64((date.timeIntervalSince1970 * 1_000_000).rounded())
        var secs = micros / 1_000_000
        micros -= secs * 1_000_000
        if micros < 0 { micros += 1_000_000; secs -= 1 }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second],
                                   from: Date(timeIntervalSince1970: TimeInterval(secs)))
        let base = String(format: "%04d-%02d-%02dT%02d:%02d:%02d",
                          c.year!, c.month!, c.day!, c.hour!, c.minute!, c.second!)
        return micros == 0 ? base + "Z" : base + String(format: ".%06lldZ", micros)
    }

    /// Parses the contract's timestamps (`Z` or an offset, 0 to 6 fractional digits).
    public static func parse(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}
