import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

/// Durable companion state (port of `state.py`): ROWID cursor, dedup set, the bounded
/// envelope queue, the send ledger, pairing and the local switches. One JSON file, mode
/// 0600, replaced atomically (temp file, fsync, rename). Cursor, dedup marks and queued
/// envelopes change in ONE replace, so a crash leaves the old or the new state: rows are
/// re-read into byte-identical envelopes (same `event_id`), never lost or duplicated.
///
/// It holds message text while envelopes wait for upload. It never holds a token: tokens
/// live in the Keychain (`SecretStore`). Location on the Mac:
/// `~/Library/Application Support/ai.wingman.companion/companion-state.json`.
public struct CompanionState: Codable, Sendable, Equatable {
    public static let version = 1
    public static let dedupRetention: TimeInterval = 14 * 24 * 3600
    /// Upper bound on queued envelopes. A full queue stops the inbound poller (the cursor
    /// does not move), so nothing is dropped; it resumes once uploads drain the queue.
    public static let maxQueuedInbound = 1000

    public var version = CompanionState.version
    public var cursor: Int64?  // nil = never baselined
    public var baselineAt: Date?  // nothing older is ever emitted
    public var seen: [String: Date] = [:]  // external_message_id -> first seen
    public var queue: [JSONValue] = []  // envelopes, upload order
    public var ledger: [String: SendRecord] = [:]  // idempotency_key -> record
    public var deviceInstallId: String?
    public var pairing: Pairing?
    public var settings = Settings()
    public var health = Health()
    public var counters: [String: Int] = [:]

    public init() {}

    public struct Pairing: Codable, Sendable, Equatable {
        /// Persisted BEFORE the request and replayed byte-for-byte after a lost response.
        public var idempotencyKey: String
        public var requestBody: JSONValue
        public var connectionId: String?
        public var userId: String?
        public var pairedAt: Date?
        public init(idempotencyKey: String, requestBody: JSONValue) {
            self.idempotencyKey = idempotencyKey
            self.requestBody = requestBody
        }
    }

    public struct Settings: Codable, Sendable, Equatable {
        /// The user's enable/pause switch. Off: nothing is read, uploaded or claimed.
        public var enabled = true
        /// Local kill switch: stops claims (and so every send) immediately, even while the
        /// backend still holds instructions. Reading and uploading continue.
        public var sendsStopped = false
        /// Draft-only mode: send is claimed as UNSUPPORTED; the backend makes assisted
        /// deliveries and the user sends by hand.
        public var draftOnly = false
        public var groupPolicy: GroupPolicy = .notice
        public init() {}
    }

    /// What the menu shows; never message data.
    public struct Health: Codable, Sendable, Equatable {
        public var fullDiskAccess: Bool?
        public var automationGranted: Bool?
        public var lastPollAt: Date?
        public var lastUploadAt: Date?
        public var lastError: String?
        public init() {}
    }

    public enum Phase: String, Codable, Sendable { case claimed, scriptOk = "script_ok", acknowledged, failed }

    public struct SendRecord: Codable, Sendable, Equatable {
        public var outboundMessageId: String
        public var connectionId: String
        public var chatGuid: String
        public var textLength: Int  // never the text
        public var claimedAt: Date
        public var phase: Phase
        public var transportCalled: Bool
        public var rowidMark: Int64?
        public var scriptOkAt: Date?
        public var sentGuid: String?
        public var sentRowid: Int64?
        public var delivered = false
    }

    public var queuedInboundCount: Int {
        queue.filter { $0["type"]?.stringValue == "message.received" }.count
    }

    public func isQueued(eventId: String) -> Bool {
        queue.contains { $0["event_id"]?.stringValue == eventId }
    }

    public mutating func enqueue(_ envelope: JSONValue) {
        guard let id = envelope["event_id"]?.stringValue, !isQueued(eventId: id) else { return }
        queue.append(envelope)
    }

    public mutating func bump(_ name: String, by n: Int = 1) { counters[name, default: 0] += n }

    public mutating func prune(now: Date) {
        let horizon = now.addingTimeInterval(-Self.dedupRetention)
        seen = seen.filter { $0.value >= horizon }
        ledger = ledger.filter { _, r in
            (r.phase != .failed && !(r.phase == .acknowledged && r.delivered)) || r.claimedAt >= horizon
        }
    }
}

public enum StateError: Error, Equatable, Sendable {
    case unsupportedVersion
    case io(String)
}

/// Loads and atomically saves `CompanionState`.
public final class StateStore: @unchecked Sendable {
    public let url: URL
    private let lock = NSLock()

    public init(url: URL) { self.url = url }

    public static func defaultURL(bundleId: String = "ai.wingman.companion") -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support")
        return base.appendingPathComponent(bundleId).appendingPathComponent("companion-state.json")
    }

    public func load() throws -> CompanionState {
        lock.lock(); defer { lock.unlock() }
        guard let data = FileManager.default.contents(atPath: url.path) else { return CompanionState() }
        let state = try JSONDecoder().decode(CompanionState.self, from: data)
        guard state.version == CompanionState.version else { throw StateError.unsupportedVersion }
        return state
    }

    public func save(_ state: CompanionState) throws {
        lock.lock(); defer { lock.unlock() }
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        let data = try enc.encode(state)
        let dir = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let tmp = dir.appendingPathComponent(".state-\(UUID().uuidString)")
        let fd = open(tmp.path, O_WRONLY | O_CREAT | O_EXCL, 0o600)
        guard fd >= 0 else { throw StateError.io("create temp failed") }
        var ok = false
        defer {
            if !ok { unlink(tmp.path) }
        }
        let written = data.withUnsafeBytes { buf -> Int in
            var off = 0
            while off < buf.count {
                let n = write(fd, buf.baseAddress! + off, buf.count - off)
                if n <= 0 { return -1 }
                off += n
            }
            return off
        }
        let synced = fsync(fd) == 0
        close(fd)
        guard written == data.count, synced else { throw StateError.io("write failed") }
        guard rename(tmp.path, url.path) == 0 else { throw StateError.io("rename failed") }
        ok = true
    }

    /// Disconnect: remove every local Wingman file (state, queue, ledger, dedup set).
    public func wipe() throws {
        lock.lock(); defer { lock.unlock() }
        if FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }
}
