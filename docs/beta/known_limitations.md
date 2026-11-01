# Known limitations and supported platforms

Owner: WING-026. State as of v0.1.0-beta.1 (`main` @ `266243a`, 2026-11-01). Labels per
`AGENT_RULES.md`:

- **VERIFIED**: a reproducible run proved it, in the place that matters.
- **UNVERIFIED**: built, maybe tested elsewhere (unit tests, an emulator), but not proven on the
  real target. May not work. Never treated as able to send automatically.
- **UNSUPPORTED**: not built. Not available in the beta at all.

## Supported-platform table

| Platform / feature | Read | Send | Beta status | Notes and evidence |
|---|---|---|---|---|
| `mock` (simulated platform, WING-006) | VERIFIED (simulator only) | VERIFIED (simulator only) | **Dev and operator testing only.** Not a messaging platform; no real person receives anything | Every backend E2E flow F1 to F9, X1 to X5 (`docs/e2e_results.md`). Refused in `prod`. Its state lives in process memory (I-016, below) |
| **Android SMS**, CI emulator | Exercised | Exercised | "Emulator SMS stack exercised" | PASS in CI job `E2E Android emulator` (inbound, approve, `SmsManager` send, server `sent`; "delivered" came from the emulator's fake modem). Does not change any label |
| **Android SMS**, physical phone | **UNVERIFIED** | **UNVERIFIED** | **The only real channel in the beta, used with approval on every reply** | Never run on a phone with a SIM (I-003, gate G5 ⛔). Autopilot impossible while `send` is UNVERIFIED (D-016) |
| Tinder | UNSUPPORTED | UNSUPPORTED | Not built | WING-021 is research only and not started; not pairable in contract 1.2.0 |
| Bumble | UNSUPPORTED | UNSUPPORTED | Not built | As Tinder |
| Hinge | UNSUPPORTED | UNSUPPORTED | Not built | As Tinder |
| iMessage | UNSUPPORTED | UNSUPPORTED | Not built | WING-020 (Mac companion feasibility) not started |
| Android notification reply (any app) | UNSUPPORTED | UNSUPPORTED | Not built | WING-017 spike not started. The contract accepts `android_notification` pairing, but no app code pairs it |
| Swipe, discovery, compatibility scores | UNSUPPORTED | | Not built | WING-019. The **Discovery** tab in the app is a placeholder screen |
| Calendar, date proposals | UNSUPPORTED | | Not built | WING-018. Wingman never creates calendar events |
| iOS app | UNSUPPORTED | | Not built | |

## Features

| Feature | Status | Notes |
|---|---|---|
| Onboarding: intent (Serious relationship, Casual dating, Casual physical, Open to anything, Custom goal), style, autonomy, review | Works on CI emulator | `e2e_results.md` A2 drove Serious + Assisted; the other intents are JVM-tested and contract-validated, not UI-driven in E2E |
| Automation modes | Manual (default) and Assisted work. **Autopilot is effectively off in the beta**: it applies only to channels with VERIFIED send, and the only VERIFIED channel is the `mock` simulator | Autopilot with the mock: F6 |
| Approvals: approve, edit then approve, reject | Works (HTTP F4; app A4) | Approvals expire after 24 h (`approval_expired`); approving an expired draft is refused |
| Activity (audit timeline) | Works (A5) | Shows IDs and states, never message text in the audit details |
| Pause (global and per contact), takeover, resume | Works over HTTP (F7); takeover in the app (A6) | D-014. Pause in the app is JVM-tested and compiled, not UI-driven in E2E |
| Unpair | Works over HTTP (F8) | In-app unpair UNVERIFIED on a phone (P6) |
| "Suggest a reply" on request (Manual mode) | Works over HTTP (F3) | |
| Memory facts: list, correct, delete | Works over HTTP (F5) | With the dev mock nothing is extracted; with a real LLM, extraction quality is UNVERIFIED |
| Draft quality with a real LLM | **UNVERIFIED** (I-009) | `OpenAICompatibleProvider` has never called a real endpoint. The Anthropic provider is not implemented. Only the deterministic `[dev mock]` drafts have run |
| Content guardrails (no invented facts, no explicit or coercive wording, no unsupported escalation) | Unit-tested only (I-026) | The dev mock never produces unsafe text, so E2E cannot exercise them |
| Home tab | Shows your name, pending approvals, automation state and backend version | Shell screen, not a dashboard |

## Known limitations

### Messaging and SMS

- **G5 not met (I-003).** No real channel has sent a reply on a physical device. SMS `read` and
  `send` stay UNVERIFIED until a human runs `docs/integrations/android_sms.md` §7.
- **No autopilot on SMS.** Every SMS reply needs approval (reasons include
  `capability_unverified`). The server never stores a client's claim of VERIFIED (D-016).
- **MMS is dropped while Wingman is the default SMS app**: picture messages, group chats, long texts
  converted to MMS. Use a spare phone or switch back after each session (`install.md` §5.4).
- **No periodic background sync (I-024).** The phone uploads received texts and claims approved
  replies only when the app starts, and after pair/unpair. An approved reply waits until you open
  the app. If the phone claims a reply and then never reports back for 120 s, the server marks the
  outcome **unknown** (`lease_expired`, "Phone didn't confirm; outcome unknown"), never `sent` and
  never retried, so it cannot double-send; check your phone's sent messages.
- **No mark-read (I-022).** The contract has no mark-read operation, so unread counts only grow.
- **Delivery reports** depend on the carrier; many messages stay at "Sent (delivery not
  confirmed)", which is correct.
- **Dual SIM**: a reply may go out from the default SMS SIM rather than the one the text came in
  on (UNVERIFIED).
- **Texts received before the first unlock after a reboot** are handled only after unlock
  (UNVERIFIED).
- **Android 13+ restricted settings** may block SMS permissions for sideloaded APKs on some phones;
  granting the default-SMS-app role through the system dialog is the supported path.
- Conversations show no connection-capability summary (I-023); the app reads it from the connection.

### Mock connector (dev and operator testing only)

- `MockConnector` state lives in **process memory** (I-016). A backend restart forgets simulated
  threads; delayed mock replies due after a restart are dead-lettered ("unknown mock thread"). A
  separate worker process (Compose `worker`) skips mock messages, so delayed or retried mock sends
  stay queued there. Use `scripts/dev-run.sh` (one process) for mock testing. Never a real-platform
  issue.

### Accounts and sign-in

- **No identity provider (I-014).** Sign-in is the debug-only dev-token panel; tokens are minted by
  the operator, last up to the TTL they choose, and are never refreshed. OIDC (AppAuth, PKCE) is
  built and JVM-tested but has never run against a real IdP (I-011).
- The beta APK is a **debug** build. It contains the debug-only SMS test harness (no launcher icon)
  and allows plain HTTP only to loopback hosts.
- **No self-service account deletion or data export.** The operator deletes an account on request
  (`release_checklist.md` §5). Memory facts can be deleted one by one.

### Backend and operations

- **No deployed environment exists.** The Azure sample is UNVERIFIED and its image default
  (`WINGMAN_ENV=prod`) refuses the dev stub, so it cannot host this beta until an IdP exists.
- The beta backend runs with `WINGMAN_ENV=dev`, so the unauthenticated demo router
  `/dev/connectors/mock/*` is mounted (fixed demo user, in-memory, no access to tester data). The
  operator blocks `/dev/` at the reverse proxy.
- With `WINGMAN_LLM_PROVIDER=mock`, only `scripts/dev-run.sh` produces drafts; the plain API
  (`app.main`, Compose `backend`) returns `no_send` (`model_unavailable`) for every message.
- **No analytics (WING-022 not built).** Metrics come only from the audit log (`metrics.md`). LLM
  token cost is computed per call but not persisted.
- Capability verification is an operator CLI with no audit row (I-019).
- Redis is configured but unused.

### Tests

- Compose UI tests under `createAndroidComposeRule` cannot launch `MainActivity` (I-027); the
  emulator E2E tests use UiAutomator instead.
- `mypy tests/db` reports 27 errors (I-012; not part of the check).
