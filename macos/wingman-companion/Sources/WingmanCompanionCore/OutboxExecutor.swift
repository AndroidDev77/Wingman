import Foundation

/// A leased `OutboxInstruction` (contract), parsed. `text` and the thread are private.
public struct OutboxInstruction: Sendable, Equatable {
    public var id: String
    public var connectionId: String
    public var outboundMessageId: String
    public var idempotencyKey: String
    public var attempt: Int
    public var state: String
    public var leaseExpiresAt: String
    public var externalThreadId: String
    public var text: String

    public init?(_ v: JSONValue) {
        guard let id = v["id"]?.stringValue, let c = v["connection_id"]?.stringValue,
              let o = v["outbound_message_id"]?.stringValue, let k = v["idempotency_key"]?.stringValue,
              let s = v["state"]?.stringValue, let l = v["lease_expires_at"]?.stringValue,
              let t = v["external_thread_id"]?.stringValue, let text = v["text"]?.stringValue
        else { return nil }
        self.init(id: id, connectionId: c, outboundMessageId: o, idempotencyKey: k,
                  attempt: v["attempt"]?.intValue ?? 1, state: s, leaseExpiresAt: l, externalThreadId: t, text: text)
    }

    public init(id: String, connectionId: String, outboundMessageId: String, idempotencyKey: String,
                attempt: Int, state: String, leaseExpiresAt: String, externalThreadId: String, text: String) {
        self.id = id
        self.connectionId = connectionId
        self.outboundMessageId = outboundMessageId
        self.idempotencyKey = idempotencyKey
        self.attempt = attempt
        self.state = state
        self.leaseExpiresAt = leaseExpiresAt
        self.externalThreadId = externalThreadId
        self.text = text
    }
}

public struct SendOutcome: Sendable, Equatable {
    public enum Status: String, Sendable { case executed, replayed, ignored, refused, expired, failed }
    public var status: Status
    public var transportCalled: Bool
    public var envelope: JSONValue?
}

/// Leased instruction -> at most ONE Apple-event send -> honest outcome (port of
/// `sendplan.py`, D-006, ADR 0006).
///
/// 1. An instruction for another connection or not `leased` is ignored (no answer).
/// 2. One that cannot be sent safely (not an `imessage:chat:` thread, bad GUID or text, a
///    group chat while group sends are off, or the local kill switch is on) is **refused**:
///    the key is used up with no transport call and `send_failed` /
///    `capability_unsupported` / not retryable is reported (nothing was sent).
/// 3. Lease deadline: `anchor + (lease_expires_at - server_time) - 15 s`. Past it:
///    `send_failed` / `timeout` / retryable, no transport call.
/// 4. **The key is claimed in the durable ledger BEFORE the transport is called**, with
///    the pre-send `MAX(ROWID)` mark. A repeat of the key, before or after a restart, is
///    answered from the ledger and never sent again.
/// 5. Script success (the handler returned) is recorded but is NOT an acknowledgement.
///    `send_acknowledged` comes only from `reconcile`, when the sent row is found in
///    chat.db (`is_from_me`, same chat, same text, above the mark, `error = 0`). No row
///    within 60 s -> `send_failed` / `timeout` / NOT retryable (it may still go out).
public final class OutboxExecutor: @unchecked Sendable {
    public static let deadlineMargin: TimeInterval = 15
    public static let correlationWindow: TimeInterval = 60

    /// Apple-event error -> (code, retryable). UNVERIFIED on a Mac.
    public static let appleEventErrors: [Int: (FailureCode, Bool)] = [
        -1743: (.permissionDenied, false),  // errAEEventNotPermitted: Automation denied
        -1728: (.rejectedByPlatform, false),  // errAENoSuchObject: chat id not found
        -600: (.notConnected, true),  // procNotFound: Messages not running
        -609: (.notConnected, true),  // connectionInvalid
        -1712: (.timeout, false),  // errAETimeout: may still go out
    ]

    let store: StateStore
    let reader: ChatDbReader
    let scope: Scope
    let transport: MessagesTransport
    let clock: @Sendable () -> Date
    public var allowGroupSends = false

    public init(store: StateStore, reader: ChatDbReader, scope: Scope, transport: MessagesTransport,
                clock: @escaping @Sendable () -> Date = Date.init) {
        self.store = store
        self.reader = reader
        self.scope = scope
        self.transport = transport
        self.clock = clock
    }

    public static func localDeadline(anchor: Date, serverTime: String, leaseExpiresAt: String) -> Date? {
        guard let s = TimeConv.parse(serverTime), let l = TimeConv.parse(leaseExpiresAt) else { return nil }
        return anchor.addingTimeInterval(l.timeIntervalSince(s) - deadlineMargin)
    }

    public func execute(_ ins: OutboxInstruction, serverTime: String, anchor: Date) async throws -> SendOutcome {
        var state = try store.load()
        if state.ledger[ins.idempotencyKey] != nil {
            return SendOutcome(status: .replayed, transportCalled: false)
        }
        guard ins.connectionId == scope.connectionId, ins.state == "leased" else {
            return SendOutcome(status: .ignored, transportCalled: false)
        }
        let now = clock()
        let chatGuid = Envelope.chatGuid(fromThreadId: ins.externalThreadId)

        func finish(_ phase: CompanionState.Phase, code: FailureCode, retryable: Bool, detail: String,
                    status: SendOutcome.Status) throws -> SendOutcome {
            let env = Envelope.sendFailed(scope: scope, chatGuid: chatGuid, outboundMessageId: ins.outboundMessageId,
                                          idempotencyKey: ins.idempotencyKey, code: code, retryable: retryable,
                                          detail: detail, occurredAt: now, observedAt: now)
            state.ledger[ins.idempotencyKey] = record(ins, chatGuid ?? "", phase: phase, called: false, at: now)
            state.enqueue(env)
            try store.save(state)
            return SendOutcome(status: status, transportCalled: false, envelope: env)
        }

        let command: AppleScriptCommand.SendCommand
        do {
            guard let chatGuid else { throw AppleScriptCommand.ValidationError.badChatGuid }
            command = try AppleScriptCommand.build(chatGuid: chatGuid, text: ins.text)
        } catch {
            return try finish(.failed, code: .capabilityUnsupported, retryable: false,
                              detail: "refused by the companion: invalid destination or text", status: .refused)
        }
        if command.isGroup && !allowGroupSends {
            return try finish(.failed, code: .capabilityUnsupported, retryable: false,
                              detail: "refused by the companion: group sends are off", status: .refused)
        }
        if state.settings.sendsStopped || !state.settings.enabled {
            return try finish(.failed, code: .capabilityUnsupported, retryable: false,
                              detail: "refused by the companion: sends stopped on this Mac", status: .refused)
        }
        guard let deadline = Self.localDeadline(anchor: anchor, serverTime: serverTime,
                                                leaseExpiresAt: ins.leaseExpiresAt), now < deadline else {
            return try finish(.failed, code: .timeout, retryable: true,
                              detail: "lease deadline passed before the send started", status: .expired)
        }

        var rec = record(ins, command.chatGuid, phase: .claimed, called: true, at: now)
        rec.rowidMark = try reader.maxRowid()
        state.ledger[ins.idempotencyKey] = rec
        try store.save(state)  // durable BEFORE the transport (D-006)

        let result = try await transport.send(command)

        state = try store.load()
        guard var r = state.ledger[ins.idempotencyKey] else { return SendOutcome(status: .failed, transportCalled: true) }
        if result.exitCode == 0 {
            r.phase = .scriptOk  // NOT acknowledged yet
            r.scriptOkAt = clock()
            state.ledger[ins.idempotencyKey] = r
            state.health.automationGranted = true
            try store.save(state)
            return SendOutcome(status: .executed, transportCalled: true)
        }
        let (code, retryable) = Self.appleEventErrors[result.appleEventError ?? 0] ?? (.transportError, false)
        let t = clock()
        let env = Envelope.sendFailed(scope: scope, chatGuid: command.chatGuid, outboundMessageId: r.outboundMessageId,
                                      idempotencyKey: ins.idempotencyKey, code: code, retryable: retryable,
                                      detail: "apple event error \(result.appleEventError.map(String.init) ?? "none")",
                                      occurredAt: t, observedAt: t)
        r.phase = .failed
        state.ledger[ins.idempotencyKey] = r
        if code == .permissionDenied { state.health.automationGranted = false }
        state.enqueue(env)
        try store.save(state)
        return SendOutcome(status: .failed, transportCalled: true, envelope: env)
    }

    private func record(_ ins: OutboxInstruction, _ chatGuid: String, phase: CompanionState.Phase,
                        called: Bool, at: Date) -> CompanionState.SendRecord {
        CompanionState.SendRecord(outboundMessageId: ins.outboundMessageId, connectionId: scope.connectionId,
                                  chatGuid: chatGuid, textLength: ins.text.unicodeScalars.count, claimedAt: at,
                                  phase: phase, transportCalled: called)
    }

    /// Correlates pending sends with chat.db rows and queues outcome envelopes. `texts`
    /// maps idempotency key -> authorized text, kept in memory only (the ledger stores the
    /// length). After a crash the length is the only check: weaker, and documented.
    @discardableResult
    public func reconcile(texts: [String: String]) throws -> [JSONValue] {
        var state = try store.load()
        let now = clock()
        var emitted: [JSONValue] = []
        var taken = Set(state.ledger.values.compactMap { $0.sentRowid })
        for key in state.ledger.keys.sorted(by: { state.ledger[$0]!.claimedAt < state.ledger[$1]!.claimedAt }) {
            var rec = state.ledger[key]!
            switch rec.phase {
            case .claimed, .scriptOk:
                let row = try findSentRow(rec, text: texts[key], taken: taken)
                let env: JSONValue
                if let row, row.error == 0 {
                    taken.insert(row.rowid)
                    env = Envelope.sendAcknowledged(scope: scope, sentGuid: row.guid, chatGuid: rec.chatGuid,
                                                    outboundMessageId: rec.outboundMessageId, idempotencyKey: key,
                                                    occurredAt: row.occurredAt ?? now, observedAt: now)
                    rec.phase = .acknowledged
                    rec.sentGuid = row.guid
                    rec.sentRowid = row.rowid
                } else if let row {
                    taken.insert(row.rowid)
                    env = failed(rec, key, .rejectedByPlatform, false, "messages error \(row.error)", now)
                    rec.phase = .failed
                } else if now.timeIntervalSince(rec.claimedAt) > Self.correlationWindow {
                    env = failed(rec, key, .timeout, false, "sent row not observed; outcome unknown", now)
                    rec.phase = .failed
                } else {
                    continue
                }
                state.ledger[key] = rec
                state.enqueue(env)
                emitted.append(env)
            case .acknowledged where !rec.delivered:
                guard let rowid = rec.sentRowid, let sentGuid = rec.sentGuid,
                      let row = try reader.readRowids([rowid]).first, row.isDelivered == true else { continue }
                let env = Envelope.deliveryReceipt(scope: scope, sentGuid: sentGuid, chatGuid: rec.chatGuid,
                                                   outboundMessageId: rec.outboundMessageId, occurredAt: now, observedAt: now)
                rec.delivered = true
                state.ledger[key] = rec
                state.enqueue(env)
                emitted.append(env)
            default:
                continue
            }
        }
        try store.save(state)
        return emitted
    }

    private func failed(_ rec: CompanionState.SendRecord, _ key: String, _ code: FailureCode, _ retryable: Bool,
                        _ detail: String, _ now: Date) -> JSONValue {
        Envelope.sendFailed(scope: scope, chatGuid: rec.chatGuid, outboundMessageId: rec.outboundMessageId,
                            idempotencyKey: key, code: code, retryable: retryable, detail: detail,
                            occurredAt: now, observedAt: now)
    }

    /// First own row in the same chat after the mark with the same text, not already
    /// matched to another key (two identical replies match distinct rows, in order).
    private func findSentRow(_ rec: CompanionState.SendRecord, text: String?, taken: Set<Int64>) throws -> MessageRow? {
        var cursor = rec.rowidMark ?? 0
        while true {
            let rows = try reader.readAfter(cursor, limit: 200)
            if rows.isEmpty { return nil }
            for row in rows {
                cursor = row.rowid
                let sameText = text.map { Envelope.cleanText(row.text) == Envelope.cleanText($0) }
                    ?? ((row.text ?? "").unicodeScalars.count == rec.textLength)
                if row.isFromMe && row.chatGuid == rec.chatGuid && !taken.contains(row.rowid) && !row.isReaction && sameText {
                    return row
                }
            }
        }
    }
}
