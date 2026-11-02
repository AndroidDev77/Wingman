import Foundation
import XCTest
@testable import WingmanCompanionCore

/// A scripted stand-in for the Wingman backend, enough for the device side of ADR 0006/0007.
final class FakeBackend: @unchecked Sendable {
    var reachable = true
    var unpaired = false
    var connectionId = CONNECTION_ID
    var pending: [OutboxInstruction] = []
    var ingested: [JSONValue] = []
    var pairBodies: [(String, JSONValue)] = []
    var loseNextPairResponse = false
    var claimStatus = 200
    lazy var http = FakeHTTP { [unowned self] req in try self.handle(req) }

    func handle(_ req: HTTPRequest) throws -> HTTPResponse {
        guard reachable else { throw Offline() }
        XCTAssertEqual(req.headers["Authorization"], "Bearer test-access-token")
        if req.method == "POST" { XCTAssertNotNil(req.headers["Idempotency-Key"], req.url.path) }
        let p = req.url.path
        switch (req.method, p) {
        case ("GET", "/v1/me"):
            return json(200, ["id": .string(USER_ID), "email": "me@example.com", "display_name": "Me", "created_at": "2026-11-01T00:00:00Z"])
        case ("POST", "/v1/connections"):
            let key = req.headers["Idempotency-Key"]!
            let first = !pairBodies.contains { $0.0 == key }
            pairBodies.append((key, bodyJSON(req)!))
            if loseNextPairResponse {
                loseNextPairResponse = false
                throw Offline()
            }
            return json(first ? 201 : 200, ["id": .string(connectionId), "platform": "imessage", "transport": "companion"])
        case ("POST", _) where p.hasSuffix("/events"):
            if unpaired, bodyJSON(req)?["type"] == "message.received" {
                return json(422, ["code": "validation_error", "errors": [["pointer": "/connection_id"]]])
            }
            ingested.append(bodyJSON(req)!)
            return HTTPResponse(status: 202)
        case ("POST", _) where p.hasSuffix("/outbox/claim"):
            if unpaired {
                return json(422, ["code": "validation_error", "errors": [["pointer": "/device_install_id"]]])
            }
            if claimStatus != 200 { return HTTPResponse(status: claimStatus) }
            let out = pending
            pending = []
            return json(200, ["instructions": .array(out.map(instructionJSON)), "server_time": .string(SERVER_TIME)])
        case ("POST", _) where p.hasSuffix("/unpair"):
            unpaired = true
            return json(200, ["id": .string(connectionId)])
        default:
            return HTTPResponse(status: 404)
        }
    }
}

final class RecordingLog: CompanionLog, @unchecked Sendable {
    private let lock = NSLock()
    private(set) var lines: [String] = []
    func record(_ event: LogEvent) {
        lock.lock()
        lines.append(event.description)
        lock.unlock()
    }
}

final class EngineTests: CompanionTestCase {
    var backend: FakeBackend!
    var secrets: MemorySecrets!
    var fake: FakeMessages!
    var log: RecordingLog!

    override func setUp() {
        super.setUp()
        backend = FakeBackend()
        secrets = MemorySecrets()
        fake = FakeMessages(fixture, clock)
        log = RecordingLog()
    }

    func engine() -> CompanionEngine {
        let api = WingmanAPI(baseURL: URL(string: "https://api.example.test")!, http: backend.http, tokens: StaticToken())
        return CompanionEngine(store: store, reader: ChatDbReader(path: fixture.path), api: api, transport: fake,
                               secrets: secrets, log: log, clock: clock.fn)
    }

    func pairedEngine() async throws -> CompanionEngine {
        let e = engine()
        try await e.pair(displayName: "Test Mac iMessage")
        await e.runCycle()  // baseline
        return e
    }

    func testPairingSendsTheCompanionShapeAndReplaysAfterALostResponse() async throws {
        backend.loseNextPairResponse = true
        let e = engine()
        do { try await e.pair(displayName: "Chris's MacBook iMessage"); XCTFail() } catch {}
        let id = try await e.pair(displayName: "ignored on replay")
        XCTAssertEqual(id, CONNECTION_ID)
        XCTAssertEqual(backend.pairBodies.count, 2)
        XCTAssertEqual(backend.pairBodies[0].0, backend.pairBodies[1].0)  // same key
        XCTAssertEqual(backend.pairBodies[0].1, backend.pairBodies[1].1)  // same body
        let body = backend.pairBodies[0].1
        XCTAssertEqual(body["platform"], "imessage")
        XCTAssertEqual(body["transport"], "companion")
        XCTAssertEqual(body["display_name"], "Chris's MacBook iMessage")
        XCTAssertEqual(body["capabilities"]?["capabilities"]?["send"]?["status"], "UNVERIFIED")
        XCTAssertEqual(body["capabilities"]?["capabilities"]?["read"]?["status"], "UNVERIFIED")
        XCTAssertEqual(body["capabilities"]?["capabilities"]?["swipe"]?["status"], "UNSUPPORTED")
        let install = body["device_install_id"]!.stringValue!
        XCTAssertEqual(secrets.values[SecretAccount.deviceInstallId], install)
        XCTAssertNotNil(UUID(uuidString: install))
        XCTAssertNil(body["capabilities"]?["capabilities"]?["send"]?["evidence"])  // never claims VERIFIED
    }

    func testInboundIsUploadedOnceAcrossRestarts() async throws {
        let e = try await pairedEngine()
        fixture.message(DM, sender: ALICE, text: "Free tonight?", at: at(1))
        clock.advance(120)
        await e.runCycle()
        XCTAssertEqual(backend.ingested.map { $0["type"]! }, ["message.received"])
        XCTAssertEqual(backend.http.requests.last { $0.url.path.hasSuffix("/events") }?.headers["Idempotency-Key"],
                       backend.ingested[0]["event_id"]?.stringValue)
        await engine().runCycle()  // restart
        await e.runCycle()
        XCTAssertEqual(backend.ingested.count, 1)
        XCTAssertTrue(try store.load().queue.isEmpty)
    }

    func testClaimExecuteAndAcknowledgeFromTheRow() async throws {
        let e = try await pairedEngine()
        backend.pending = [instruction()]
        let r = await e.runCycle()
        XCTAssertEqual(r.claimed, 1)
        XCTAssertEqual(r.outcomes, [.executed])
        XCTAssertEqual(fake.calls.count, 1)
        XCTAssertEqual(backend.ingested.map { $0["type"]! }, ["message.send_acknowledged"])
        let claim = backend.http.requests.first { $0.url.path.hasSuffix("/outbox/claim") }!
        XCTAssertEqual(bodyJSON(claim)?["device_install_id"], .string(secrets.values[SecretAccount.deviceInstallId]!))
        // The same instruction again (a replayed claim) is never sent twice.
        backend.pending = [instruction()]
        await e.runCycle()
        XCTAssertEqual(fake.calls.count, 1)
    }

    func testKillSwitchStopsClaimsImmediately() async throws {
        let e = try await pairedEngine()
        let claimsBefore = backend.http.paths("POST").filter { $0.hasSuffix("/outbox/claim") }.count
        try await e.setSendsStopped(true)
        backend.pending = [instruction()]
        let r = await e.runCycle()
        XCTAssertEqual(r.skippedClaim, "sends_stopped")
        XCTAssertEqual(backend.http.paths("POST").filter { $0.hasSuffix("/outbox/claim") }.count, claimsBefore)
        XCTAssertTrue(fake.calls.isEmpty)
        // Reading and uploading continue.
        fixture.message(DM, sender: ALICE, text: "still read", at: at(1))
        clock.advance(120)
        await e.runCycle()
        XCTAssertEqual(backend.ingested.count, 1)
    }

    func testPausedSwitchDoesNothingAtAll() async throws {
        let e = try await pairedEngine()
        let before = backend.http.requests.count
        try await e.setEnabled(false)
        fixture.message(DM, sender: ALICE, text: "x", at: at(1))
        await e.runCycle()
        XCTAssertEqual(backend.http.requests.count, before)
        let status = await e.status
        XCTAssertEqual(status, .paused)
    }

    func testDraftOnlyAnnouncesSendUnsupportedAndNeverClaims() async throws {
        let e = try await pairedEngine()
        try await e.setDraftOnly(true)
        backend.pending = [instruction()]
        let r = await e.runCycle()
        XCTAssertEqual(r.skippedClaim, "draft_only")
        let caps = backend.ingested.first { $0["type"] == "connection.capabilities_changed" }
        XCTAssertEqual(caps?["payload"]?["capabilities"]?["capabilities"]?["send"]?["status"], "UNSUPPORTED")
        XCTAssertTrue(fake.calls.isEmpty)
    }

    func testFullDiskAccessRevokedLowersReadAndStopsClaims() async throws {
        let e = try await pairedEngine()
        try FileManager.default.removeItem(at: fixture.path)  // as if access was revoked
        backend.pending = [instruction()]
        let r = await e.runCycle()
        XCTAssertEqual(r.skippedClaim, "chat_db_unavailable")
        let caps = backend.ingested.filter { $0["type"] == "connection.capabilities_changed" }
        XCTAssertEqual(caps.count, 1)
        XCTAssertEqual(caps[0]["payload"]?["capabilities"]?["capabilities"]?["read"]?["status"], "UNSUPPORTED")
        let status = await e.status
        XCTAssertEqual(status, .needsFullDiskAccess)
        await e.runCycle()  // announced once, not every cycle
        XCTAssertEqual(backend.ingested.filter { $0["type"] == "connection.capabilities_changed" }.count, 1)
    }

    func testAutomationDeniedIsReportedAndClaimsStop() async throws {
        let e = try await pairedEngine()
        fake.result = ExecResult(exitCode: 1, appleEventError: -1743)
        backend.pending = [instruction()]
        await e.runCycle()
        let types = backend.ingested.map { $0["type"]!.stringValue! }
        XCTAssertTrue(types.contains("message.send_failed"))
        XCTAssertTrue(types.contains("connection.capabilities_changed"))
        backend.pending = [instruction(key: "send:0192f3a0-0000-7000-8000-00000000aa01:2")]
        let r = await e.runCycle()
        XCTAssertEqual(r.skippedClaim, "automation_denied")
        XCTAssertEqual(fake.calls.count, 1)
    }

    func testBackendUnreachableBacksOffAndKeepsTheQueue() async throws {
        let e = try await pairedEngine()
        backend.reachable = false
        fixture.message(DM, sender: ALICE, text: "queued while offline", at: at(1))
        clock.advance(120)
        await e.runCycle()
        XCTAssertEqual(try store.load().queue.count, 1)
        var status = await e.status
        guard case .backendUnreachable = status else { return XCTFail("\(status)") }
        let n = backend.http.requests.count
        await e.runCycle()  // inside the backoff window: no request at all
        XCTAssertEqual(backend.http.requests.count, n)
        backend.reachable = true
        clock.advance(3600)
        await e.runCycle()
        XCTAssertEqual(backend.ingested.count, 1)
        status = await e.status
        XCTAssertEqual(status, .running(sendsStopped: false, draftOnly: false))
    }

    func testServerSideUnpairStopsClaims() async throws {
        let e = try await pairedEngine()
        backend.unpaired = true
        backend.pending = [instruction()]
        await e.runCycle()
        let status = await e.status
        XCTAssertEqual(status, .unpaired)
        XCTAssertNil(try store.load().pairing)
        XCTAssertTrue(fake.calls.isEmpty)
        let claims = backend.http.paths("POST").filter { $0.hasSuffix("/outbox/claim") }.count
        await e.runCycle()
        XCTAssertEqual(backend.http.paths("POST").filter { $0.hasSuffix("/outbox/claim") }.count, claims)
    }

    func testDisconnectUnpairsThenWipesKeychainAndState() async throws {
        let e = try await pairedEngine()
        secrets.values[SecretAccount.refreshToken] = "rt"
        let result = await e.disconnect(tokens: nil)
        XCTAssertEqual(result, .done)
        XCTAssertTrue(backend.unpaired)
        XCTAssertTrue(secrets.values.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.url.path))
    }

    func testDisconnectWhileOfflineWipesNothingUnlessForced() async throws {
        let e = try await pairedEngine()
        secrets.values[SecretAccount.refreshToken] = "rt"
        backend.reachable = false
        let first = await e.disconnect(tokens: nil)
        XCTAssertEqual(first, .serverUnreachable)
        XCTAssertEqual(secrets.values[SecretAccount.refreshToken], "rt")
        XCTAssertTrue(try store.load().settings.sendsStopped)  // but no more claims
        let forced = await e.disconnect(tokens: nil, force: true)
        XCTAssertEqual(forced, .done)
        XCTAssertTrue(secrets.values.isEmpty)
    }

    func testLockedKeychainWaits() async throws {
        let e = engine()
        savePaired { $0.deviceInstallId = nil }
        secrets.locked = true
        await e.runCycle()
        let status = await e.status
        XCTAssertEqual(status, .keychainLocked)
    }

    func testLogsNeverContainMessageData() async throws {
        let e = try await pairedEngine()
        fixture.message(DM, sender: ALICE, text: "very private words", at: at(1))
        clock.advance(120)
        backend.pending = [instruction(text: "secret reply text")]
        fake.result = ExecResult(exitCode: 1, appleEventError: -1728)
        await e.runCycle()
        let all = log.lines.joined(separator: "\n")
        XCTAssertFalse(log.lines.isEmpty)
        for needle in ["private", "secret", ALICE, "iMessage;-;", "15550100001", "test-access-token"] {
            XCTAssertFalse(all.contains(needle), needle)
        }
    }
}

final class AuthTests: XCTestCase {
    let config = Auth0Config(issuer: URL(string: "https://tenant.example.auth0.com/")!, clientId: "mac-client",
                             audience: "https://api.wingman.app", redirectURI: "com.wingman.companion:/oauth2redirect",
                             connection: "google-oauth2")

    func testAuthorizeURLUsesPKCEAndOfflineAccess() {
        let p = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", state: "st")
        let url = Auth0Requests.authorizeURL(config, pkce: p)
        let q = Dictionary(uniqueKeysWithValues: URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!.map { ($0.name, $0.value!) })
        XCTAssertEqual(url.host, "tenant.example.auth0.com")
        XCTAssertEqual(url.path, "/authorize")
        XCTAssertEqual(q["code_challenge_method"], "S256")
        XCTAssertEqual(q["code_challenge"], "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        XCTAssertEqual(q["client_id"], "mac-client")
        XCTAssertEqual(q["redirect_uri"], "com.wingman.companion:/oauth2redirect")
        XCTAssertEqual(q["audience"], "https://api.wingman.app")
        XCTAssertTrue(q["scope"]!.contains("offline_access"))
        XCTAssertNil(q["client_secret"])
        XCTAssertEqual(config.callbackScheme, "com.wingman.companion")
    }

    func testCallbackStateIsChecked() throws {
        XCTAssertEqual(try Auth0Requests.code(fromCallback: URL(string: "com.wingman.companion:/oauth2redirect?code=abc&state=st")!,
                                              expectedState: "st"), "abc")
        XCTAssertThrowsError(try Auth0Requests.code(fromCallback: URL(string: "com.wingman.companion:/x?code=abc&state=evil")!,
                                                    expectedState: "st"))
        XCTAssertThrowsError(try Auth0Requests.code(fromCallback: URL(string: "com.wingman.companion:/x?error=access_denied&state=st")!,
                                                    expectedState: "st"))
    }

    func testSignInStoresOnlyTheRefreshTokenAndRotates() async throws {
        let secrets = MemorySecrets()
        var issued = 0
        let http = FakeHTTP { req in
            XCTAssertEqual(req.url.path, "/oauth/token")
            let form = String(data: req.body!, encoding: .utf8)!
            XCTAssertFalse(form.contains("client_secret"))
            issued += 1
            return json(200, ["access_token": .string("at\(issued)"), "refresh_token": .string("rt\(issued)"), "expires_in": 1])
        }
        let tm = TokenManager(config: config, http: http, secrets: secrets)
        let p = PKCE.random()
        try await tm.completeSignIn(callback: URL(string: "com.wingman.companion:/oauth2redirect?code=c&state=\(p.state)")!, pkce: p)
        XCTAssertEqual(secrets.values, [SecretAccount.refreshToken: "rt1"])
        let form = String(data: http.requests[0].body!, encoding: .utf8)!
        XCTAssertTrue(form.contains("code_verifier=\(p.verifier)"))
        // expires_in 1 s: the next call refreshes and stores the rotated token.
        let t = try await tm.accessToken()
        XCTAssertEqual(t, "at2")
        XCTAssertEqual(secrets.values[SecretAccount.refreshToken], "rt2")
    }

    func testInvalidGrantSignsOut() async {
        let secrets = MemorySecrets()
        secrets.values[SecretAccount.refreshToken] = "revoked"
        let tm = TokenManager(config: config, http: FakeHTTP { _ in json(403, ["error": "invalid_grant"]) }, secrets: secrets)
        do { _ = try await tm.accessToken(); XCTFail() } catch { XCTAssertEqual(error as? AuthError, .signedOut) }
        XCTAssertNil(secrets.values[SecretAccount.refreshToken])
    }

    func testLockedKeychainSurfacesAsLocked() async {
        let secrets = MemorySecrets()
        secrets.locked = true
        let tm = TokenManager(config: config, http: FakeHTTP { _ in throw Offline() }, secrets: secrets)
        do { _ = try await tm.accessToken(); XCTFail() } catch { XCTAssertEqual(error as? AuthError, .keychainLocked) }
    }

    func testAPIRetriesOnceAfter401WithAFreshToken() async throws {
        actor Counting: AccessTokenProvider {
            var n = 0
            var invalidated = 0
            func accessToken() async throws -> String { n += 1; return "t\(n)" }
            func invalidate() async { invalidated += 1 }
        }
        let tokens = Counting()
        let http = FakeHTTP { req in
            req.headers["Authorization"] == "Bearer t1" ? HTTPResponse(status: 401) : json(200, ["id": .string(USER_ID)])
        }
        let api = WingmanAPI(baseURL: URL(string: "https://api.example.test")!, http: http, tokens: tokens)
        let me = try await api.me()
        XCTAssertEqual(me, USER_ID)
        let inv = await tokens.invalidated
        XCTAssertEqual(inv, 1)
    }

    func testIngestAnswerMapping() async {
        for (status, body, expected): (Int, JSONValue, UploadAnswer) in [
            (202, [:], .accepted), (200, [:], .duplicate), (400, [:], .permanent), (404, [:], .permanent),
            (422, ["errors": [["pointer": "/payload/text"]]], .permanent),
            (422, ["errors": [["pointer": "/connection_id"]]], .unpaired),
            (429, [:], .retry), (500, [:], .retry), (409, [:], .retry),
        ] {
            let api = WingmanAPI(baseURL: URL(string: "https://api.example.test")!,
                                 http: FakeHTTP { _ in json(status, body) }, tokens: StaticToken())
            let answer = await api.ingest(["event_id": "e", "connection_id": "c"])
            XCTAssertEqual(answer, expected, "\(status)")
        }
        let offline = WingmanAPI(baseURL: URL(string: "https://api.example.test")!, http: FakeHTTP { _ in throw Offline() },
                                 tokens: StaticToken())
        let answer = await offline.ingest(["event_id": "e", "connection_id": "c"])
        XCTAssertEqual(answer, .retry)
    }

    func testBackoffGrowsAndCaps() {
        var b = Backoff(base: 5, cap: 60)
        let now = at(0)
        b.fail(now: now, jitter: 1)
        XCTAssertEqual(b.notBefore, now.addingTimeInterval(5))
        b.fail(now: now, jitter: 1)
        b.fail(now: now, jitter: 1)
        XCTAssertEqual(b.notBefore, now.addingTimeInterval(20))
        for _ in 0..<10 { b.fail(now: now, jitter: 1) }
        XCTAssertEqual(b.notBefore, now.addingTimeInterval(60))
        XCTAssertFalse(b.ready(at: now))
        b.succeed()
        XCTAssertTrue(b.ready(at: now))
    }
}
