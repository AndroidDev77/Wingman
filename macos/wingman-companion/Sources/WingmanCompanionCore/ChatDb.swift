import CSQLite
import Foundation

/// Read-only, incremental reader over `~/Library/Messages/chat.db` (port of `chatdb.py`).
///
/// Rules (docs/integrations/imessage.md §1, kept from WING-020):
/// * **Never write.** Opened with `SQLITE_OPEN_READONLY` and the URI `?mode=ro`. Never
///   `immutable=1`: SQLite would then ignore the `-wal` file and miss the newest messages.
///   No checkpoint, no writing PRAGMA.
/// * **Short read transactions.** Each query is one autocommit statement, finalized
///   before returning, so Messages' checkpoints are never starved.
/// * **Schema drift tolerant.** Optional columns are discovered with `PRAGMA table_info`
///   and read as NULL when missing.
/// * **ROWID cursor.** Edits, unsends and delivery flags UPDATE rows and are not seen.
public enum ChatDbError: Error, Equatable, Sendable {
    /// Missing, no Full Disk Access (macOS reports EPERM / "authorization denied"),
    /// or not a database. The detail never contains a path or message data.
    case unavailable(String)
    case queryFailed(String)
}

public struct MessageRow: Sendable, Equatable {
    public var rowid: Int64
    public var guid: String
    public var text: String?
    public var textSource: String  // "text" | "attributedBody" | "none"
    public var occurredAt: Date?
    public var isFromMe: Bool
    public var senderHandle: String?
    public var service: String?
    public var chatGuid: String?
    public var chatIdentifier: String?
    public var chatDisplayName: String?
    public var chatStyle: Int64?
    public var participantCount: Int
    public var attachmentCount: Int
    public var associatedMessageType: Int64
    public var associatedMessageGuid: String?
    public var itemType: Int64
    public var edited: Bool
    public var retracted: Bool
    public var isSent: Bool?
    public var isDelivered: Bool?
    public var error: Int64

    public var isGroup: Bool { isGroupChat(chatGuid: chatGuid, chatIdentifier: chatIdentifier, participants: participantCount) }

    /// Tapbacks: 2000...2006 add, 3000...3006 remove (older docs: negative values).
    public var isReaction: Bool {
        let t = abs(associatedMessageType)
        return associatedMessageType != 0 && t >= 2000 && t <= 3999
    }

    /// `item_type != 0`: renames, member changes and other system items.
    public var isSystemItem: Bool { itemType != 0 }
}

/// Group detection from structural signals, never from the disputed `chat.style` (43 vs 45).
public func isGroupChat(chatGuid: String?, chatIdentifier: String?, participants: Int) -> Bool {
    if let g = chatGuid, g.contains(";+;") { return true }
    if participants > 1 { return true }
    if let id = chatIdentifier, id.hasPrefix("chat"), !id.contains("@") { return true }
    return false
}

/// The reader. Not thread-safe: one owner (the engine actor) uses it.
public final class ChatDbReader: @unchecked Sendable {
    public let path: URL
    private var db: OpaquePointer?
    private var messageCols: Set<String> = []
    private var chatCols: Set<String> = []

    static let optionalMessageColumns = [
        "attributedBody", "associated_message_type", "associated_message_guid", "date_edited",
        "date_retracted", "item_type", "cache_has_attachments", "is_sent", "is_delivered", "error",
        "service", "thread_originator_guid",
    ]

    public init(path: URL) { self.path = path }
    deinit { close() }

    public static var defaultPath: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Messages/chat.db")
    }

    public func open() throws {
        if db != nil { return }
        guard FileManager.default.fileExists(atPath: path.path) else {
            // Without Full Disk Access macOS may report the file as unreadable rather than
            // missing (UNVERIFIED which). Both are "unavailable".
            throw ChatDbError.unavailable("chat.db not found or not accessible")
        }
        let uri = path.standardizedFileURL.absoluteString + "?mode=ro"
        var handle: OpaquePointer?
        let rc = sqlite3_open_v2(uri, &handle, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil)
        guard rc == SQLITE_OK, let handle else {
            if let handle { sqlite3_close_v2(handle) }
            throw ChatDbError.unavailable("cannot open chat.db (sqlite \(rc))")
        }
        sqlite3_busy_timeout(handle, 2000)
        db = handle
        do {
            _ = try query("SELECT 1 FROM message LIMIT 1") { _ in 0 }
            messageCols = try columns("message")
            chatCols = try columns("chat")
        } catch {
            close()
            throw ChatDbError.unavailable("cannot read chat.db")
        }
    }

    public func close() {
        if let db { sqlite3_close_v2(db) }
        db = nil
    }

    // MARK: queries

    public func maxRowid() throws -> Int64 {
        try open()
        return try query("SELECT COALESCE(MAX(ROWID), 0) FROM message") { $0.int64(0) ?? 0 }.first ?? 0
    }

    /// Messages with ROWID > cursor, ascending, at most `limit`.
    public func readAfter(_ cursor: Int64, limit: Int = 200) throws -> [MessageRow] {
        precondition(limit >= 1, "limit must be >= 1")
        try open()
        return try query(selectSQL() + " WHERE m.ROWID > ? ORDER BY m.ROWID LIMIT ?",
                         bind: [.int(cursor), .int(Int64(limit))], map: Self.toRow)
    }

    public func readRowids(_ rowids: [Int64]) throws -> [MessageRow] {
        if rowids.isEmpty { return [] }
        try open()
        let marks = rowids.map { _ in "?" }.joined(separator: ",")
        return try query(selectSQL() + " WHERE m.ROWID IN (\(marks)) ORDER BY m.ROWID",
                         bind: rowids.map { .int($0) }, map: Self.toRow)
    }

    private func selectSQL() -> String {
        func m(_ c: String) -> String { messageCols.contains(c) ? "m.\(c)" : "NULL" }
        func c(_ col: String) -> String { chatCols.contains(col) ? "c.\(col)" : "NULL" }
        return """
            SELECT m.ROWID AS rowid, m.guid AS guid, m.text AS text,
                   \(m("attributedBody")) AS attributed_body, m.date AS date,
                   m.is_from_me AS is_from_me, \(m("service")) AS service,
                   \(m("associated_message_type")) AS associated_message_type,
                   \(m("associated_message_guid")) AS associated_message_guid,
                   \(m("date_edited")) AS date_edited, \(m("date_retracted")) AS date_retracted,
                   \(m("item_type")) AS item_type, \(m("is_sent")) AS is_sent,
                   \(m("is_delivered")) AS is_delivered, \(m("error")) AS error,
                   h.id AS sender_handle,
                   c.guid AS chat_guid, c.chat_identifier AS chat_identifier,
                   \(c("display_name")) AS chat_display_name, \(c("style")) AS chat_style,
                   (SELECT COUNT(*) FROM chat_handle_join chj WHERE chj.chat_id = c.ROWID) AS participant_count,
                   (SELECT COUNT(*) FROM message_attachment_join maj WHERE maj.message_id = m.ROWID) AS attachment_count
            FROM message m
            LEFT JOIN handle h ON h.ROWID = m.handle_id
            LEFT JOIN chat_message_join cmj ON cmj.message_id = m.ROWID
            LEFT JOIN chat c ON c.ROWID = cmj.chat_id
            """
    }

    private static func toRow(_ r: Row) -> MessageRow {
        var text = r.text("text")
        var source = "text"
        if text == nil || text == "" {
            if let decoded = TypedStream.decodeAttributedBody(r.blob("attributed_body")) {
                text = decoded
                source = "attributedBody"
            } else {
                text = nil
                source = "none"
            }
        }
        return MessageRow(
            rowid: r.int64("rowid") ?? 0,
            guid: r.text("guid") ?? "",
            text: text,
            textSource: source,
            occurredAt: TimeConv.appleToDate(r.int64("date")),
            isFromMe: (r.int64("is_from_me") ?? 0) != 0,
            senderHandle: r.text("sender_handle"),
            service: r.text("service"),
            chatGuid: r.text("chat_guid"),
            chatIdentifier: r.text("chat_identifier"),
            chatDisplayName: r.text("chat_display_name"),
            chatStyle: r.int64("chat_style"),
            participantCount: Int(r.int64("participant_count") ?? 0),
            attachmentCount: Int(r.int64("attachment_count") ?? 0),
            associatedMessageType: r.int64("associated_message_type") ?? 0,
            associatedMessageGuid: r.text("associated_message_guid"),
            itemType: r.int64("item_type") ?? 0,
            edited: (r.int64("date_edited") ?? 0) != 0,
            retracted: (r.int64("date_retracted") ?? 0) != 0,
            isSent: r.int64("is_sent").map { $0 != 0 },
            isDelivered: r.int64("is_delivered").map { $0 != 0 },
            error: r.int64("error") ?? 0
        )
    }

    private func columns(_ table: String) throws -> Set<String> {
        Set(try query("PRAGMA table_info(\(table))") { $0.text(1) }.compactMap { $0 })
    }

    // MARK: SQLite plumbing

    enum Bind { case int(Int64) }

    struct Row {
        let stmt: OpaquePointer
        let names: [String: Int32]
        func idx(_ name: String) -> Int32? { names[name] }
        func isNull(_ i: Int32) -> Bool { sqlite3_column_type(stmt, i) == SQLITE_NULL }
        func int64(_ i: Int32) -> Int64? { isNull(i) ? nil : sqlite3_column_int64(stmt, i) }
        func text(_ i: Int32) -> String? {
            guard !isNull(i), let p = sqlite3_column_text(stmt, i) else { return nil }
            return String(cString: p)
        }
        func int64(_ n: String) -> Int64? { idx(n).flatMap { int64($0) } }
        func text(_ n: String) -> String? { idx(n).flatMap { text($0) } }
        func blob(_ n: String) -> [UInt8]? {
            guard let i = idx(n), !isNull(i) else { return nil }
            let len = Int(sqlite3_column_bytes(stmt, i))
            guard len > 0, let p = sqlite3_column_blob(stmt, i) else { return [] }
            return Array(UnsafeRawBufferPointer(start: p, count: len))
        }
    }

    private func query<T>(_ sql: String, bind: [Bind] = [], map: (Row) -> T) throws -> [T] {
        guard let db else { throw ChatDbError.unavailable("not open") }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw ChatDbError.queryFailed("prepare failed (sqlite \(sqlite3_errcode(db)))")
        }
        defer { sqlite3_finalize(stmt) }  // ends the implicit read transaction
        for (i, b) in bind.enumerated() {
            switch b {
            case .int(let v): sqlite3_bind_int64(stmt, Int32(i + 1), v)
            }
        }
        var names: [String: Int32] = [:]
        for i in 0..<sqlite3_column_count(stmt) {
            if let n = sqlite3_column_name(stmt, i) { names[String(cString: n)] = i }
        }
        var out: [T] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW {
                out.append(map(Row(stmt: stmt, names: names)))
            } else if rc == SQLITE_DONE {
                break
            } else {
                throw ChatDbError.queryFailed("step failed (sqlite \(rc))")
            }
        }
        return out
    }
}
