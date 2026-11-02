import Foundation
import XCTest
@testable import WingmanCompanionCore

/// The companion's envelopes, frozen in `Fixtures/companion_envelopes.json`. The backend
/// suite (`backend/tests/connectors/imessage/`) posts exactly these through the real API
/// and validates them against `connector_event.v1`, so the Swift mapper and the server are
/// tested against the same bytes. Regenerate with `WINGMAN_UPDATE_GOLDEN=1 swift test`.
final class GoldenEnvelopeTests: CompanionTestCase {
    static var goldenURL: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Fixtures/companion_envelopes.json")
    }

    func produce() async throws -> JSONValue {
        try poller().poll()
        fixture.message(DM, sender: ALICE, text: "Free tonight? 🍜", at: at(1))
        fixture.message(SMS_CHAT, sender: DAN_SMS, text: "Running 10 min late", at: at(1), service: "SMS")
        fixture.message(GROUP, sender: CARA, text: "Who's in for Saturday?", at: at(1))
        fixture.message("iMessage;-;\(BOB)", sender: BOB, text: "\u{FFFC}", at: at(1), attachments: 1)
        fixture.message(DM, sender: ALICE, text: "Coffee after?", at: at(1), attributed: true)
        clock.advance(90)
        let (state, _) = try poller().poll()
        var out = state.queue

        let fake = FakeMessages(fixture, clock)
        let ex = OutboxExecutor(store: store, reader: ChatDbReader(path: fixture.path), scope: SCOPE, transport: fake, clock: clock.fn)
        let ok = instruction(key: "send:0192f3a0-0000-7000-8000-00000000aa01:1", text: "Yes! 8pm?",
                             leaseExpires: TimeConv.contractTimestamp(clock.now.addingTimeInterval(120)))
        _ = try await ex.execute(ok, serverTime: TimeConv.contractTimestamp(clock.now), anchor: clock.now)
        out += try ex.reconcile(texts: [ok.idempotencyKey: ok.text])
        fixture.markDelivered(try reader.maxRowid())  // the row FakeMessages wrote
        out += try ex.reconcile(texts: [:])
        fake.result = ExecResult(exitCode: 1, appleEventError: -1743)
        let denied = instruction(key: "send:0192f3a0-0000-7000-8000-00000000aa02:1", text: "See you",
                                 outbound: "0192f3a0-0000-7000-8000-00000000aa02",
                                 leaseExpires: TimeConv.contractTimestamp(clock.now.addingTimeInterval(120)))
        if let env = try await ex.execute(denied, serverTime: TimeConv.contractTimestamp(clock.now), anchor: clock.now).envelope {
            out.append(env)
        }
        out.append(Envelope.capabilitiesChanged(scope: SCOPE, document: Capabilities.document(assessedAt: clock.now, automationGranted: false),
                                                reason: "automation_denied", at: clock.now))
        return .array(out)
    }

    static var pairingURL: URL {
        goldenURL.deletingLastPathComponent().appendingPathComponent("companion_pairing_requests.json")
    }

    func testPairingRequestsMatchTheGoldenFile() throws {
        let install = "00000000-0000-4000-8000-0000000000c0"
        let produced: JSONValue = [
            "default": CompanionEngine.pairingBody(displayName: "Test MacBook iMessage", deviceInstallId: install,
                                                   assessedAt: at(0), draftOnly: false),
            "draft_only": CompanionEngine.pairingBody(displayName: "Test MacBook iMessage", deviceInstallId: install,
                                                      assessedAt: at(0), draftOnly: true),
        ]
        try checkGolden(produced, Self.pairingURL)
    }

    func checkGolden(_ produced: JSONValue, _ url: URL) throws {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes]
        if ProcessInfo.processInfo.environment["WINGMAN_UPDATE_GOLDEN"] == "1" {
            try (enc.encode(produced) + Data("\n".utf8)).write(to: url)
        }
        let golden = try JSONValue.decode(try Data(contentsOf: url))
        XCTAssertEqual(produced, golden, "regenerate with WINGMAN_UPDATE_GOLDEN=1 if the change is intended")
    }

    func testEnvelopesMatchTheGoldenFile() async throws {
        let produced = try await produce()
        let types = produced.arrayValue!.map { $0["type"]!.stringValue! }
        XCTAssertEqual(types, ["message.received", "message.received", "message.received", "message.received",
                               "message.received", "message.send_acknowledged", "message.delivery_receipt",
                               "message.send_failed", "connection.capabilities_changed"])
        try checkGolden(produced, Self.goldenURL)
    }
}
