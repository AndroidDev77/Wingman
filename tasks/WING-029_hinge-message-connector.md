# WING-029: Implement and verify Hinge messaging connector

**Project:** Wingman AI  
**Phase:** 7, platform integrations  
**Recommended branch:** `wing-029-hinge-connector`  
**Dependencies:** WING-006, WING-011, WING-017, WING-021. WING-014/015 for UX integration.  
**Expected ownership:** `android/app/src/main/java/com/wingman/integrations/hinge/`, `backend/tests/connectors/hinge/`, `docs/integrations/hinge.md`.  
**Do not modify:** other app connectors, central dispatcher, or shared contracts without approval.

## Copy/paste task for coding agent

You are a senior engineer assigned to **one isolated ticket** in the existing Wingman AI repository. **The project has already started.** Inspect the current code and existing contracts before editing. Do not recreate the monorepo, replace working modules, or launch a separate architecture. Follow `AGENT_RULES.md`, the existing `contracts/`, and the connector and automation interfaces as implemented. Work in a separate branch/worktree and produce **one pull request for this ticket**. If a dependency is still in progress, implement non-conflicting tests/fixtures/adapter scaffolding and give the lead agent a precise integration handoff rather than inventing competing interfaces.

Keep read, draft, send, profile-read, and swipe as independent capabilities. A notification that can be read is not proof that it can be replied to. All outgoing messages must pass through the existing automation controller and respect global and per-conversation pause/approval controls. Never claim a real platform capability without a reproducible test on that platform. Mark each outcome `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED`.


### Objective

Develop a Hinge connector for conversation notifications, contextual reply generation, and verified outgoing replies where technically supported. Hinge messages, profile-prompt comments, likes, and new-match alerts must not be conflated. Provide an assisted mode if autonomous sending cannot be demonstrated.

### Execution steps

1. Inspect current repo contracts, Android notification listener, and WING-021 findings. Define Hinge-specific capabilities and event taxonomy based on verified examples.
2. Implement package/source validation and notification parsing into distinct normalized event types: chat message, like/comment, new match, system notice, and unknown. Include fixtures for unavailable fields.
3. Persist reliable external identifiers when exposed, and preserve identity uncertainty when Android notifications expose only a display name; ensure one person's details cannot be attached to another match.
4. Connect inbound **chat message** events to the existing AI conversation/memory pipeline, using user-selected dating mode and style. Do not automatically reply to likes/comments as if a chat already exists.
5. Detect whether Hinge notifications provide a usable reply action. Implement and enable the action only after a real-device check. Do not use undocumented guessed HTTP endpoints to simulate official messaging support.
6. For unavailable send capability, present a draft with copy/open-app workflow and keep `send=false` in capability flags.
7. Add handling for notification truncation, sensitive-content hiding, duplicate updates, mismatched threads, network loss, unmatches, and revocation of notification permission.
8. Document Hinge version and Android build used for any live experiment, exact action availability, outcome, and capability status.

### Acceptance criteria

- Distinguishes likes, comments, matches, and real incoming chat events in tests.
- Does not auto-send on non-message notifications or ambiguous identities.
- Every send passes through existing authorization/automation controls; user takeover halts the connector.
- Unit tests and integration fixture tests cover absent reply action and duplicate inbound events.
- Capability flags reflect real observations, not product intent.
- Device verification, if performed, produces a reproducible inbound-to-reply transcript with no sensitive personal data. If not feasible, a working assisted fallback exists and live support is plainly `BLOCKED`/`UNVERIFIED`.

### Test scenarios

Unit: likes vs chat, empty body, truncated notification, duplicate notification update, sender collision.  
Integration: inject verified fixtures, assert one draft/one audit event, dispatch only with capability.  
Device: inspect actual Android action availability; explicitly note absent or unavailable testing.

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
