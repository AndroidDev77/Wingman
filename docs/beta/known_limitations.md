# Known limitations and supported platforms

Owner: WING-026. State as of v0.1.0-beta.1 (`main` @ `266243a`, 2026-11-01); platform rows
updated 2026-11-02 from WING-032 ([`release_gates.md`](../integrations/release_gates.md) §6,
[`cross_platform_test_matrix.md`](../integrations/cross_platform_test_matrix.md)). What is enabled,
and in which mode, is defined by [`config/release/feature_gates.json`](../../config/release/feature_gates.json):
`scripts/release/check.sh` and `build.sh` refuse any feature marked autonomous without VERIFIED
device evidence. **Nothing below has run on a real dating account, Mac or (except SMS on the CI
emulator) any phone.** Labels per `AGENT_RULES.md`:

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
| Tinder (notifications) | UNVERIFIED | UNSUPPORTED (assisted only: you send by hand) | **Assisted only**, opt-in per app | WING-027 (PR #50); mock E2E P2 ([matrix](../integrations/cross_platform_test_matrix.md) §4); never run on a phone (I-003); API path BLOCKED (D-023, I-032) |
| Bumble (notifications) | UNVERIFIED | UNSUPPORTED (assisted only) | **Assisted only**, opt-in per app | WING-028 (PR #50); as Tinder |
| Hinge (notifications) | UNVERIFIED | UNSUPPORTED (assisted only) | **Assisted only, experimental**, opt-in per app | WING-029 (PR #51); as Tinder; mock E2E P2; never run on a phone (I-003). Hinge's terms restrict AI-written content: the owner accepts that risk for this experimental stage (D-031, closes I-030) |
| Hinge (experimental API) | UNVERIFIED | UNVERIFIED, approval only | **Experimental**, not in the beta build | WING-029 (PR #51, #55), sideloaded experimental build only (D-026, D-027, I-031); owner live test L0-L10 not run; ban-risk consent required (D-021); AI drafts allowed with approval on every reply (D-031) |
| iMessage (Mac companion) | UNVERIFIED | UNVERIFIED, approval only | **Experimental**, not distributed (unsigned) | WING-030 (PR #54, D-028); never run on a Mac (I-034, I-035); groups are notices only |
| Android notification direct reply | n/a | Not available | Disabled | No RemoteInput reply verified for any app (I-033); replies are assisted (you send by hand) |
| Discovery, compatibility scores | Manual import only | n/a | **Assisted only**: decisions recorded in Wingman | WING-019 (PR #49), WING-031 (PR #55); mock E2E P5 |
| Swipe / auto-swipe | n/a | Not available | Disabled | No swipe path: Execute is refused (`swipe_execution_disabled`), nothing sets `executed` (D-027) |
| Calendar, date proposals | UNSUPPORTED | | Not built | WING-018. Wingman never creates calendar events; date suggestions always need approval (mock E2E C8) |
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
- **Mark-read** exists in the backend since contract 1.3.0 (I-022 closed, PR #47); the beta.1 app
  build predates it, so unread counts only grow in that build.
- **A phone that reports `error` or `disconnected` holds its sends** (D-030, PR #58): approved and
  manual replies stay queued (nothing is canceled by the hold) and go out once when it reports
  `connected` again.
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

- **Fixed after beta.1 (I-028 closed, PR #38, D-020):** the deadlock below. Root cause was the
  per-user `FOR UPDATE` lock; exclusive row locks are now `FOR NO KEY UPDATE`, covered by
  `backend/tests/automation/test_ingest_deadlock.py`. Original report kept for the record:
- **Intermittent deadlock on concurrent duplicate inbound events (found by WING-026 CI).** On PR #36 the CI job `E2E backend` failed twice in a row in scenario X1 and passed
  on the third attempt, with no backend code changed by the PR (`main` @ `266243a` had passed). Two
  of the 12 parallel deliveries of the same message under different event IDs got HTTP `500`:
  Postgres `DeadlockDetectedError` in `ConversationRepository.get_or_create_for_thread`
  (`INSERT ... ON CONFLICT DO NOTHING` on `conversations`, reached from
  `app/services/ingest.py::_inbound`). The Postgres log of the first failing job
  ([124966027140](https://github.com/AndroidDev77/Wingman/actions/runs/41346065184/job/124966027140),
  checked by the integrator) shows the cycle: one request's `INSERT INTO conversations` waits on
  another request's uncommitted insert of the same thread, while that request waits for
  `SELECT ... FROM users ... FOR UPDATE` (the automation controller's per-user lock), which the
  first request's earlier foreign-key share lock on the same `users` row blocks. Not reproduced locally (6 of 6 runs of X1 passed, and the full
  suite). The run-wide zero-duplicate-sends invariant still held in the failing runs (every counter
  0): the failed requests stored nothing and were not acknowledged, so a device retries them and the
  retry is deduplicated. Impact for testers: a rare `500` on a burst of identical SMS uploads, retried
  by the phone; no duplicate or lost message observed. Proposed follow-up (roadmap P1): retry the
  ingest transaction on `40P01` (deadlock) / `40001`, or take a per-thread advisory lock before the
  upsert, plus a stress test.

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
