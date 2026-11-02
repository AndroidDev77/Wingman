import AppKit
import Foundation
import SwiftUI
import WingmanCompanionCore

/// UI state and the background loop. All decisions live in `CompanionEngine` (Core).
@MainActor
final class AppModel: ObservableObject {
    @Published var status: CompanionStatus = .notSignedIn
    @Published var signedIn = false
    @Published var enabled = true
    @Published var sendsStopped = false
    @Published var draftOnly = false
    @Published var startAtLogin = LoginItem.enabled
    @Published var fullDiskAccess: Bool?
    @Published var automationGranted: Bool?
    @Published var recent: [(Date, String)] = []
    @Published var lastProblem: String?
    @Published var busy = false

    let config = AppConfig.load()
    let log = MemoryLog()
    let store = StateStore(url: StateStore.defaultURL())
    let secrets = KeychainSecretStore(service: "ai.wingman.companion")
    private let signIn = WebSignIn()
    private var tokens: TokenManager?
    private var engine: CompanionEngine?
    private var loop: Task<Void, Never>?
    private var walWatcher: DispatchSourceFileSystemObject?
    static let interval: Duration = .seconds(30)

    init() {
        if let auth = config.auth0, let base = config.apiBaseURL {
            let http = URLSessionTransport()
            let tm = TokenManager(config: auth, http: http, secrets: secrets)
            tokens = tm
            engine = CompanionEngine(store: store, reader: ChatDbReader(path: ChatDbReader.defaultPath),
                                     api: WingmanAPI(baseURL: base, http: http, tokens: tm),
                                     transport: AppleScriptTransport(), secrets: secrets, log: log)
        } else {
            lastProblem = "This build has no Auth0 or API configuration (see imessage_companion.md §3)."
        }
        refreshLocal()
        signedIn = ((try? secrets.read(SecretAccount.refreshToken)) ?? nil) != nil
        startLoop()
    }

    var configured: Bool { engine != nil }

    func refreshLocal() {
        guard let s = try? store.load() else { return }
        enabled = s.settings.enabled
        sendsStopped = s.settings.sendsStopped
        draftOnly = s.settings.draftOnly
        fullDiskAccess = s.health.fullDiskAccess
        automationGranted = s.health.automationGranted
        recent = log.recent.reversed()
    }

    // MARK: loop

    func startLoop() {
        loop?.cancel()
        watchWAL()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.cycle()
                try? await Task.sleep(for: AppModel.interval)
            }
        }
    }

    func cycle() async {
        guard let engine else { return }
        await engine.runCycle()
        status = await engine.status
        if case .notSignedIn = status { signedIn = false }
        refreshLocal()
    }

    /// `chat.db-wal` changes are a wake-up hint only; the 30 s poll stays the source of truth.
    private func watchWAL() {
        walWatcher?.cancel()
        let path = ChatDbReader.defaultPath.path + "-wal"
        let fd = open(path, O_EVTONLY)
        guard fd >= 0 else { return }  // no Full Disk Access yet, or Messages not running
        let src = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: [.write, .extend], queue: .main)
        src.setEventHandler { [weak self] in
            Task { @MainActor in await self?.cycle() }
        }
        src.setCancelHandler { close(fd) }
        src.resume()
        walWatcher = src
    }

    // MARK: actions

    func signInAndPair() async {
        guard let auth = config.auth0, let tokens, let engine else { return }
        busy = true
        defer { busy = false }
        do {
            let pkce = PKCE.random()
            let callback = try await signIn.run(auth, pkce: pkce)
            try await tokens.completeSignIn(callback: callback, pkce: pkce)
            signedIn = true
            let name = (Host.current().localizedName ?? "Mac") + " iMessage"
            try await engine.pair(displayName: name)
            lastProblem = nil
            await cycle()
        } catch {
            lastProblem = "Sign-in or pairing did not complete. Try again."
        }
    }

    func setEnabled(_ on: Bool) {
        Task {
            try? await engine?.setEnabled(on)
            await cycle()
        }
    }

    func setSendsStopped(_ stopped: Bool) {
        Task {
            try? await engine?.setSendsStopped(stopped)
            refreshLocal()
        }
    }

    func setDraftOnly(_ on: Bool) {
        Task {
            try? await engine?.setDraftOnly(on)
            await cycle()
        }
    }

    func setStartAtLogin(_ on: Bool) {
        do { try LoginItem.set(on) } catch { lastProblem = "Could not change the login item." }
        startAtLogin = LoginItem.enabled
    }

    /// Returns false when the server could not be reached and nothing was wiped.
    func disconnect(force: Bool) async -> Bool {
        guard let engine else { return true }
        busy = true
        defer { busy = false }
        let r = await engine.disconnect(tokens: tokens, force: force)
        if r == .done {
            signedIn = false
            log.clear()
            refreshLocal()
            status = .notSignedIn
            return true
        }
        return false
    }

    var statusText: String {
        switch status {
        case .notSignedIn: return "Not signed in"
        case .notPaired: return "Signed in, not paired"
        case .paused: return "Paused"
        case .needsFullDiskAccess: return "Needs Full Disk Access"
        case .keychainLocked: return "Waiting for the keychain to unlock"
        case .backendUnreachable: return "Wingman unreachable, retrying"
        case .unpaired: return "Unpaired from Wingman"
        case let .running(stopped, draft):
            if stopped { return "Connected, sends stopped" }
            return draft ? "Connected, draft-only" : "Connected"
        }
    }
}
