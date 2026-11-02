import Foundation
import XCTest
@testable import WingmanCompanionCore

final class DigestTests: XCTestCase {
    func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }

    func testSHA1Vectors() {
        XCTAssertEqual(hex(Digest.sha1(Array("abc".utf8))), "a9993e364706816aba3e25717850c26c9cd0d89d")
        XCTAssertEqual(hex(Digest.sha1(Array(repeating: UInt8(ascii: "a"), count: 1000))),
                       "291e9a6c66994949b57ba5e650361e98fc36b1ba")
    }

    func testSHA256Vectors() {
        XCTAssertEqual(hex(Digest.sha256([])), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(hex(Digest.sha256(Array(repeating: UInt8(ascii: "a"), count: 1000))),
                       "41edece42d63e8d9bf515a9ba6932e1c20cbc9f5a5d134645adb5db1b9737ea3")
    }

    /// Same event IDs as the Python spike (`uuid.uuid5`), so both agree on dedup keys.
    func testEventIdsMatchPythonSpike() {
        XCTAssertEqual(Envelope.eventId(userId: USER_ID, type: "message.received",
                                        key: "imessage:FIXTURE-0001-0000-0000-0000-000000000000"),
                       "435691a1-49a0-50d8-b577-08f390890632")
        XCTAssertEqual(Envelope.eventId(userId: USER_ID, type: "message.send_acknowledged",
                                        key: "send:0192f3a0-0000-7000-8000-00000000aa01:1"),
                       "038cb572-071b-58fa-b350-d4c82dd4a1f1")
        XCTAssertEqual(Envelope.eventId(userId: USER_ID, type: "x", key: ""), "2fefe399-61e2-5247-83f1-0dbbb11cb1a3")
    }

    /// RFC 7636 appendix B.
    func testPKCEChallengeRFCVector() {
        let p = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", state: "s")
        XCTAssertEqual(p.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let r = PKCE.random()
        XCTAssertEqual(r.verifier.count, 43)
        XCTAssertNotEqual(r.verifier, PKCE.random().verifier)
    }
}

final class TimeConvTests: XCTestCase {
    func testNanosecondsAndSeconds() {
        XCTAssertNil(TimeConv.appleToDate(0))
        XCTAssertNil(TimeConv.appleToDate(nil))
        let d = at(0)
        XCTAssertEqual(TimeConv.appleToDate(TimeConv.dateToAppleNs(d)), d)
        XCTAssertEqual(TimeConv.appleToDate(Int64(d.timeIntervalSinceReferenceDate)), d)  // old seconds databases
    }

    func testContractTimestamp() {
        XCTAssertEqual(TimeConv.contractTimestamp(at(0)), "2026-10-30T18:00:00Z")
        XCTAssertEqual(TimeConv.contractTimestamp(at(0).addingTimeInterval(0.25)), "2026-10-30T18:00:00.250000Z")
        XCTAssertEqual(TimeConv.parse("2026-10-30T18:02:00Z")!.timeIntervalSince(at(0)), 120)
        XCTAssertEqual(TimeConv.parse("2026-10-30T18:00:00.5Z")!.timeIntervalSince(at(0)), 0.5, accuracy: 0.001)
    }
}

final class TypedStreamTests: XCTestCase {
    func testRoundTripAllLengthEncodings() {
        for text in ["hi", String(repeating: "é", count: 100), String(repeating: "x", count: 70_000), "emoji 👋🏽 ok"] {
            XCTAssertEqual(TypedStream.decodeAttributedBody(TypedStream.encodeForFixture(text)), text)
        }
    }

    func testMalformedNeverThrows() {
        XCTAssertNil(TypedStream.decodeAttributedBody(nil))
        XCTAssertNil(TypedStream.decodeAttributedBody([]))
        XCTAssertNil(TypedStream.decodeAttributedBody(Array("not a stream".utf8)))
        var truncated = TypedStream.encodeForFixture("hello world")
        truncated.removeLast(truncated.count - TypedStream.header.count - 60)
        XCTAssertNil(TypedStream.decodeAttributedBody(truncated))
        // Invalid UTF-8 is "not decodable", never garbled text.
        var bad = TypedStream.encodeForFixture("ab")
        let i = bad.firstIndex(of: UInt8(ascii: "a"))!
        bad[i] = 0xff
        XCTAssertNil(TypedStream.decodeAttributedBody(bad))
        // Header only, no NSString.
        XCTAssertNil(TypedStream.decodeAttributedBody(TypedStream.header + [0x84, 0x01]))
    }
}

final class AppleScriptCommandTests: XCTestCase {
    func testScriptIsConstantAndDataOnlyInArguments() throws {
        let evil = "\" & (do shell script \"rm -rf ~\") & \"\nend tell\ntell application \"Finder\" to quit «event aevtquit» “smart”"
        let cmd = try AppleScriptCommand.build(chatGuid: DM, text: evil)
        XCTAssertEqual(cmd.arguments, [DM, evil])
        XCTAssertFalse(AppleScriptCommand.sendToChatSource.contains("rm -rf"))
        XCTAssertTrue(AppleScriptCommand.sendToChatSource.hasPrefix("on run argv"))
        XCTAssertTrue(AppleScriptCommand.sendToChatSource.contains("chat id chatGuid"))
        // An argument that looks like an option is still just data.
        XCTAssertEqual(try AppleScriptCommand.build(chatGuid: DM, text: "-e quit").arguments.last, "-e quit")
    }

    func testRejectsBadGuidsAndText() {
        for g in ["", "iMessage;-;", "Mail;-;x@example.com", "iMessage;*;x", "iMessage;-;+1555\" & quit",
                  "iMessage;-;a b", "-e;-;x", "iMessage;-;" + String(repeating: "1", count: 201), "iMessage;-;é@x.com"] {
            XCTAssertThrowsError(try AppleScriptCommand.validateChatGuid(g), g)
        }
        for g in [DM, GROUP, SMS_CHAT, "any;-;+15550100001", "RCS;-;bob@example.com"] {
            XCTAssertNoThrow(try AppleScriptCommand.validateChatGuid(g), g)
        }
        for t in ["", "   \n", "bad\u{0}text", "bell\u{7}", "del\u{7f}", String(repeating: "x", count: 4001)] {
            XCTAssertThrowsError(try AppleScriptCommand.validateText(t))
        }
        XCTAssertNoThrow(try AppleScriptCommand.validateText("line1\nline2\ttab\r"))
        XCTAssertNoThrow(try AppleScriptCommand.validateText(String(repeating: "x", count: 4000)))
    }

    func testDescribeIsRedacted() throws {
        let cmd = try AppleScriptCommand.build(chatGuid: DM, text: "secret plans")
        XCTAssertEqual(cmd.describe(), "applescript send (iMessage, 1:1, 12 chars)")
        XCTAssertFalse(cmd.describe().contains("secret"))
        XCTAssertFalse(cmd.describe().contains(ALICE))
    }

    func testManualLiteralEscaping() throws {
        XCTAssertEqual(try AppleScriptCommand.stringLiteral("a\"b\\c\nd"), "\"a\\\"b\\\\c\\nd\"")
        XCTAssertThrowsError(try AppleScriptCommand.stringLiteral("x\u{1}"))
    }
}

final class StateStoreTests: CompanionTestCase {
    func testAtomicSaveIsPrivateAndRoundTrips() throws {
        var s = CompanionState()
        s.cursor = 7
        s.enqueue(["event_id": "e1", "type": "message.received"])
        s.enqueue(["event_id": "e1", "type": "message.received"])  // dedup by event_id
        try store.save(s)
        XCTAssertEqual(try store.load(), s)
        XCTAssertEqual(try store.load().queue.count, 1)
        let attrs = try FileManager.default.attributesOfItem(atPath: store.url.path)
        XCTAssertEqual((attrs[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: store.url.deletingLastPathComponent().path)
        XCTAssertEqual(leftovers, ["companion-state.json"])
        try store.wipe()
        XCTAssertEqual(try store.load(), CompanionState())
    }

    func testStateNeverHoldsTokens() throws {
        savePaired()
        let raw = String(data: FileManager.default.contents(atPath: store.url.path)!, encoding: .utf8)!
        XCTAssertFalse(raw.lowercased().contains("token"))
    }

    func testPruneKeepsRecentDedupAndLiveLedger() {
        var s = CompanionState()
        s.seen = ["old": at(-60 * 24 * 15), "new": at(0)]
        let rec = CompanionState.SendRecord(outboundMessageId: "o", connectionId: "c", chatGuid: DM, textLength: 1,
                                            claimedAt: at(-60 * 24 * 15), phase: .failed, transportCalled: true)
        var live = rec
        live.phase = .scriptOk
        s.ledger = ["dead": rec, "live": live]
        s.prune(now: at(0))
        XCTAssertEqual(Set(s.seen.keys), ["new"])
        XCTAssertEqual(Set(s.ledger.keys), ["live"])
    }
}
