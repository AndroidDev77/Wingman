import Foundation

/// Sign-in with the user's Wingman account: OAuth 2.0 authorization code + PKCE (RFC 7636,
/// `S256`) in the system browser (the app uses `ASWebAuthenticationSession`), against the
/// same Auth0 tenant and API audience as Android, with the Mac's OWN Native application
/// and redirect URI (docs/integrations/imessage_companion.md §3). The refresh token goes in
/// the Keychain (`SecretStore`); the access token stays in memory.
public struct Auth0Config: Sendable, Equatable, Codable {
    /// e.g. `https://dev-zumw4z1t7m8hefst.us.auth0.com/` (trailing slash, as the backend issuer).
    public var issuer: URL
    public var clientId: String
    public var audience: String
    /// e.g. `com.wingman.companion:/oauth2redirect`.
    public var redirectURI: String
    /// e.g. `google-oauth2`; nil lets Auth0 show its login page.
    public var connection: String?

    public init(issuer: URL, clientId: String, audience: String, redirectURI: String, connection: String? = nil) {
        self.issuer = issuer
        self.clientId = clientId
        self.audience = audience
        self.redirectURI = redirectURI
        self.connection = connection
    }

    public var callbackScheme: String { String(redirectURI.prefix { $0 != ":" }) }
    var authorizeURL: URL { issuer.appendingPathComponent("authorize") }
    var tokenURL: URL { issuer.appendingPathComponent("oauth/token") }
    var revokeURL: URL { issuer.appendingPathComponent("oauth/revoke") }
}

public struct PKCE: Sendable, Equatable {
    public let verifier: String
    public let challenge: String
    public let state: String

    public init(verifier: String, state: String) {
        self.verifier = verifier
        self.challenge = base64url(Digest.sha256(Array(verifier.utf8)))
        self.state = state
    }

    /// 32 random bytes each (43-character verifier, RFC 7636 §4.1).
    public static func random() -> PKCE {
        var g = SystemRandomNumberGenerator()
        func rnd() -> String { base64url((0..<32).map { _ in UInt8.random(in: 0...255, using: &g) }) }
        return PKCE(verifier: rnd(), state: rnd())
    }
}

public enum AuthError: Error, Equatable, Sendable {
    case signedOut  // no refresh token: the user must sign in
    case keychainLocked  // wait for unlock; never fall back to a file
    case stateMismatch
    case denied(String)
    case network
    case malformed
}

public enum Auth0Requests {
    public static func authorizeURL(_ c: Auth0Config, pkce: PKCE) -> URL {
        var comps = URLComponents(url: c.authorizeURL, resolvingAgainstBaseURL: false)!
        var items = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: c.clientId),
            URLQueryItem(name: "redirect_uri", value: c.redirectURI),
            URLQueryItem(name: "audience", value: c.audience),
            URLQueryItem(name: "scope", value: "openid profile email offline_access"),
            URLQueryItem(name: "code_challenge", value: pkce.challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: pkce.state),
        ]
        if let conn = c.connection { items.append(URLQueryItem(name: "connection", value: conn)) }
        comps.queryItems = items
        return comps.url!
    }

    /// Extracts the code from the redirect, checking `state` (CSRF).
    public static func code(fromCallback url: URL, expectedState: String) throws -> String {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func v(_ n: String) -> String? { items.first { $0.name == n }?.value }
        if let err = v("error") { throw AuthError.denied(err) }
        guard v("state") == expectedState else { throw AuthError.stateMismatch }
        guard let code = v("code"), !code.isEmpty else { throw AuthError.malformed }
        return code
    }

    static func form(_ fields: [(String, String)]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return Data(fields.map { k, v in
            "\(k)=\(v.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
        }.joined(separator: "&").utf8)
    }

    public static func exchange(_ c: Auth0Config, code: String, pkce: PKCE) -> HTTPRequest {
        HTTPRequest(method: "POST", url: c.tokenURL, headers: ["Content-Type": "application/x-www-form-urlencoded"],
                    body: form([("grant_type", "authorization_code"), ("client_id", c.clientId),
                                ("code_verifier", pkce.verifier), ("code", code), ("redirect_uri", c.redirectURI)]))
    }

    public static func refresh(_ c: Auth0Config, refreshToken: String) -> HTTPRequest {
        HTTPRequest(method: "POST", url: c.tokenURL, headers: ["Content-Type": "application/x-www-form-urlencoded"],
                    body: form([("grant_type", "refresh_token"), ("client_id", c.clientId),
                                ("refresh_token", refreshToken)]))
    }

    public static func revoke(_ c: Auth0Config, refreshToken: String) -> HTTPRequest {
        HTTPRequest(method: "POST", url: c.revokeURL, headers: ["Content-Type": "application/json"],
                    body: try? JSONValue.object(["client_id": .string(c.clientId), "token": .string(refreshToken)]).encoded())
    }
}

public struct TokenResponse: Sendable, Equatable {
    public var accessToken: String
    public var refreshToken: String?
    public var expiresIn: TimeInterval

    public init?(_ r: HTTPResponse) {
        guard r.status == 200, let j = r.json, let at = j["access_token"]?.stringValue else { return nil }
        accessToken = at
        refreshToken = j["refresh_token"]?.stringValue
        expiresIn = TimeInterval(j["expires_in"]?.intValue ?? 3600)
    }
}

/// Where secrets live. The app implements it with the login Keychain:
/// generic password, `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`,
/// `kSecAttrSynchronizable = false`. Throws `AuthError.keychainLocked` when locked.
public protocol SecretStore: Sendable {
    func read(_ account: String) throws -> String?
    func write(_ account: String, _ value: String) throws
    func delete(_ account: String) throws
}

public enum SecretAccount {
    public static let refreshToken = "auth0.refresh_token"
    public static let deviceInstallId = "device_install_id"
}

/// Access token in memory only; refreshed from the Keychain's refresh token.
public actor TokenManager: AccessTokenProvider {
    let config: Auth0Config
    let http: HTTPTransport
    let secrets: SecretStore
    let clock: @Sendable () -> Date
    private var cached: (token: String, expires: Date)?

    public init(config: Auth0Config, http: HTTPTransport, secrets: SecretStore,
                clock: @escaping @Sendable () -> Date = Date.init) {
        self.config = config
        self.http = http
        self.secrets = secrets
        self.clock = clock
    }

    public func accessToken() async throws -> String {
        if let c = cached, c.expires.timeIntervalSince(clock()) > 60 { return c.token }
        guard let rt = try secrets.read(SecretAccount.refreshToken) else { throw AuthError.signedOut }
        let resp: HTTPResponse
        do { resp = try await http.perform(Auth0Requests.refresh(config, refreshToken: rt)) } catch { throw AuthError.network }
        if resp.status == 400 || resp.status == 401 || resp.status == 403 {
            // invalid_grant: revoked or expired. Sign in again; keep nothing.
            try? secrets.delete(SecretAccount.refreshToken)
            throw AuthError.signedOut
        }
        guard let t = TokenResponse(resp) else { throw AuthError.network }
        if let rotated = t.refreshToken { try secrets.write(SecretAccount.refreshToken, rotated) }
        cached = (t.accessToken, clock().addingTimeInterval(t.expiresIn))
        return t.accessToken
    }

    public func invalidate() async { cached = nil }

    /// Completes sign-in after the browser redirect.
    public func completeSignIn(callback: URL, pkce: PKCE) async throws {
        let code = try Auth0Requests.code(fromCallback: callback, expectedState: pkce.state)
        let resp: HTTPResponse
        do { resp = try await http.perform(Auth0Requests.exchange(config, code: code, pkce: pkce)) } catch { throw AuthError.network }
        guard let t = TokenResponse(resp) else { throw AuthError.denied("token exchange failed") }
        guard let rt = t.refreshToken else { throw AuthError.denied("no refresh token (enable offline access)") }
        try secrets.write(SecretAccount.refreshToken, rt)
        cached = (t.accessToken, clock().addingTimeInterval(t.expiresIn))
    }

    /// Disconnect: revoke the refresh token at Auth0 (best effort), then delete it.
    public func signOut() async {
        if let rt = try? secrets.read(SecretAccount.refreshToken) {
            _ = try? await http.perform(Auth0Requests.revoke(config, refreshToken: rt))
        }
        try? secrets.delete(SecretAccount.refreshToken)
        cached = nil
    }
}
