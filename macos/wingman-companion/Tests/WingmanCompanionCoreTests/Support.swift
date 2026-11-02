import CSQLite
import Foundation
import XCTest
@testable import WingmanCompanionCore

/// SYNTHETIC chat.db-shaped fixture (Swift port of the spike's `tests/fixture_db.py`): the
/// community-documented schema, reserved test numbers (+1 555 01xx) and example.com. It
/// stands in for Messages and is the only writer; the code under test opens it read-only.
let fixtureSchema = """
    CREATE TABLE handle (
        ROWID INTEGER PRIMARY KEY AUTOINCREMENT UNIQUE, id TEXT NOT NULL, country TEXT,
        service TEXT NOT NULL, uncanonicalized_id TEXT, person_centric_id TEXT, UNIQUE (id, service));
    CREATE TABLE chat (
        ROWID INTEGER PRIMARY KEY AUTOINCREMENT, guid TEXT UNIQUE NOT NULL, style INTEGER,
        state INTEGER, account_id TEXT, chat_identifier TEXT, service_name TEXT,
        room_name TEXT, display_name TEXT, is_archived INTEGER DEFAULT 0);
    CREATE TABLE message (
        ROWID INTEGER PRIMARY KEY AUTOINCREMENT, guid TEXT UNIQUE NOT NULL, text TEXT,
        handle_id INTEGER DEFAULT 0, service TEXT, date INTEGER, date_read INTEGER,
        date_delivered INTEGER, is_delivered INTEGER DEFAULT 0, is_from_me INTEGER DEFAULT 0,
        is_sent INTEGER DEFAULT 0, is_read INTEGER DEFAULT 0, error INTEGER DEFAULT 0,
        item_type INTEGER DEFAULT 0, cache_has_attachments INTEGER DEFAULT 0,
        cache_roomnames TEXT, associated_message_guid TEXT, associated_message_type INTEGER DEFAULT 0,
        attributedBody BLOB, date_edited INTEGER DEFAULT 0, date_retracted INTEGER DEFAULT 0,
        thread_originator_guid TEXT);
    CREATE TABLE chat_message_join (chat_id INTEGER, message_id INTEGER, message_date INTEGER DEFAULT 0,
        PRIMARY KEY (chat_id, message_id));
    CREATE TABLE chat_handle_join (chat_id INTEGER, handle_id INTEGER, UNIQUE (chat_id, handle_id));
    CREATE TABLE attachment (ROWID INTEGER PRIMARY KEY AUTOINCREMENT, guid TEXT UNIQUE NOT NULL,
        filename TEXT, mime_type TEXT, transfer_state INTEGER DEFAULT 0, total_bytes INTEGER DEFAULT 0);
    CREATE TABLE message_attachment_join (message_id INTEGER, attachment_id INTEGER, UNIQUE (message_id, attachment_id));
    """

let T0 = Date(timeIntervalSince1970: 1_793_383_200)  // 2026-10-30T18:00:00Z
func at(_ minutes: Double) -> Date { T0.addingTimeInterval(minutes * 60) }

let ALICE = "+15550100001"
let BOB = "bob@example.com"
let CARA = "+15550100003"
let DAN_SMS = "+15550100004"
let DM = "iMessage;-;\(ALICE)"
let GROUP = "iMessage;+;chat123456789012345"
let SMS_CHAT = "SMS;-;\(DAN_SMS)"
let USER_ID = "0192f3a0-0000-7000-8000-000000000001"
let CONNECTION_ID = "0192f3a0-0000-7000-8000-0000000000c1"
let SCOPE = Scope(userId: USER_ID, connectionId: CONNECTION_ID)

private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

enum SQLValue { case int(Int64), text(String), blob([UInt8]), null }

final class FixtureDb: @unchecked Sendable {
    let path: URL
    private var db: OpaquePointer?
    private var n = 0
    private(set) var handles: [String: Int64] = [:]
    private(set) var chats: [String: Int64] = [:]

    init(path: URL, wal: Bool = true, schema: String = fixtureSchema) {
        self.path = path
        sqlite3_open(path.path, &db)
        if wal { exec("PRAGMA journal_mode=WAL") }
        // Keep everything in the WAL: no automatic checkpoint, like a busy Messages.
        exec("PRAGMA wal_autocheckpoint=0")
        exec(schema)
    }

    deinit { close() }
    func close() {
        if let db { sqlite3_close_v2(db) }
        db = nil
    }

    func exec(_ sql: String) {
        var err: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &err) != SQLITE_OK {
            fatalError("fixture SQL failed: \(String(cString: err!))")
        }
    }

    @discardableResult
    func run(_ sql: String, _ args: [SQLValue] = []) -> Int64 {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else {
            fatalError("prepare: \(String(cString: sqlite3_errmsg(db)))")
        }
        defer { sqlite3_finalize(stmt) }
        for (i, a) in args.enumerated() {
            let idx = Int32(i + 1)
            switch a {
            case .int(let v): sqlite3_bind_int64(stmt, idx, v)
            case .text(let s): sqlite3_bind_text(stmt, idx, s, -1, transient)
            case .blob(let b): b.withUnsafeBytes { _ = sqlite3_bind_blob(stmt, idx, $0.baseAddress, Int32(b.count), transient) }
            case .null: sqlite3_bind_null(stmt, idx)
            }
        }
        guard sqlite3_step(stmt) == SQLITE_DONE else { fatalError("step: \(String(cString: sqlite3_errmsg(db)))") }
        return sqlite3_last_insert_rowid(db)
    }

    func handle(_ address: String, service: String = "iMessage") -> Int64 {
        if let h = handles[address] { return h }
        let id = run("INSERT INTO handle (id, service) VALUES (?, ?)", [.text(address), .text(service)])
        handles[address] = id
        return id
    }

    @discardableResult
    func chat(_ guid: String, members: [String], style: Int64, displayName: String? = nil) -> Int64 {
        let parts = guid.split(separator: ";", maxSplits: 2).map(String.init)
        let cid = run("INSERT INTO chat (guid, style, chat_identifier, service_name, display_name) VALUES (?,?,?,?,?)",
                      [.text(guid), .int(style), .text(parts[2]), .text(parts[0]), displayName.map { .text($0) } ?? .null])
        for m in members {
            run("INSERT INTO chat_handle_join (chat_id, handle_id) VALUES (?, ?)", [.int(cid), .int(handle(m, service: parts[0]))])
        }
        chats[guid] = cid
        return cid
    }

    @discardableResult
    func message(_ chatGuid: String?, sender: String?, text: String?, at date: Date, fromMe: Bool = false,
                 attributed: Bool = false, service: String = "iMessage", associatedType: Int64 = 0,
                 itemType: Int64 = 0, attachments: Int = 0, guid: String? = nil, join: Bool = true,
                 error: Int64 = 0, delivered: Bool = false, retracted: Bool = false, edited: Bool = false,
                 rawAttributed: [UInt8]? = nil) -> (Int64, String) {
        n += 1
        let g = guid ?? String(format: "FIXTURE-%04d-0000-0000-0000-000000000000", n)
        var body: SQLValue = rawAttributed.map { .blob($0) } ?? .null
        if attributed, let text { body = .blob(TypedStream.encodeForFixture(text)) }
        let stored: SQLValue = attributed ? .null : (text.map { .text($0) } ?? .null)
        let hid = sender.map { handle($0, service: service) } ?? 0
        let ns = TimeConv.dateToAppleNs(date)
        let rowid = run("""
            INSERT INTO message (guid, text, handle_id, service, date, is_from_me, is_sent, associated_message_type,
              item_type, attributedBody, cache_has_attachments, error, is_delivered, date_retracted, date_edited)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, [.text(g), stored, .int(hid), .text(service), .int(ns), .int(fromMe ? 1 : 0), .int(fromMe ? 1 : 0),
                  .int(associatedType), .int(itemType), body, .int(attachments > 0 ? 1 : 0), .int(error),
                  .int(delivered ? 1 : 0), .int(retracted ? ns : 0), .int(edited ? ns : 0)])
        if let chatGuid, join { self.join(chatGuid, rowid) }
        for i in 0..<attachments {
            let a = run("INSERT INTO attachment (guid, filename, mime_type) VALUES (?,?,?)",
                        [.text("ATT-\(rowid)-\(i)"), .text("~/Library/Messages/Attachments/x/\(rowid)-\(i).jpg"), .text("image/jpeg")])
            run("INSERT INTO message_attachment_join (message_id, attachment_id) VALUES (?,?)", [.int(rowid), .int(a)])
        }
        return (rowid, g)
    }

    func join(_ chatGuid: String, _ rowid: Int64) {
        run("INSERT INTO chat_message_join (chat_id, message_id) VALUES (?, ?)", [.int(chats[chatGuid]!), .int(rowid)])
    }

    func markDelivered(_ rowid: Int64) { run("UPDATE message SET is_delivered = 1 WHERE ROWID = ?", [.int(rowid)]) }
    func delete(_ rowid: Int64) { run("DELETE FROM message WHERE ROWID = ?", [.int(rowid)]) }
}

func standardFixture(_ dir: URL) -> FixtureDb {
    let db = FixtureDb(path: dir.appendingPathComponent("chat.db"))
    db.chat(DM, members: [ALICE], style: 45)
    db.chat("iMessage;-;\(BOB)", members: [BOB], style: 45)
    db.chat(GROUP, members: [ALICE, CARA], style: 43, displayName: "Weekend plans")
    db.chat(SMS_CHAT, members: [DAN_SMS], style: 45)
    return db
}

final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var _now: Date
    init(_ start: Date = at(0)) { _now = start }
    var now: Date {
        get { lock.lock(); defer { lock.unlock() }; return _now }
        set { lock.lock(); _now = newValue; lock.unlock() }
    }
    func advance(_ s: TimeInterval) { now = now.addingTimeInterval(s) }
    var fn: @Sendable () -> Date { { [self] in self.now } }
}

/// Fresh temp dir per test.
class CompanionTestCase: XCTestCase {
    var dir: URL!
    var fixture: FixtureDb!
    var store: StateStore!
    var reader: ChatDbReader!
    var clock: TestClock!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("wing030-\(UUID().uuidString)")
        try! FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fixture = standardFixture(dir)
        store = StateStore(url: dir.appendingPathComponent("state/companion-state.json"))
        reader = ChatDbReader(path: fixture.path)
        clock = TestClock()
    }

    override func tearDown() {
        reader?.close()
        fixture?.close()
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    func poller(batch: Int = 200, maxQueued: Int = CompanionState.maxQueuedInbound) -> InboundPoller {
        InboundPoller(reader: reader, store: store, scope: SCOPE, batchSize: batch, maxQueued: maxQueued, clock: clock.fn)
    }

    /// A state that is paired, so the engine/poller have a scope.
    func savePaired(_ mutate: (inout CompanionState) -> Void = { _ in }) {
        var s = (try? store.load()) ?? CompanionState()
        var p = CompanionState.Pairing(idempotencyKey: "pair-key", requestBody: [:])
        p.connectionId = CONNECTION_ID
        p.userId = USER_ID
        p.pairedAt = clock.now
        s.pairing = p
        s.deviceInstallId = "00000000-0000-4000-8000-0000000000aa"
        mutate(&s)
        try! store.save(s)
    }
}

/// Stands in for Messages: records commands and, like Messages would, writes the sent row.
final class FakeMessages: MessagesTransport, @unchecked Sendable {
    let db: FixtureDb
    let clock: TestClock
    var writeRow = true
    var result = ExecResult(exitCode: 0)
    var rowError: Int64 = 0
    var onSend: ((AppleScriptCommand.SendCommand) throws -> Void)?
    private(set) var calls: [AppleScriptCommand.SendCommand] = []

    init(_ db: FixtureDb, _ clock: TestClock) {
        self.db = db
        self.clock = clock
    }

    func send(_ command: AppleScriptCommand.SendCommand) async throws -> ExecResult {
        calls.append(command)
        try onSend?(command)
        if result.exitCode == 0 && writeRow {
            db.message(command.chatGuid, sender: nil, text: command.text, at: clock.now, fromMe: true, error: rowError)
        }
        return result
    }
}

/// In-memory Keychain stand-in.
final class MemorySecrets: SecretStore, @unchecked Sendable {
    var values: [String: String] = [:]
    var locked = false
    func read(_ a: String) throws -> String? {
        if locked { throw AuthError.keychainLocked }
        return values[a]
    }
    func write(_ a: String, _ v: String) throws {
        if locked { throw AuthError.keychainLocked }
        values[a] = v
    }
    func delete(_ a: String) throws { values[a] = nil }
}

struct StaticToken: AccessTokenProvider {
    var token = "test-access-token"
    func accessToken() async throws -> String { token }
    func invalidate() async {}
}

/// A scripted backend: records requests and answers through a handler.
final class FakeHTTP: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var requests: [HTTPRequest] = []
    var handler: (HTTPRequest) throws -> HTTPResponse

    init(_ handler: @escaping (HTTPRequest) throws -> HTTPResponse) { self.handler = handler }

    func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        lock.withLock { requests.append(request) }
        return try handler(request)
    }

    func paths(_ method: String? = nil) -> [String] {
        requests.filter { method == nil || $0.method == method }.map { $0.url.path }
    }
}

struct Offline: Error {}

func json(_ status: Int, _ body: JSONValue) -> HTTPResponse { HTTPResponse(status: status, body: try! body.encoded()) }
func bodyJSON(_ r: HTTPRequest) -> JSONValue? { r.body.flatMap { try? JSONValue.decode($0) } }

func instruction(key: String = "send:0192f3a0-0000-7000-8000-00000000aa01:1", text: String = "Yes! 8pm?",
                 thread: String = "imessage:chat:\(DM)", connection: String = CONNECTION_ID, state: String = "leased",
                 outbound: String = "0192f3a0-0000-7000-8000-00000000aa01",
                 leaseExpires: String = "2026-10-30T18:02:00Z") -> OutboxInstruction {
    OutboxInstruction(id: "0192f3a0-0000-7000-8000-00000000bb01", connectionId: connection, outboundMessageId: outbound,
                      idempotencyKey: key, attempt: 1, state: state, leaseExpiresAt: leaseExpires,
                      externalThreadId: thread, text: text)
}

let SERVER_TIME = "2026-10-30T18:00:00Z"

func instructionJSON(_ i: OutboxInstruction) -> JSONValue {
    [
        "id": .string(i.id), "connection_id": .string(i.connectionId), "outbound_message_id": .string(i.outboundMessageId),
        "idempotency_key": .string(i.idempotencyKey), "attempt": .int(i.attempt), "state": .string(i.state),
        "created_at": .string(SERVER_TIME), "leased_at": .string(SERVER_TIME), "lease_expires_at": .string(i.leaseExpiresAt),
        "conversation_id": "0192f3a0-0000-7000-8000-00000000cc01", "external_thread_id": .string(i.externalThreadId),
        "text": .string(i.text), "authorization": ["origin": "user_manual", "approval_id": nil],
    ]
}
