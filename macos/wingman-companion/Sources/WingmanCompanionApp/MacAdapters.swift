// macOS adapters for the Core protocols. Everything in this file is UNVERIFIED: it has been
// compiled (CI, macOS runner) but never run against a real Messages, Keychain or Auth0.
import AppKit
import AuthenticationServices
import Carbon
import Foundation
import OSLog
import Security
import ServiceManagement
import WingmanCompanionCore

// MARK: - Apple events (in-process, so the Automation grant belongs to this app)

/// Runs the constant `AppleScriptCommand.sendToChatSource`, compiled once, through its
/// `on run argv` handler with a two-item list descriptor. Never spawns `osascript`, never
/// formats user text into source. NSAppleScript is main-thread only.
@MainActor
final class AppleScriptTransport: MessagesTransport {
    private var compiled: NSAppleScript?

    private func script() throws -> NSAppleScript {
        if let compiled { return compiled }
        guard let s = NSAppleScript(source: AppleScriptCommand.sendToChatSource) else {
            throw TransportError.compileFailed
        }
        var err: NSDictionary?
        guard s.compileAndReturnError(&err) else { throw TransportError.compileFailed }
        compiled = s
        return s
    }

    enum TransportError: Error { case compileFailed }

    func send(_ command: AppleScriptCommand.SendCommand) async throws -> ExecResult {
        // A script that does not compile (e.g. Messages' dictionary changed) is a definite
        // non-send: report it as a failed attempt (transport_error), never as an unknown.
        guard let s = try? script() else { return ExecResult(exitCode: 1, appleEventError: nil) }
        let argv = NSAppleEventDescriptor.list()
        for (i, arg) in command.arguments.enumerated() {
            argv.insert(NSAppleEventDescriptor(string: arg), at: i + 1)
        }
        // The `run` handler is the 'aevt'/'oapp' event; its direct parameter is argv.
        let event = NSAppleEventDescriptor.appleEvent(
            withEventClass: AEEventClass(kCoreEventClass),
            eventID: AEEventID(kAEOpenApplication),
            targetDescriptor: NSAppleEventDescriptor.currentProcess(),
            returnID: AEReturnID(kAutoGenerateReturnID),
            transactionID: AETransactionID(kAnyTransactionID))
        event.setParam(argv, forKeyword: AEKeyword(keyDirectObject))
        var err: NSDictionary?
        _ = s.executeAppleEvent(event, error: &err)
        if let err {
            let n = (err[NSAppleScript.errorNumber] as? NSNumber)?.intValue
            return ExecResult(exitCode: 1, appleEventError: n)
        }
        return ExecResult(exitCode: 0)
    }
}

// MARK: - Keychain

/// Generic passwords in the data-protection keychain, this device only, never synced.
/// A locked keychain is reported as `AuthError.keychainLocked`; there is no file fallback.
struct KeychainSecretStore: SecretStore {
    let service: String

    private func base(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
            kSecAttrSynchronizable as String: false,
        ]
    }

    private func check(_ status: OSStatus) throws {
        switch status {
        case errSecSuccess, errSecItemNotFound: return
        case errSecInteractionNotAllowed: throw AuthError.keychainLocked
        default: throw KeychainError(status: status)
        }
    }

    struct KeychainError: Error { let status: OSStatus }

    func read(_ account: String) throws -> String? {
        var q = base(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        try check(status)
        guard let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ account: String, _ value: String) throws {
        let data = Data(value.utf8)
        let update = SecItemUpdate(base(account) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            var add = base(account)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            try check(SecItemAdd(add as CFDictionary, nil))
        } else {
            try check(update)
        }
    }

    func delete(_ account: String) throws {
        try check(SecItemDelete(base(account) as CFDictionary))
    }
}

// MARK: - HTTPS (outbound only)

struct URLSessionTransport: HTTPTransport {
    let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.ephemeral  // no cookie or URL cache on disk
        cfg.timeoutIntervalForRequest = 30
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
    }

    func perform(_ request: HTTPRequest) async throws -> HTTPResponse {
        guard request.url.scheme == "https" || request.url.host == "127.0.0.1" || request.url.host == "localhost" else {
            throw URLError(.secureConnectionFailed)  // never plain HTTP to a remote host
        }
        var r = URLRequest(url: request.url)
        r.httpMethod = request.method
        r.httpBody = request.body
        for (k, v) in request.headers { r.setValue(v, forHTTPHeaderField: k) }
        let (data, resp) = try await session.data(for: r)
        return HTTPResponse(status: (resp as? HTTPURLResponse)?.statusCode ?? 0, body: data)
    }
}

// MARK: - Sign-in in the system browser

@MainActor
final class WebSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { NSApp.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor() }
    }

    /// Opens Auth0's authorize page; returns the redirect URL.
    func run(_ config: Auth0Config, pkce: PKCE) async throws -> URL {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<URL, Error>) in
            let s = ASWebAuthenticationSession(url: Auth0Requests.authorizeURL(config, pkce: pkce),
                                               callbackURLScheme: config.callbackScheme) { url, error in
                if let url { cont.resume(returning: url) } else { cont.resume(throwing: error ?? AuthError.malformed) }
            }
            s.presentationContextProvider = self
            s.prefersEphemeralWebBrowserSession = false
            session = s
            if !s.start() { cont.resume(throwing: AuthError.malformed) }
        }
    }
}

// MARK: - Start at login (one signed binary, one TCC identity)

enum LoginItem {
    static var enabled: Bool { SMAppService.mainApp.status == .enabled }

    static func set(_ on: Bool) throws {
        if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
    }
}

// MARK: - Logs: IDs, counts and codes only

final class MemoryLog: CompanionLog, @unchecked Sendable {
    private let lock = NSLock()
    private var buffer: [(Date, String)] = []
    private let logger = Logger(subsystem: "ai.wingman.companion", category: "engine")

    func record(_ event: LogEvent) {
        let line = event.description  // LogEvent cannot carry message data
        logger.info("\(line, privacy: .public)")
        lock.withLock {
            buffer.append((Date(), line))
            if buffer.count > 50 { buffer.removeFirst(buffer.count - 50) }
        }
    }

    var recent: [(Date, String)] { lock.withLock { buffer } }
    func clear() { lock.withLock { buffer.removeAll() } }
}

// MARK: - Configuration from Info.plist (values in Packaging/Info.plist)

struct AppConfig {
    let apiBaseURL: URL?
    let auth0: Auth0Config?

    static func load(_ info: [String: Any] = Bundle.main.infoDictionary ?? [:]) -> AppConfig {
        func s(_ k: String) -> String? {
            let v = (info[k] as? String) ?? ProcessInfo.processInfo.environment[k]
            guard let v, !v.isEmpty, !v.hasPrefix("REPLACE_") else { return nil }
            return v
        }
        var auth: Auth0Config?
        if let issuer = s("WingmanAuth0Issuer").flatMap(URL.init(string:)), let client = s("WingmanAuth0ClientId"),
           let audience = s("WingmanAuth0Audience"), let redirect = s("WingmanAuth0RedirectURI") {
            auth = Auth0Config(issuer: issuer, clientId: client, audience: audience, redirectURI: redirect,
                               connection: s("WingmanAuth0Connection"))
        }
        return AppConfig(apiBaseURL: s("WingmanAPIBaseURL").flatMap(URL.init(string:)), auth0: auth)
    }
}

// MARK: - System Settings deep links (no app can grant these itself)

enum SystemSettings {
    static func openFullDiskAccess() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")
    }

    static func openAutomation() {
        open("x-apple.systempreferences:com.apple.preference.security?Privacy_Automation")
    }

    private static func open(_ s: String) {
        if let u = URL(string: s) { NSWorkspace.shared.open(u) }
    }
}
