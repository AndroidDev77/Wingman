# WING-028: Implement and verify Bumble messaging connector

**Project:** Wingman AI  
**Phase:** 7, platform integrations  
**Recommended branch:** `wing-028-bumble-connector`  
**Dependencies:** WING-006, WING-011, WING-017, WING-021. WING-014/015 for inbox/approval presentation.  
**Expected ownership:** `android/app/src/main/java/com/wingman/integrations/bumble/`, `backend/tests/connectors/bumble/`, `docs/integrations/bumble.md`.  
**Do not modify:** Tinder/Hinge connectors or shared dispatcher/interfaces except via a reviewed contract change.

## Copy/paste task for coding agent

You are a senior engineer assigned to **one isolated ticket** in the existing Wingman AI repository. **The project has already started.** Inspect the current code and existing contracts before editing. Do not recreate the monorepo, replace working modules, or launch a separate architecture. Follow `AGENT_RULES.md`, the existing `contracts/`, and the connector and automation interfaces as implemented. Work in a separate branch/worktree and produce **one pull request for this ticket**. If a dependency is still in progress, implement non-conflicting tests/fixtures/adapter scaffolding and give the lead agent a precise integration handoff rather than inventing competing interfaces.

Keep read, draft, send, profile-read, and swipe as independent capabilities. A notification that can be read is not proof that it can be replied to. All outgoing messages must pass through the existing automation controller and respect global and per-conversation pause/approval controls. Never claim a real platform capability without a reproducible test on that platform. Mark each outcome `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED`.


### Objective

Implement the Bumble messaging adapter, including inbound event normalization, compatible automated replies when verified, and approval-based fallback otherwise. Bumble can have conversation-initiation, reply timing, and expiration behavior; derive the actual state from observed/available data instead of hardcoding assumptions.

### Execution steps

1. Read the existing connector SDK, notifications prototype, and WING-021 evidence. Create a versioned Bumble capability matrix for `read`, `draft`, `send`, `profile`, and `swipe`.
2. Implement Bumble-specific Android notification source filtering and parsing. Normalize conversation identifiers when they are available; if only a sender label is available, keep it unlinked until the user resolves ambiguity.
3. Extract relevant metadata **only if it is really available**: incoming message, reaction, match notification, conversation expiry, prompts, or other state. Preserve unknown event types as unsupported rather than inventing data.
4. Detect `RemoteInput` and other supported reply actions. Implement optional direct reply only after testing its behavior on an actual device and Bumble build. Never infer that a notification's presence permits sending.
5. Integrate with the agent flow and enforce user-selected relationship intent per conversation. The AI should not manufacture a first-message opportunity if the platform does not expose it; do not promise outbound messages before a thread is eligible.
6. Add a robust fallback: draft suggestion, user approval, and deep link/open-Bumble when direct reply is not available. Keep explicit difference between drafted, submitted, and confirmed.
7. Handle missing/collapsed notification text, ephemeral alerts, expiring chats, muted threads, repeated notifications, permission changes, and app upgrades.
8. Document exact observed and unobserved capabilities in `docs/integrations/bumble.md` and a concise manual test script.

### Acceptance criteria

- Bumble-only notifications are normalized; unrelated notifications cannot trigger the connector.
- Message and conversation identity are stable where available, and ambiguous identities are never auto-merged.
- The adapter does not try to respond to match/expiry alerts as though they were ordinary incoming chat messages.
- Automated reply is disabled unless a supported send action is truly observed and tested.
- Outbound drafts obey the existing automation controller, approval rules, and pause/takeover behavior.
- Retry/replay of an event does not duplicate a send.
- If a device test is available, demonstrate one received message and one attempted/verified reply with precise acknowledgment status; otherwise mark live send `BLOCKED` or `UNVERIFIED` and deliver a functioning assisted flow.

### Test scenarios

Unit fixtures: text message, match notice, expiry notice, notification update, missing identity, missing direct-reply action.  
Integration: simulated pipeline through automation controller and audit log.  
Device: record Android version, Bumble version, notification action inspection, permission steps, and test observations with redacted logs.

### Required handoff

At completion, report:

1. Branch name and suggested PR title.
2. Exact files created/modified and any shared contracts that need approval.
3. Commands/tests run, their results, and whether a real account/device was tested.
4. Capability matrix with `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED` for **read / draft / send / profile / swipe**.
5. Known limitations, integration/permission blockers, and follow-up tasks.
6. Merge checklist for the coordinating agent.

**Do not mark the real integration as delivered if it only works with fixtures or mocks.**


## Owner decision D-021 (2026-11-01): experimental unofficial-API route (supersedes the "no private endpoints" lines above for this ticket)

The repo owner approved evaluating and building an **experimental** connector on top of existing open-source clients of this app's private API, on these binding conditions:

1. **Evaluate before building.** Start from the candidate repositories listed in `docs/integrations/dating_platform_matrix.md` (WING-021): check license compatibility, maintenance and last-known-working date, auth flow, exact endpoints called, and rate behavior. Prefer reimplementing the documented protocol in our own code over vendoring; never copy code whose license forbids it. Do not guess endpoints beyond what those references document, and do not attempt to bypass anti-bot, attestation, captcha or rate-limit protections.
2. **Off by default, explicit opt-in.** Per-user, per-platform opt-in behind a plain-language warning that this uses an unofficial interface, may violate the platform's terms, and can get the user's account restricted or banned; the user bears that risk and can disable it at any time.
3. **Credentials and sessions stay on the device.** Login (phone OTP etc.) happens on the user's phone; session tokens are stored only on-device, encrypted (Android Keystore), never sent to or stored by the backend, never logged, never committed, never in fixtures. Calls to the platform originate from the device, not the backend.
4. **Same safety path as every connector.** Inbound data enters through the existing device ingest route; every outbound message goes through the dispatcher, approval policy, pause/takeover and the device outbox (one attempt per key, D-006). **Approval-only at first:** no autopilot and no automated swiping until a live test passes and the owner explicitly enables it per platform.
5. **Conservative rate limits** (human-like pacing, daily caps) and immediate backoff/stop on auth errors, 4xx anomalies or any sign of enforcement; never retry aggressively.
6. **Capabilities stay UNVERIFIED** until the owner (or a tester) runs the live test with their own account on their own device: authenticate, read an existing conversation, send one approved test reply, and see it in the official app. Agents never enter credentials or codes.
7. **Fallback remains:** the notification-based assisted path (WING-017) ships regardless and is what users get when the experimental connector is off or broken.
8. **Priority:** Hinge first (WING-029); Tinder (WING-027) and Bumble (WING-028) only after Hinge's evaluation, and Bumble only if a currently working auth flow is documented.
