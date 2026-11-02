import AppKit
import SwiftUI
import WingmanCompanionCore

/// Menu-bar only (`LSUIElement`). No window, no Dock icon, no listening socket.
@main
struct WingmanCompanionApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra("Wingman", systemImage: "bubble.left.and.bubble.right") {
            MenuView(model: model)
                .frame(width: 360)
        }
        .menuBarExtraStyle(.window)
    }
}

struct MenuView: View {
    @ObservedObject var model: AppModel
    @State private var confirmDisconnect = false
    @State private var offerForce = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Circle().fill(dotColor).frame(width: 8, height: 8)
                Text(model.statusText).font(.headline)
                Spacer()
            }
            if let problem = model.lastProblem {
                Text(problem).font(.caption).foregroundStyle(.red)
            }
            if !model.signedIn {
                OnboardingView(model: model)
            } else {
                switches
                PermissionsView(model: model)
            }
            AwakeNote()
            if !model.recent.isEmpty { activity }
            Divider()
            HStack {
                if model.signedIn {
                    Button("Disconnect…", role: .destructive) { confirmDisconnect = true }
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .padding(14)
        .disabled(model.busy)
        .confirmationDialog("Disconnect this Mac from Wingman?", isPresented: $confirmDisconnect) {
            Button("Disconnect", role: .destructive) {
                Task { offerForce = !(await model.disconnect(force: false)) }
            }
        } message: {
            Text("Unpairs it on the server (pending sends are canceled), signs out, and deletes the Keychain item and all local Wingman data. Messages itself is not touched.")
        }
        .alert("Wingman could not be reached", isPresented: $offerForce) {
            Button("Delete local data anyway", role: .destructive) {
                Task { _ = await model.disconnect(force: true) }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Sends are already stopped on this Mac. If you delete local data now, also remove \"Mac (iMessage)\" under Connections in the Wingman app.")
        }
    }

    private var dotColor: Color {
        switch model.status {
        case .running(let stopped, _): return stopped ? .orange : .green
        case .paused, .backendUnreachable, .keychainLocked: return .orange
        default: return .red
        }
    }

    private var switches: some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle("Companion on", isOn: Binding(get: { model.enabled }, set: { model.setEnabled($0) }))
                .help("Off: nothing is read, uploaded or sent.")
            Toggle("Stop all sends from this Mac", isOn: Binding(get: { model.sendsStopped }, set: { model.setSendsStopped($0) }))
                .help("Local kill switch. Takes effect before the next send; reading continues.")
            Toggle("Draft-only (I send by hand)", isOn: Binding(get: { model.draftOnly }, set: { model.setDraftOnly($0) }))
                .help("Wingman shows replies for you to copy; this Mac never sends.")
            Toggle("Start at login", isOn: Binding(get: { model.startAtLogin }, set: { model.setStartAtLogin($0) }))
        }
    }

    private var activity: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Recent activity (no message content is logged)").font(.caption).foregroundStyle(.secondary)
            ForEach(Array(model.recent.prefix(6).enumerated()), id: \.offset) { _, item in
                Text("\(item.0.formatted(date: .omitted, time: .shortened))  \(item.1)")
                    .font(.caption2.monospaced()).lineLimit(1)
            }
        }
    }
}

struct OnboardingView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Connect your Mac's Messages to Wingman").font(.subheadline.bold())
            Text("Wingman reads new messages in Messages on this Mac and, only after you approve a reply in Wingman, sends it through Messages. It never uploads your message history and never sends on its own.")
                .font(.caption).fixedSize(horizontal: false, vertical: true)
            PermissionsView(model: model)
            Button(model.configured ? "Sign in with Wingman" : "Not configured") {
                Task { await model.signInAndPair() }
            }
            .disabled(!model.configured)
            .keyboardShortcut(.defaultAction)
        }
    }
}

struct PermissionsView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            row("Full Disk Access", ok: model.fullDiskAccess,
                why: "Needed to read ~/Library/Messages. macOS has no prompt for it: add Wingman Companion in System Settings, then reopen the app.",
                action: SystemSettings.openFullDiskAccess)
            row("Automation → Messages", ok: model.automationGranted,
                why: "Needed to send an approved reply. macOS asks the first time a send runs; you can change it later.",
                action: SystemSettings.openAutomation)
            if model.automationGranted == false {
                Button("I allowed it in Settings: retry sends") { model.retryAutomation() }
                    .controlSize(.small)
            }
        }
    }

    private func row(_ title: String, ok: Bool?, why: String, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Image(systemName: ok == true ? "checkmark.circle.fill" : ok == false ? "xmark.octagon.fill" : "questionmark.circle")
                    .foregroundStyle(ok == true ? .green : ok == false ? .red : .secondary)
                Text(title).font(.caption.bold())
                Spacer()
                Button("Open Settings", action: action).controlSize(.small)
            }
            Text(why).font(.caption2).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct AwakeNote: View {
    var body: some View {
        Label("Your Mac must be awake, logged in, and signed in to Messages. While it sleeps nothing is read or sent, and Wingman shows sends as unconfirmed.",
              systemImage: "moon.zzz")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}
