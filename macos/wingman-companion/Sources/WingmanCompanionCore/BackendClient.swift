import Foundation

/// Minimal HTTP types so Core needs no URLSession (the app adapts `URLSession`; tests use a
/// fake). Outbound requests only: the companion never listens on a port.
public struct HTTPRequest: Sendable, Equatable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: Data?
    public init(method: String, url: URL, headers: [String: String] = [:], body: Data? = nil) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
    }
}

public struct HTTPResponse: Sendable, Equatable {
    public var status: Int
    public var body: Data
    public init(status: Int, body: Data = Data()) {
        self.status = status
        self.body = body
    }
    public var json: JSONValue? { try? JSONValue.decode(body) }
}

public protocol HTTPTransport: Sendable {
    /// Throws only for transport failures (offline, DNS, TLS, timeout).
    func perform(_ request: HTTPRequest) async throws -> HTTPResponse
}

public protocol AccessTokenProvider: Sendable {
    /// A valid access token, refreshing if needed. Throws `AuthError`.
    func accessToken() async throws -> String
    /// Drop the cached access token after a 401 so the next call refreshes.
    func invalidate() async
}

public enum BackendError: Error, Equatable, Sendable {
    case unreachable  // network failure or 5xx/429: retry with backoff
    case unauthorized  // 401 after a refresh: sign in again
    case notFound  // 404: the connection is gone (another user's, or deleted)
    case rejected(status: Int, code: String?, pointer: String?)  // other 4xx
    case malformed
}

/// The `/v1` routes the companion uses (contract v1.3.1). Every POST carries an
/// `Idempotency-Key` (ADR 0004).
public struct WingmanAPI: Sendable {
    public let baseURL: URL
    let http: HTTPTransport
    let tokens: AccessTokenProvider

    public init(baseURL: URL, http: HTTPTransport, tokens: AccessTokenProvider) {
        self.baseURL = baseURL
        self.http = http
        self.tokens = tokens
    }

    func call(_ method: String, _ path: String, body: JSONValue? = nil, key: String? = nil) async throws -> HTTPResponse {
        var headers = ["Accept": "application/json"]
        if let key { headers["Idempotency-Key"] = key }
        var data: Data?
        if let body {
            data = try body.encoded()
            headers["Content-Type"] = "application/json"
        }
        for attempt in 0..<2 {
            headers["Authorization"] = "Bearer \(try await tokens.accessToken())"
            let resp: HTTPResponse
            do {
                resp = try await http.perform(HTTPRequest(method: method, url: baseURL.appendingPathComponent(path),
                                                          headers: headers, body: data))
            } catch {
                throw BackendError.unreachable
            }
            if resp.status == 401 && attempt == 0 {
                await tokens.invalidate()
                continue
            }
            return resp
        }
        throw BackendError.unauthorized
    }

    static func problem(_ r: HTTPResponse) -> BackendError {
        switch r.status {
        case 401: return .unauthorized
        case 404: return .notFound
        case 408, 409, 425, 429, 500...: return .unreachable
        default:
            let p = r.json
            let pointer = p?["errors"]?.arrayValue?.first?["pointer"]?.stringValue
            return .rejected(status: r.status, code: p?["code"]?.stringValue, pointer: pointer)
        }
    }

    /// `GET /v1/me`: the user ID that goes into every envelope.
    public func me() async throws -> String {
        let r = try await call("GET", "v1/me")
        guard r.status == 200 else { throw Self.problem(r) }
        guard let id = r.json?["id"]?.stringValue else { throw BackendError.malformed }
        return id
    }

    /// `createConnection`: 201 new, 200 already paired for this install.
    public func createConnection(body: JSONValue, key: String) async throws -> JSONValue {
        let r = try await call("POST", "v1/connections", body: body, key: key)
        guard r.status == 200 || r.status == 201, let j = r.json else { throw Self.problem(r) }
        return j
    }

    public func getConnection(_ id: String) async throws -> JSONValue {
        let r = try await call("GET", "v1/connections/\(id)")
        guard r.status == 200, let j = r.json else { throw Self.problem(r) }
        return j
    }

    /// `unpairConnection`: effective before the response (ADR 0007).
    public func unpair(_ id: String, key: String) async throws {
        let r = try await call("POST", "v1/connections/\(id)/unpair", body: .object([:]), key: key)
        guard r.status == 200 else { throw Self.problem(r) }
    }

    /// `ingestConnectorEvent` with `Idempotency-Key = event_id`, mapped per WING-016.
    public func ingest(_ envelope: JSONValue) async -> UploadAnswer {
        guard let id = envelope["event_id"]?.stringValue, let conn = envelope["connection_id"]?.stringValue else {
            return .permanent
        }
        do {
            let r = try await call("POST", "v1/connections/\(conn)/events", body: envelope, key: id)
            switch r.status {
            case 202: return .accepted
            case 200: return .duplicate
            case 422 where Self.pointer(r) == "/connection_id": return .unpaired
            case 400, 404, 422: return .permanent
            default: return .retry
            }
        } catch {
            return .retry
        }
    }

    static func pointer(_ r: HTTPResponse) -> String? {
        r.json?["errors"]?.arrayValue?.first?["pointer"]?.stringValue
    }

    public struct ClaimResult: Sendable, Equatable {
        public var instructions: [OutboxInstruction]
        public var serverTime: String
    }

    /// `claimOutboxInstructions`. The server re-checks pause, takeover, approvals and the
    /// install binding (D-014, D-017) at claim time; a `422` at `/device_install_id` means
    /// this install may not claim (unpaired or re-paired elsewhere).
    public func claim(connectionId: String, deviceInstallId: String, max: Int, key: String) async throws -> ClaimResult {
        let r = try await call("POST", "v1/connections/\(connectionId)/outbox/claim",
                               body: ["device_install_id": .string(deviceInstallId), "max_instructions": .int(max)],
                               key: key)
        guard r.status == 200, let j = r.json else { throw Self.problem(r) }
        guard let st = j["server_time"]?.stringValue, let arr = j["instructions"]?.arrayValue else {
            throw BackendError.malformed
        }
        return ClaimResult(instructions: arr.compactMap(OutboxInstruction.init), serverTime: st)
    }
}

/// Exponential backoff with full jitter, capped. The jitter source is injected for tests.
public struct Backoff: Sendable, Equatable {
    public var base: TimeInterval
    public var cap: TimeInterval
    public private(set) var failures = 0
    public private(set) var notBefore: Date?

    public init(base: TimeInterval = 5, cap: TimeInterval = 15 * 60) {
        self.base = base
        self.cap = cap
    }

    public func ready(at now: Date) -> Bool { notBefore.map { now >= $0 } ?? true }

    /// Upper bound of the next delay (before jitter).
    public var ceiling: TimeInterval { min(cap, base * pow(2, Double(max(0, failures - 1)))) }

    public mutating func fail(now: Date, jitter: Double = Double.random(in: 0.5...1.0)) {
        failures += 1
        notBefore = now.addingTimeInterval(ceiling * min(1, max(0, jitter)))
    }

    public mutating func succeed() {
        failures = 0
        notBefore = nil
    }
}
