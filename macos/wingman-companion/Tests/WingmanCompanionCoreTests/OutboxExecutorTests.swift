import Foundation
import XCTest
@testable import WingmanCompanionCore

final class OutboxExecutorTests: CompanionTestCase {
    var fake: FakeMessages!

    override func setUp() {
        super.setUp()
        fake = FakeMessages(fixture, clock)
    }

    func executor(_ t: MessagesTransport? = nil) -> OutboxExecutor {
        OutboxExecutor(store: store, reader: ChatDbReader(path: fixture.path), scope: SCOPE,
                       transport: t ?? fake, clock: clock.fn)
    }

    func run(_ ins: OutboxInstruction, _ ex: OutboxExecutor? = nil, anchor: Date? = nil) async throws -> SendOutcome {
        try await (ex ?? executor()).execute(ins, serverTime: SERVER_TIME, anchor: anchor ?? clock.now)
    }

    func testScriptSuccessIsNotAnAckUntilTheRowAppears() async throws {
        fake.writeRow = false
        let ins = instruction()
        let ex = executor()
        let out = try await run(ins, ex)
        XCTAssertEqual(out.status, .executed)
        XCTAssertNil(out.envelope)
        XCTAssertEqual(try store.load().ledger[ins.idempotencyKey]?.phase, .scriptOk)
        XCTAssertEqual(try ex.reconcile(texts: [ins.idempotencyKey: ins.text]), [])
        let (rid, guid) = fixture.message(DM, sender: nil, text: "Yes! 8pm?", at: clock.now, fromMe: true)
        let acks = try ex.reconcile(texts: [ins.idempotencyKey: ins.text])
        XCTAssertEqual(acks.count, 1)
        XCTAssertEqual(acks[0]["type"], "message.send_acknowledged")
        XCTAssertEqual(acks[0]["external_message_id"], .string("imessage:\(guid)"))
        XCTAssertEqual(acks[0]["payload"], ["outbound_message_id": .string(ins.outboundMessageId),
                                            "idempotency_key": .string(ins.idempotencyKey)])
        XCTAssertEqual(try ex.reconcile(texts: [:]), [])
        fixture.markDelivered(rid)
        let receipt = try ex.reconcile(texts: [:])
        XCTAssertEqual(receipt.map { $0["type"]! }, ["message.delivery_receipt"])
        XCTAssertEqual(try ex.reconcile(texts: [:]), [])
        XCTAssertEqual(try store.load().queue.count, 2)  // ack + receipt queued for upload
    }

    func testOneTransportAttemptPerKeyAcrossRestart() async throws {
        let ins = instruction()
        let first = try await run(ins)
        XCTAssertTrue(first.transportCalled)
        for _ in 0..<3 {  // replayed claim, duplicate instruction, restart (new executor)
            let out = try await run(ins)
            XCTAssertEqual(out.status, .replayed)
            XCTAssertFalse(out.transportCalled)
        }
        XCTAssertEqual(fake.calls.count, 1)
    }

    func testKeyIsClaimedBeforeTransportAndCrashRecoversAsUnknownTimeout() async throws {
        let ins = instruction()
        var phaseAtSend: CompanionState.Phase?
        fake.onSend = { [store] _ in
            phaseAtSend = try store!.load().ledger[ins.idempotencyKey]?.phase
            throw Offline()  // the process "dies" mid-send
        }
        do { _ = try await run(ins); XCTFail("expected crash") } catch {}
        XCTAssertEqual(phaseAtSend, .claimed)
        fake.onSend = nil
        let replay = try await run(ins)
        XCTAssertEqual(replay.status, .replayed)
        XCTAssertEqual(fake.calls.count, 1)
        clock.advance(120)
        let env = try executor().reconcile(texts: [:])
        XCTAssertEqual(env.count, 1)
        XCTAssertEqual(env[0]["payload"]?["failure"]?["code"], "timeout")
        XCTAssertEqual(env[0]["payload"]?["failure"]?["retryable"], false)  // it may have gone out
    }

    func testCrashAfterMessagesSentRecoversAckWithoutTheText() async throws {
        fake.onSend = { [fixture, clock] cmd in
            fixture!.message(cmd.chatGuid, sender: nil, text: cmd.text, at: clock!.now, fromMe: true)
            throw Offline()
        }
        do { _ = try await run(instruction()); XCTFail() } catch {}
        let acks = try executor().reconcile(texts: [:])  // after restart: no text in memory
        XCTAssertEqual(acks.map { $0["type"]! }, ["message.send_acknowledged"])
    }

    func testIdenticalTextsMatchDistinctRows() async throws {
        let ex = executor()
        let k1 = "send:0192f3a0-0000-7000-8000-00000000aa01:1", k2 = "send:0192f3a0-0000-7000-8000-00000000aa02:1"
        _ = try await run(instruction(key: k1, text: "ok"), ex)
        _ = try await run(instruction(key: k2, text: "ok", outbound: "0192f3a0-0000-7000-8000-00000000aa02"), ex)
        let acks = try ex.reconcile(texts: [k1: "ok", k2: "ok"])
        XCTAssertEqual(Set(acks.compactMap { $0["external_message_id"]?.stringValue }).count, 2)
    }

    func testAppleEventErrorMapping() async throws {
        let cases: [(Int?, String, Bool)] = [(-1743, "permission_denied", false), (-1728, "rejected_by_platform", false),
                                            (-600, "not_connected", true), (-609, "not_connected", true),
                                            (-1712, "timeout", false), (nil, "transport_error", false),
                                            (-9999, "transport_error", false)]
        for (i, (ae, code, retryable)) in cases.enumerated() {
            fake.result = ExecResult(exitCode: 1, appleEventError: ae)
            let out = try await run(instruction(key: "send:0192f3a0-0000-7000-8000-00000000aa01:\(i + 1)"))
            XCTAssertEqual(out.status, .failed)
            XCTAssertEqual(out.envelope?["payload"]?["failure"]?["code"], .string(code))
            XCTAssertEqual(out.envelope?["payload"]?["failure"]?["retryable"], .bool(retryable))
            XCTAssertEqual(out.envelope?["external_message_id"], .null)
        }
        XCTAssertEqual(try store.load().health.automationGranted, false)  // -1743 seen
    }

    func testMessagesErrorOnTheSentRowIsAFailure() async throws {
        fake.rowError = 22
        let ins = instruction()
        let ex = executor()
        _ = try await run(ins, ex)
        let env = try ex.reconcile(texts: [ins.idempotencyKey: ins.text])
        XCTAssertEqual(env[0]["type"], "message.send_failed")
        XCTAssertEqual(env[0]["payload"]?["failure"]?["code"], "rejected_by_platform")
    }

    func testLeaseDeadlineBlocksTheSend() async throws {
        let anchor = clock.now
        clock.advance(106)  // 120 s lease - 15 s margin = 105 s
        let out = try await run(instruction(), anchor: anchor)
        XCTAssertEqual(out.status, .expired)
        XCTAssertTrue(fake.calls.isEmpty)
        XCTAssertEqual(out.envelope?["payload"]?["failure"],
                       ["code": "timeout", "retryable": true, "detail": "lease deadline passed before the send started"])
        XCTAssertEqual(OutboxExecutor.localDeadline(anchor: anchor, serverTime: SERVER_TIME,
                                                    leaseExpiresAt: "2026-10-30T18:02:00Z"), anchor.addingTimeInterval(105))
    }

    func testForeignOrNonLeasedInstructionsAreIgnored() async throws {
        for ins in [instruction(connection: "0192f3a0-0000-7000-8000-0000000000ff"), instruction(state: "expired")] {
            let out = try await run(ins)
            XCTAssertEqual(out.status, .ignored)
            XCTAssertNil(out.envelope)
        }
        XCTAssertTrue(fake.calls.isEmpty)
        XCTAssertTrue(try store.load().ledger.isEmpty)
    }

    func testUnsafeInstructionsAreRefusedNeverSent() async throws {
        let bad = [instruction(key: "k-1", thread: "sms:+15550100001"),
                   instruction(key: "k-2", thread: "imessage:chat:iMessage;-;+1555\" & quit"),
                   instruction(key: "k-3", text: "bad\u{0}text"),
                   instruction(key: "k-4", thread: "imessage:chat:\(GROUP)")]  // group sends are off
        for ins in bad {
            let out = try await run(ins)
            XCTAssertEqual(out.status, .refused, ins.idempotencyKey)
            XCTAssertEqual(out.envelope?["payload"]?["failure"]?["code"], "capability_unsupported")
            XCTAssertEqual(out.envelope?["payload"]?["failure"]?["retryable"], false)
            let again = try await run(ins)
            XCTAssertEqual(again.status, .replayed)  // the key is used up
        }
        XCTAssertTrue(fake.calls.isEmpty)
    }

    func testGroupSendOnlyWhenExplicitlyAllowed() async throws {
        let ex = executor()
        ex.allowGroupSends = true
        let out = try await run(instruction(thread: "imessage:chat:\(GROUP)"), ex)
        XCTAssertEqual(out.status, .executed)
        XCTAssertEqual(fake.calls.map(\.isGroup), [true])
    }

    func testLocalKillSwitchRefusesAnAlreadyClaimedInstruction() async throws {
        savePaired { $0.settings.sendsStopped = true }
        let out = try await run(instruction())
        XCTAssertEqual(out.status, .refused)
        XCTAssertTrue(fake.calls.isEmpty)
    }

    func testRestartWhileWaitingForTheRowStillAcksOnce() async throws {
        fake.writeRow = false
        let ins = instruction()
        _ = try await run(ins)
        // Companion restarts; Messages writes the row afterwards.
        fixture.message(DM, sender: nil, text: ins.text, at: clock.now, fromMe: true)
        let after = executor()
        XCTAssertEqual(try after.reconcile(texts: [:]).count, 1)
        XCTAssertEqual(try after.reconcile(texts: [:]).count, 0)
        XCTAssertEqual(fake.calls.count, 1)
    }
}
