import Foundation

/// Privacy-conscious log events: IDs, counts and codes only. There is no case that can
/// carry message text, an address, a chat GUID or a token (tested).
public enum LogEvent: Sendable, Equatable, CustomStringConvertible {
    case paired(connectionId: String)
    case pollRound(read: Int, enqueued: Int, duplicates: Int, skipped: Int, heldBack: Bool, queueFull: Bool)
    case baselined
    case chatDbUnavailable
    case uploaded(accepted: Int, duplicates: Int, deadLettered: Int)
    case claimed(count: Int)
    case sendOutcome(instructionId: String, status: String)
    case acknowledged(outboundMessageId: String)
    case sendFailed(outboundMessageId: String, code: String)
    case backendUnreachable(failures: Int)
    case claimRejected(code: String)
    case unpairedByServer
    case disconnected
    case signedOut
    case keychainLocked

    public var description: String {
        switch self {
        case .paired(let id): return "paired connection=\(id)"
        case let .pollRound(r, e, d, s, h, q):
            return "poll read=\(r) enqueued=\(e) duplicates=\(d) skipped=\(s) held_back=\(h) queue_full=\(q)"
        case .baselined: return "baselined (no history uploaded)"
        case .chatDbUnavailable: return "chat.db unavailable (Full Disk Access?)"
        case let .uploaded(a, d, x): return "upload accepted=\(a) duplicates=\(d) dead_lettered=\(x)"
        case .claimed(let n): return "claimed \(n) instruction(s)"
        case let .sendOutcome(id, s): return "instruction=\(id) outcome=\(s)"
        case .acknowledged(let id): return "acknowledged outbound=\(id)"
        case let .sendFailed(id, c): return "send_failed outbound=\(id) code=\(c)"
        case .backendUnreachable(let n): return "backend unreachable (failures=\(n))"
        case .claimRejected(let c): return "claim rejected code=\(c)"
        case .unpairedByServer: return "connection unpaired by the server"
        case .disconnected: return "disconnected; local state wiped"
        case .signedOut: return "signed out"
        case .keychainLocked: return "keychain locked; waiting for unlock"
        }
    }
}

public protocol CompanionLog: Sendable {
    func record(_ event: LogEvent)
}

public struct NoLog: CompanionLog {
    public init() {}
    public func record(_ event: LogEvent) {}
}

/// What the menu shows.
public enum CompanionStatus: Sendable, Equatable {
    case notSignedIn
    case notPaired
    case paused  // the user's switch is off
    case needsFullDiskAccess
    case keychainLocked
    case backendUnreachable(retryAt: Date?)
    case unpaired  // the server says this Mac is no longer paired
    case running(sendsStopped: Bool, draftOnly: Bool)
}

/// One owner of the cycle: inbound poll -> reconcile -> upload -> claim -> execute ->
/// reconcile -> upload. The app calls `runCycle()` on a timer (30 s) and when the
/// `chat.db-wal` file changes (a wake-up hint, never the source of truth).
public actor CompanionEngine {
    let store: StateStore
    let reader: ChatDbReader
    let api: WingmanAPI
    let transport: MessagesTransport
    let secrets: SecretStore
    let log: CompanionLog
    let clock: @Sendable () -> Date
    public var maxInstructions = 5
    public private(set) var backoff = Backoff()
    public private(set) var status: CompanionStatus = .notPaired
    /// Authorized texts by idempotency key, in memory only (the ledger keeps lengths).
    private var texts: [String: String] = [:]

    public init(store: StateStore, reader: ChatDbReader, api: WingmanAPI, transport: MessagesTransport,
                secrets: SecretStore, log: CompanionLog = NoLog(), clock: @escaping @Sendable () -> Date = Date.init) {
        self.store = store
        self.reader = reader
        self.api = api
        self.transport = transport
        self.secrets = secrets
        self.log = log
        self.clock = clock
    }

    // MARK: install ID and pairing

    /// Random per-install UUID, kept in the Keychain (survives a deleted state file) and in
    /// the state file. A reinstall that also deletes the Keychain item is a new lease holder.
    public func deviceInstallId() throws -> String {
        var state = try store.load()
        if let id = state.deviceInstallId { return id }
        let id = try secrets.read(SecretAccount.deviceInstallId) ?? UUID().lower
        try secrets.write(SecretAccount.deviceInstallId, id)
        state.deviceInstallId = id
        try store.save(state)
        return id
    }

    /// `createConnection` as `imessage` / `companion`. The key and body are persisted before
    /// the request and replayed unchanged after a lost response (200 = already paired).
    @discardableResult
    public func pair(displayName: String) async throws -> String {
        var state = try store.load()
        if let id = state.pairing?.connectionId { return id }
        let install = try deviceInstallId()
        state = try store.load()
        let userId = try await api.me()
        if state.pairing == nil {
            let body = Self.pairingBody(displayName: displayName, deviceInstallId: install,
                                        assessedAt: clock(), draftOnly: state.settings.draftOnly)
            state.pairing = .init(idempotencyKey: UUID().lower, requestBody: body)
            try store.save(state)
        }
        let p = state.pairing!
        let conn = try await api.createConnection(body: p.requestBody, key: p.idempotencyKey)
        guard let id = conn["id"]?.stringValue else { throw BackendError.malformed }
        state = try store.load()
        state.pairing?.connectionId = id
        state.pairing?.userId = userId
        state.pairing?.pairedAt = clock()
        try store.save(state)
        log.record(.paired(connectionId: id))
        return id
    }

    /// The `createConnection` body: `imessage` over `companion`, this install, and the
    /// claimed (never VERIFIED) capability document. No credential of any kind.
    public static func pairingBody(displayName: String, deviceInstallId: String, assessedAt: Date,
                                   draftOnly: Bool) -> JSONValue {
        [
            "platform": "imessage",
            "transport": "companion",
            "display_name": .string(String(displayName.prefix(120))),
            "device_install_id": .string(deviceInstallId),
            "capabilities": Capabilities.document(assessedAt: assessedAt, draftOnly: draftOnly),
        ]
    }

    func scope(_ s: CompanionState) -> Scope? {
        guard let c = s.pairing?.connectionId, let u = s.pairing?.userId else { return nil }
        return Scope(userId: u, connectionId: c)
    }

    // MARK: switches

    public func setEnabled(_ on: Bool) throws {
        var s = try store.load()
        s.settings.enabled = on
        try store.save(s)
    }

    /// Local kill switch: no claim is made from the next statement on, and an instruction
    /// already claimed in this cycle is refused (never sent).
    public func setSendsStopped(_ stopped: Bool) throws {
        var s = try store.load()
        s.settings.sendsStopped = stopped
        try store.save(s)
    }

    public func setDraftOnly(_ on: Bool) throws {
        var s = try store.load()
        guard s.settings.draftOnly != on else { return }
        s.settings.draftOnly = on
        if let sc = scope(s) {
            let now = clock()
            s.enqueue(Envelope.capabilitiesChanged(scope: sc, document: capabilityDocument(s, now), reason: "draft_only_\(on)", at: now))
        }
        try store.save(s)
    }

    /// After the user re-allows Automation in System Settings: claims resume and `send` is
    /// announced as UNVERIFIED again. The next real send re-checks the grant.
    public func resetAutomationCheck() throws {
        var s = try store.load()
        guard s.health.automationGranted == false else { return }
        s.health.automationGranted = nil
        try store.save(s)
        if let sc = scope(s) { try queueCapabilities(sc, reason: "automation_retry") }
    }

    public func setGroupPolicy(_ p: GroupPolicy) throws {
        var s = try store.load()
        s.settings.groupPolicy = p
        try store.save(s)
    }

    func capabilityDocument(_ s: CompanionState, _ now: Date) -> JSONValue {
        Capabilities.document(assessedAt: now, fullDiskAccess: s.health.fullDiskAccess,
                              automationGranted: s.health.automationGranted, draftOnly: s.settings.draftOnly)
    }

    // MARK: the cycle

    public struct CycleReport: Sendable, Equatable {
        public var poll: PollReport?
        public var drained = DrainReport()
        public var claimed = 0
        public var outcomes: [SendOutcome.Status] = []
        public var skippedClaim: String?
    }

    @discardableResult
    public func runCycle() async -> CycleReport {
        // chat.db is opened fresh every cycle and closed after it: a revoked Full Disk
        // Access grant is noticed at the next open (an open descriptor would hide it), and
        // no connection lingers between polls.
        defer { reader.close() }
        var report = CycleReport()
        do {
            let state = try store.load()
            guard let sc = scope(state) else {
                status = .notPaired
                return report
            }
            guard state.settings.enabled else {
                status = .paused
                return report
            }
            let now = clock()
            guard backoff.ready(at: now) else {
                status = .backendUnreachable(retryAt: backoff.notBefore)
                return report
            }

            // 1. Inbound (local only).
            let readable = pollInbound(sc, into: &report)

            // 2. Outcomes of earlier sends.
            if readable { _ = try? executor(sc).reconcile(texts: texts) }

            // 3. Upload.
            report.drained = try await drain()
            if report.drained.unpaired { return try await handleUnpaired() }
            if report.drained.stoppedOnRetry { return unreachable(report) }

            // 4. Claim, unless the user stopped sends, chat.db is unreadable (no way to
            //    acknowledge), or draft-only mode is on (send UNSUPPORTED: nothing to claim).
            let fresh = try store.load()
            if fresh.settings.sendsStopped { report.skippedClaim = "sends_stopped" }
            else if fresh.settings.draftOnly { report.skippedClaim = "draft_only" }
            else if !readable { report.skippedClaim = "chat_db_unavailable" }
            else if fresh.health.automationGranted == false { report.skippedClaim = "automation_denied" }
            if report.skippedClaim == nil {
                try await claimAndExecute(sc, install: try deviceInstallId(), into: &report)
                _ = try? executor(sc).reconcile(texts: texts)
                let second = try await drain()
                report.drained.uploaded += second.uploaded
                report.drained.duplicates += second.duplicates
                report.drained.deadLettered += second.deadLettered
                if second.stoppedOnRetry { return unreachable(report) }
            }
            backoff.succeed()
            let s = try store.load()
            status = s.health.fullDiskAccess == false
                ? .needsFullDiskAccess
                : .running(sendsStopped: s.settings.sendsStopped, draftOnly: s.settings.draftOnly)
        } catch AuthError.keychainLocked {
            status = .keychainLocked
            log.record(.keychainLocked)
        } catch AuthError.signedOut {
            status = .notSignedIn
            log.record(.signedOut)
        } catch BackendError.unauthorized {
            status = .notSignedIn
        } catch BackendError.unreachable, AuthError.network {
            return unreachable(report)
        } catch BackendError.notFound {
            return (try? await handleUnpaired()) ?? report
        } catch BackendError.rejected(_, let code, let pointer) where pointer == "/device_install_id" {
            // D-017: this install may not claim (unpaired, or re-paired from elsewhere).
            log.record(.claimRejected(code: code ?? "validation_error"))
            return (try? await handleUnpaired()) ?? report
        } catch {
            backoff.fail(now: clock())
            status = .backendUnreachable(retryAt: backoff.notBefore)
        }
        return report
    }

    private func unreachable(_ report: CycleReport) -> CycleReport {
        backoff.fail(now: clock())
        log.record(.backendUnreachable(failures: backoff.failures))
        status = .backendUnreachable(retryAt: backoff.notBefore)
        return report
    }

    private func executor(_ sc: Scope) -> OutboxExecutor {
        OutboxExecutor(store: store, reader: reader, scope: sc, transport: transport, clock: clock)
    }

    /// Returns whether chat.db was readable. A change of readability queues one
    /// `connection.capabilities_changed` (read UNSUPPORTED, or back to UNVERIFIED).
    private func pollInbound(_ sc: Scope, into report: inout CycleReport) -> Bool {
        let poller = InboundPoller(reader: reader, store: store, scope: sc, clock: clock)
        do {
            let before = try store.load().health.fullDiskAccess
            let (_, r) = try poller.poll()
            report.poll = r
            if r.baselined { log.record(.baselined) }
            log.record(.pollRound(read: r.read, enqueued: r.enqueued, duplicates: r.duplicates,
                                  skipped: r.skipped.values.reduce(0, +), heldBack: r.heldBack, queueFull: r.queueFull))
            if before == false { try queueCapabilities(sc, reason: "read_restored") }
            return true
        } catch {
            reader.close()
            log.record(.chatDbUnavailable)
            if var s = try? store.load(), s.health.fullDiskAccess != false {
                s.health.fullDiskAccess = false
                try? store.save(s)
                try? queueCapabilities(sc, reason: "read_lost")
            }
            return false
        }
    }

    private func queueCapabilities(_ sc: Scope, reason: String) throws {
        var s = try store.load()
        let now = clock()
        s.enqueue(Envelope.capabilitiesChanged(scope: sc, document: capabilityDocument(s, now), reason: reason, at: now))
        try store.save(s)
    }

    private func drain() async throws -> DrainReport {
        let api = self.api
        let r = try await drainQueue(store: store) { env in await api.ingest(env) }
        if r.uploaded + r.duplicates + r.deadLettered > 0 {
            log.record(.uploaded(accepted: r.uploaded, duplicates: r.duplicates, deadLettered: r.deadLettered))
        }
        return r
    }

    private func claimAndExecute(_ sc: Scope, install: String, into report: inout CycleReport) async throws {
        let result = try await api.claim(connectionId: sc.connectionId, deviceInstallId: install,
                                         max: maxInstructions, key: UUID().lower)
        let anchor = clock()
        report.claimed = result.instructions.count
        if !result.instructions.isEmpty { log.record(.claimed(count: result.instructions.count)) }
        let ex = executor(sc)
        let automationBefore = try store.load().health.automationGranted
        for ins in result.instructions {
            // Re-read the kill switch before EVERY instruction: flipping it mid-cycle stops
            // the remaining sends (they are refused, never sent).
            texts[ins.idempotencyKey] = ins.text
            let out = try await ex.execute(ins, serverTime: result.serverTime, anchor: anchor)
            report.outcomes.append(out.status)
            log.record(.sendOutcome(instructionId: ins.id, status: out.status.rawValue))
            if let env = out.envelope, let code = env["payload"]?["failure"]?["code"]?.stringValue {
                log.record(.sendFailed(outboundMessageId: ins.outboundMessageId, code: code))
            }
        }
        if automationBefore != false, try store.load().health.automationGranted == false {
            try queueCapabilities(sc, reason: "automation_denied")
        }
        // Forget texts whose outcome is settled.
        let ledger = try store.load().ledger
        texts = texts.filter { k, _ in [.claimed, .scriptOk].contains(ledger[k]?.phase) }
    }

    /// The server no longer accepts this install: stop claiming, forget the connection and
    /// its dedup/cursor state (a new pairing baselines again, so no history is uploaded),
    /// and show "Unpaired". Late send outcomes were already given their chance in the drain
    /// (the server still accepts them after an unpair); what remains is dropped. The send
    /// ledger is kept, so a replayed key is still never sent twice.
    private func handleUnpaired() async throws -> CycleReport {
        var s = try store.load()
        s.pairing = nil
        s.cursor = nil
        s.baselineAt = nil
        s.seen = [:]
        s.queue = []
        try store.save(s)
        status = .unpaired
        log.record(.unpairedByServer)
        return CycleReport()
    }

    // MARK: disconnect

    public enum DisconnectResult: Sendable, Equatable {
        case done
        /// The server could not be reached; nothing was wiped. Retry, or `force`.
        case serverUnreachable
    }

    /// Disconnect: unpair server-side (cancels pending work, ADR 0007), revoke and delete
    /// the refresh token, delete the install ID and wipe every local file. With `force`, the
    /// local wipe happens even when the server cannot be reached (the user is told to
    /// unpair "Mac (iMessage)" in the Wingman app as well).
    public func disconnect(tokens: TokenManager?, force: Bool = false) async -> DisconnectResult {
        if var s = try? store.load() {
            s.settings.sendsStopped = true  // no claim from here on, whatever happens next
            try? store.save(s)
            if let id = s.pairing?.connectionId {
                do {
                    try await api.unpair(id, key: UUID().lower)
                } catch BackendError.notFound {
                    // already gone
                } catch {
                    if !force { return .serverUnreachable }
                }
            }
        }
        await tokens?.signOut()
        try? secrets.delete(SecretAccount.refreshToken)
        try? secrets.delete(SecretAccount.deviceInstallId)
        try? store.wipe()
        texts = [:]
        reader.close()
        status = .notSignedIn
        log.record(.disconnected)
        return .done
    }
}
