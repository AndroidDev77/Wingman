import CSQLite
import Foundation
import XCTest
@testable import WingmanCompanionCore

final class ChatDbReaderTests: CompanionTestCase {
    func testReadsRowsStillInTheWALWithoutWriting() throws {
        fixture.message(DM, sender: ALICE, text: "in the wal", at: at(1))
        let wal = fixture.path.path + "-wal"
        XCTAssertTrue(FileManager.default.fileExists(atPath: wal))
        let walBefore = FileManager.default.contents(atPath: wal)
        let dbBefore = FileManager.default.contents(atPath: fixture.path.path)
        let rows = try reader.readAfter(0)
        XCTAssertEqual(rows.map(\.text), ["in the wal"])
        reader.close()
        XCTAssertEqual(FileManager.default.contents(atPath: fixture.path.path), dbBefore)
        XCTAssertEqual(FileManager.default.contents(atPath: wal), walBefore)
    }

    func testReaderConnectionIsReadOnly() throws {
        try reader.open()
        // The reader exposes no write path; prove the handle itself refuses writes.
        var db: OpaquePointer?
        let uri = fixture.path.standardizedFileURL.absoluteString + "?mode=ro"
        XCTAssertEqual(sqlite3_open_v2(uri, &db, SQLITE_OPEN_READONLY | SQLITE_OPEN_URI, nil), SQLITE_OK)
        XCTAssertEqual(sqlite3_exec(db, "DELETE FROM message", nil, nil, nil), SQLITE_READONLY)
        sqlite3_close_v2(db)
    }

    func testMissingFileIsUnavailable() {
        let r = ChatDbReader(path: dir.appendingPathComponent("nope/chat.db"))
        XCTAssertThrowsError(try r.maxRowid()) { XCTAssertEqual($0 as? ChatDbError, .unavailable("chat.db not found or not accessible")) }
    }

    func testNotADatabaseIsUnavailable() throws {
        let p = dir.appendingPathComponent("junk.db")
        try Data("garbage".utf8).write(to: p)
        XCTAssertThrowsError(try ChatDbReader(path: p).maxRowid())
    }

    func testAttributedBodyFallbackAndGroups() throws {
        fixture.message(DM, sender: ALICE, text: "from blob", at: at(1), attributed: true)
        fixture.message(GROUP, sender: CARA, text: "group hi", at: at(2))
        let rows = try reader.readAfter(0)
        XCTAssertEqual(rows[0].text, "from blob")
        XCTAssertEqual(rows[0].textSource, "attributedBody")
        XCTAssertFalse(rows[0].isGroup)
        XCTAssertTrue(rows[1].isGroup)
        XCTAssertEqual(rows[1].participantCount, 2)
    }

    func testGroupSignals() {
        XCTAssertTrue(isGroupChat(chatGuid: GROUP, chatIdentifier: nil, participants: 0))
        XCTAssertTrue(isGroupChat(chatGuid: nil, chatIdentifier: "chat99", participants: 0))
        XCTAssertTrue(isGroupChat(chatGuid: "any;-;x", chatIdentifier: nil, participants: 3))
        XCTAssertFalse(isGroupChat(chatGuid: DM, chatIdentifier: ALICE, participants: 1))
        XCTAssertFalse(isGroupChat(chatGuid: nil, chatIdentifier: "chatty@example.com", participants: 1))
    }

    func testMissingOptionalColumnsReadAsNull() throws {
        let p = dir.appendingPathComponent("old.db")
        let old = FixtureDb(path: p, schema: """
            CREATE TABLE handle (ROWID INTEGER PRIMARY KEY, id TEXT, service TEXT);
            CREATE TABLE chat (ROWID INTEGER PRIMARY KEY, guid TEXT, chat_identifier TEXT);
            CREATE TABLE message (ROWID INTEGER PRIMARY KEY, guid TEXT, text TEXT, handle_id INTEGER, date INTEGER, is_from_me INTEGER);
            CREATE TABLE chat_message_join (chat_id INTEGER, message_id INTEGER);
            CREATE TABLE chat_handle_join (chat_id INTEGER, handle_id INTEGER);
            CREATE TABLE message_attachment_join (message_id INTEGER, attachment_id INTEGER);
            INSERT INTO message VALUES (1, 'G1', 'old school', 0, 600000000, 0);
            """)
        defer { old.close() }
        let rows = try ChatDbReader(path: p).readAfter(0)
        XCTAssertEqual(rows.count, 1)
        XCTAssertNil(rows[0].service)
        XCTAssertEqual(rows[0].associatedMessageType, 0)
        XCTAssertNil(rows[0].isDelivered)
        XCTAssertNotNil(rows[0].occurredAt)  // seconds-based date
    }
}

final class EnvelopeTests: CompanionTestCase {
    func row(_ chat: String, sender: String = ALICE, text: String? = "hi", attachments: Int = 0) throws -> MessageRow {
        fixture.message(chat, sender: sender, text: text, at: at(1), attachments: attachments)
        return try reader.readAfter(0).last!
    }

    func testOneToOneIsLinkedByChatGuid() throws {
        let env = Envelope.received(try row(DM, text: "Free tonight?\u{FFFC}"), scope: SCOPE, observedAt: at(2))!
        XCTAssertEqual(env["type"], "message.received")
        XCTAssertEqual(env["platform"], "imessage")
        XCTAssertEqual(env["external_thread_id"], .string("imessage:chat:\(DM)"))
        XCTAssertEqual(env["external_message_id"], "imessage:FIXTURE-0001-0000-0000-0000-000000000000")
        XCTAssertEqual(env["event_id"], "435691a1-49a0-50d8-b577-08f390890632")  // = spike
        let p = env["payload"]!
        XCTAssertEqual(p["identity"], "linked")
        XCTAssertEqual(p["source"], "companion")
        XCTAssertEqual(p["kind"], "chat_message")
        XCTAssertEqual(p["text"], "Free tonight?")
        XCTAssertEqual(p["sender"]?["external_contact_id"], .string("imessage:\(ALICE)"))
        XCTAssertEqual(p["sender"]?["display_name"], .null)
    }

    func testContactComesFromTheChatNotAnAliasHandle() throws {
        // Alice writes from an e-mail alias into the chat keyed by her number.
        let env = Envelope.received(try row(DM, sender: "Alice@Example.com"), scope: SCOPE, observedAt: at(2))!
        XCTAssertEqual(env["payload"]?["sender"]?["external_contact_id"], .string("imessage:\(ALICE)"))
    }

    func testGroupIsAmbiguousNoticeOrSkipped() throws {
        let r = try row(GROUP, sender: CARA)
        let env = Envelope.received(r, scope: SCOPE, observedAt: at(2), groupPolicy: .notice)!
        XCTAssertEqual(env["payload"]?["identity"], "ambiguous")
        XCTAssertEqual(env["external_thread_id"], .string("imessage:chat:\(GROUP)"))
        XCTAssertEqual(env["payload"]?["sender"]?["external_contact_id"], .string("imessage:\(CARA)"))
        XCTAssertNil(Envelope.received(r, scope: SCOPE, observedAt: at(2), groupPolicy: .skip))
    }

    func testAttachmentOnlyAndTransport() throws {
        let env = Envelope.received(try row(SMS_CHAT, sender: DAN_SMS, text: "\u{FFFC}", attachments: 2),
                                    scope: SCOPE, observedAt: at(2))!
        XCTAssertEqual(env["payload"]?["attachment_count"], 2)
        XCTAssertEqual(env["payload"]?["text"], "")
        XCTAssertEqual(Envelope.transport(chatGuid: SMS_CHAT, service: nil), "sms")
        XCTAssertEqual(Envelope.transport(chatGuid: "any;-;x", service: "iMessage"), "imessage")
        XCTAssertEqual(Envelope.transport(chatGuid: "any;-;x", service: nil), "unknown")
    }

    func testClassifySkipsWhatIsNotAnInboundMessage() throws {
        fixture.message(DM, sender: nil, text: "mine", at: at(1), fromMe: true)
        fixture.message(DM, sender: ALICE, text: "Loved “hi”", at: at(1), associatedType: 2000)
        fixture.message(DM, sender: ALICE, text: nil, at: at(1), itemType: 1)
        fixture.message(DM, sender: ALICE, text: "oops", at: at(1), retracted: true)
        fixture.message(DM, sender: ALICE, text: "   ", at: at(1))
        fixture.message(DM, sender: ALICE, text: "old", at: at(-10))
        fixture.message(nil, sender: ALICE, text: "orphan", at: at(1))
        fixture.message(DM, sender: ALICE, text: "edited later", at: at(1), edited: true)
        let reasons = try reader.readAfter(0).map { Envelope.classify($0, baseline: at(0))?.rawValue ?? "ok" }
        XCTAssertEqual(reasons, ["from_me", "reaction", "system_item", "retracted", "no_content", "before_baseline",
                                 "no_chat", "ok"])
    }
}

final class InboundPollerTests: CompanionTestCase {
    func testFirstPollBaselinesAndEmitsNoHistory() throws {
        fixture.message(DM, sender: ALICE, text: "history", at: at(-5))
        let (s, r) = try poller().poll()
        XCTAssertTrue(r.baselined)
        XCTAssertEqual(s.cursor, 1)
        XCTAssertTrue(s.queue.isEmpty)
        fixture.message(DM, sender: ALICE, text: "new", at: at(1))
        clock.advance(120)
        let (s2, r2) = try poller().poll()
        XCTAssertEqual(r2.enqueued, 1)
        XCTAssertEqual(s2.queue.first?["payload"]?["text"], "new")
    }

    func testLateRowsOlderThanBaselineAreNeverEmitted() throws {
        try poller().poll()
        fixture.message(DM, sender: ALICE, text: "synced from iCloud", at: at(-60))
        clock.advance(60)
        let (_, r) = try poller().poll()
        XCTAssertEqual(r.enqueued, 0)
        XCTAssertEqual(r.skipped["before_baseline"], 1)
    }

    func testDedupAcrossRestartCursorRewindAndNewRowid() throws {
        try poller().poll()
        let (_, guid) = fixture.message(DM, sender: ALICE, text: "once", at: at(1))
        clock.advance(120)
        XCTAssertEqual(try poller().poll().1.enqueued, 1)
        // "Restart" with a cursor that moved back.
        var s = try store.load()
        s.cursor = 0
        try store.save(s)
        XCTAssertEqual(try poller().poll().1.duplicates, 1)
        // Same GUID under a new ROWID (rebuilt database).
        fixture.delete(1)
        fixture.message(DM, sender: ALICE, text: "once", at: at(1), guid: guid)
        let (s2, r) = try poller().poll()
        XCTAssertEqual(r.enqueued, 0)
        XCTAssertEqual(s2.queue.count, 1)
    }

    func testCrashBeforeSaveReplaysIdenticalEnvelopes() throws {
        try poller().poll()
        fixture.message(DM, sender: ALICE, text: "a", at: at(1))
        clock.advance(120)
        let (lost, _) = try poller().poll(persist: false)  // crash: nothing saved
        let (saved, _) = try poller().poll()
        XCTAssertEqual(lost.queue, saved.queue)
    }

    func testHoldBackYoungOrphanThenRetry() throws {
        try poller().poll()
        let (rowid, _) = fixture.message(DM, sender: ALICE, text: "join comes later", at: at(0), join: false)
        let (_, r) = try poller().poll()
        XCTAssertTrue(r.heldBack)
        XCTAssertEqual(r.cursorAfter, 0)
        fixture.join(DM, rowid)
        XCTAssertEqual(try poller().poll().1.enqueued, 1)
    }

    func testBoundedQueueAppliesBackPressureWithoutLoss() throws {
        try poller().poll()
        for i in 0..<5 { fixture.message(DM, sender: ALICE, text: "m\(i)", at: at(1)) }
        clock.advance(120)
        let (s, r) = try poller(maxQueued: 3).poll()
        XCTAssertTrue(r.queueFull)
        XCTAssertEqual(s.queue.count, 3)
        XCTAssertEqual(s.cursor, 3)
        // Upload drains two; the next round takes the rest.
        var drained = s
        drained.queue.removeFirst(2)
        try store.save(drained)
        let (s2, _) = try poller(maxQueued: 3).poll()
        XCTAssertEqual(s2.queue.map { $0["payload"]?["text"]?.stringValue ?? "" }, ["m2", "m3", "m4"])
    }

    func testBatchesAcrossManyRows() throws {
        try poller().poll()
        for i in 0..<25 { fixture.message(i % 2 == 0 ? DM : SMS_CHAT, sender: i % 2 == 0 ? ALICE : DAN_SMS, text: "n\(i)", at: at(1)) }
        clock.advance(120)
        let (s, r) = try poller(batch: 4).poll()
        XCTAssertEqual(r.enqueued, 25)
        XCTAssertEqual(s.cursor, 25)
    }

    func testGroupPolicySkip() throws {
        savePaired { $0.settings.groupPolicy = .skip }
        try poller().poll()
        fixture.message(GROUP, sender: CARA, text: "g", at: at(1))
        clock.advance(120)
        let (_, r) = try poller().poll()
        XCTAssertEqual(r.enqueued, 0)
        XCTAssertEqual(r.skipped["group"], 1)
    }

    func testDrainOrderStopsOnRetryAndContinuesAfterUnpaired() async throws {
        var s = CompanionState()
        for id in ["a", "b", "c", "d"] { s.enqueue(["event_id": .string(id)]) }
        try store.save(s)
        var answers: [UploadAnswer] = [.accepted, .duplicate, .retry]
        var seen: [String] = []
        let r = try await drainQueue(store: store) { env in
            seen.append(env["event_id"]!.stringValue!)
            return answers.removeFirst()
        }
        XCTAssertEqual(seen, ["a", "b", "c"])
        XCTAssertTrue(r.stoppedOnRetry)
        XCTAssertEqual(try store.load().queue.map { $0["event_id"]!.stringValue! }, ["c", "d"])
        answers = [.unpaired, .accepted]
        let r2 = try await drainQueue(store: store) { _ in answers.removeFirst() }
        XCTAssertTrue(r2.unpaired)
        XCTAssertEqual(r2.uploaded, 1)
        XCTAssertTrue(try store.load().queue.isEmpty)
    }
}
