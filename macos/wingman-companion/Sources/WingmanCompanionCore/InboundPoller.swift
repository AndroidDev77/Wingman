import Foundation

/// One inbound round: read -> classify -> dedup -> enqueue -> cursor (port of `connector.py`).
///
/// * **No history.** The first round on a fresh state only baselines (cursor = MAX(ROWID),
///   `baselineAt` = now) and emits nothing.
/// * **Hold-back.** A row younger than 30 s whose `chat_message_join` is still missing
///   stops the round; it is retried next time (Messages' write order is UNVERIFIED).
/// * **Bounded queue.** When `maxQueuedInbound` envelopes wait for upload, the round stops
///   before the next row and the cursor stays there: back-pressure, nothing dropped.
/// * **One durable write** at the end: crash before it -> identical envelopes next time.
public struct PollReport: Sendable, Equatable {
    public var baselined = false
    public var read = 0
    public var enqueued = 0
    public var duplicates = 0
    public var heldBack = false
    public var queueFull = false
    public var skipped: [String: Int] = [:]
    public var cursorBefore: Int64?
    public var cursorAfter: Int64?
}

public struct InboundPoller: Sendable {
    public static let holdBackGrace: TimeInterval = 30

    let reader: ChatDbReader
    let store: StateStore
    let scope: Scope
    let batchSize: Int
    let maxQueued: Int
    let clock: @Sendable () -> Date

    public init(reader: ChatDbReader, store: StateStore, scope: Scope, batchSize: Int = 200,
                maxQueued: Int = CompanionState.maxQueuedInbound, clock: @escaping @Sendable () -> Date = Date.init) {
        self.reader = reader
        self.store = store
        self.scope = scope
        self.batchSize = batchSize
        self.maxQueued = maxQueued
        self.clock = clock
    }

    @discardableResult
    public func poll(persist: Bool = true) throws -> (CompanionState, PollReport) {
        var state = try store.load()
        let now = clock()
        var report = PollReport(cursorBefore: state.cursor)

        guard let start = state.cursor else {
            state.cursor = try reader.maxRowid()
            state.baselineAt = now
            report.baselined = true
            report.cursorAfter = state.cursor
            if persist { try store.save(state) }
            return (state, report)
        }

        var cursor = start
        var queued = state.queuedInboundCount
        outer: while true {
            let rows = try reader.readAfter(cursor, limit: batchSize)
            if rows.isEmpty { break }
            for row in rows {
                if queued >= maxQueued {
                    report.queueFull = true
                    break outer
                }
                report.read += 1
                let reason = Envelope.classify(row, baseline: state.baselineAt)
                if reason == .noChat, let at = row.occurredAt, now.timeIntervalSince(at) < Self.holdBackGrace {
                    report.heldBack = true
                    break outer
                }
                cursor = row.rowid
                if let reason {
                    report.skipped[reason.rawValue, default: 0] += 1
                    continue
                }
                guard let env = Envelope.received(row, scope: scope, observedAt: now,
                                                  groupPolicy: state.settings.groupPolicy) else {
                    report.skipped[row.isGroup ? Envelope.SkipReason.group.rawValue : "no_address", default: 0] += 1
                    continue
                }
                let key = env["external_message_id"]!.stringValue!
                let eventId = env["event_id"]!.stringValue!
                if state.seen[key] != nil || state.isQueued(eventId: eventId) {
                    report.duplicates += 1
                    continue
                }
                state.enqueue(env)
                state.seen[key] = now
                queued += 1
                report.enqueued += 1
            }
            if rows.count < batchSize { break }
        }

        state.cursor = cursor
        state.prune(now: now)
        state.bump("enqueued", by: report.enqueued)
        state.health.lastPollAt = now
        state.health.fullDiskAccess = true
        report.cursorAfter = cursor
        if persist { try store.save(state) }
        return (state, report)
    }
}

/// How the backend answered one upload (WING-016 rules, ADR 0006).
public enum UploadAnswer: Sendable, Equatable {
    case accepted  // 202
    case duplicate  // 200
    case permanent  // 400/404/422: dead-letter (logged by event ID only)
    case retry  // anything else, network errors: stop and retry later
    case unpaired  // 422 at /connection_id: the connection was unpaired server-side
}

public struct DrainReport: Sendable, Equatable {
    public var uploaded = 0
    public var duplicates = 0
    public var deadLettered = 0
    public var stoppedOnRetry = false
    public var unpaired = false
}

/// Upload queued envelopes in order with `Idempotency-Key = event_id`. Stops at the first
/// `retry`, so nothing overtakes a waiting envelope. Each removal is saved before the next
/// request. The state is re-read every step, because other code (the sender) appends.
public func drainQueue(store: StateStore, upload: (JSONValue) async -> UploadAnswer) async throws -> DrainReport {
    var report = DrainReport()
    while true {
        let state = try store.load()
        guard let env = state.queue.first, let id = env["event_id"]?.stringValue else { break }
        let answer = await upload(env)
        if answer == .retry {
            report.stoppedOnRetry = true
            break
        }
        var fresh = try store.load()
        switch answer {
        case .accepted: report.uploaded += 1
        case .duplicate: report.duplicates += 1
        case .permanent, .unpaired:
            report.deadLettered += 1
            fresh.bump("dead_lettered")
            if answer == .unpaired { report.unpaired = true }
        case .retry: break
        }
        fresh.queue.removeAll { $0["event_id"]?.stringValue == id }
        fresh.health.lastUploadAt = Date()
        // After an unpair, inbound events are refused (422) but late send outcomes are
        // still accepted, so the drain continues.
        try store.save(fresh)
    }
    return report
}
