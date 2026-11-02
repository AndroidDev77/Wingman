# Release notes: v0.1.0-beta.1 (private beta)

Owner: WING-026. Build: `main` @ `266243a` or later with only docs/scripts changes; APK
versionName `0.1.0-beta.1`, versionCode `2`; backend image `wingman-backend:0.1.0-beta.1-<sha>`.

**Scope: limited private beta.** Invited adult testers, their own conversations, unpaid. Gate G5
(a real connector verified on a physical device) is **not met**, so SMS is **UNVERIFIED** and every
SMS reply needs the tester's approval. No paid launch.

**How to read this.** Every line names its evidence. Evidence keys:

| Key | Meaning |
|---|---|
| **CI-main** | All seven CI jobs green on `main` @ `266243a` (run [41342480403](https://github.com/AndroidDev77/Wingman/actions/runs/41342480403) for `ci.yml`, [41342480432](https://github.com/AndroidDev77/Wingman/actions/runs/41342480432) for `e2e.yml`) |
| **F1..F9, X1..X5** | Backend E2E scenarios in [`docs/e2e_results.md`](../e2e_results.md), CI job `E2E backend` (PR #34, re-run on every PR and `main`) |
| **A1..A6** | App-drives-backend steps on the CI emulator (API 34), job `E2E Android emulator` (PR #34) |
| **SMS-emu** | "Emulator SMS stack exercised", same job. **Not** a device or carrier |
| **Unit** | The backend (`backend`, 954 tests) or Android (`android`, 398 JVM tests) CI job |

Anything not listed here is not claimed. For what does not work, see
[`known_limitations.md`](known_limitations.md).

## What's in this beta

### Set up

- Sign in on a debug build by pasting an operator-minted dev token; `GET /v1/me` identifies you.
  *Evidence:* A1; auth stub PR #10, Android sign-in PR #23. **Debug builds only; no real sign-in
  (I-014).**
- First launch opens onboarding: pick what you're looking for (five intents), optionally your
  texting style, and how much Wingman should do; save; land on the Inbox.
  *Evidence:* A2 (Serious relationship + Assisted driven through the UI); F1 (version 0 starts in
  `manual`, save with `If-Match`, stale save `412` overwrites nothing). Onboarding UI PR #17.
- Automation is **Manual** until you choose otherwise and confirm.
  *Evidence:* F1 (new user mode `manual`), F2 (manual mode creates no approval and no send).

### Drafts and approvals

- An incoming message produces a draft grounded in that message; in Assisted mode it waits in
  **To approve** and nothing is sent before you approve.
  *Evidence:* F3, F4, A3, A4. Of the style settings, only **punctuation** is E2E-asserted (F3,
  with the deterministic `[dev mock]` text); how well a real model follows your style is
  UNVERIFIED (I-009). Reply generation PR #20, automation PR #24, approvals UI PR #26.
- Approve, edit-then-approve (exactly your edited text is sent), or reject (nothing sent).
  Approving twice sends once.
  *Evidence:* F4 (`409 approval_not_pending` on a second approve; replayed key returns the same
  message).
- "Sent" is shown only after the messaging side confirms; before that, "Queued, not sent yet".
  *Evidence:* A4, F4 (response `queued`, then `sent`).
- **Suggest a reply** on request in Manual mode. *Evidence:* F3 (createDraft on request, over
  HTTP); the button in the app is compiled and JVM-tested, not UI-driven in E2E.
- Wingman can decline to draft (`no_send`) or ask you (`ask_user`) instead of inventing facts or
  escalating. *Evidence:* **Unit only** (`backend/tests/ai/replies`); not E2E-testable with the dev
  mock (I-026).

### Staying in control

- **Activity** shows the server's audit timeline for each step (received, proposed, approved,
  queued, handed over, sent), with no message text.
  *Evidence:* A5; audit asserted in every flow F1 to F9.
- **Pause** (everyone or one person) stops automatic sends and routes new drafts to approval;
  **Take over** cancels pending drafts and approved-but-unsent replies for that person; your own
  messages still send (D-014).
  *Evidence:* F7 (HTTP); A6 (takeover in the app). Pause in the app: Unit only.
- **Disconnect** (unpair) cancels queued replies and pending approvals for that connection.
  *Evidence:* F8 (HTTP). In-app unpair on a phone: UNVERIFIED.
- Your data is isolated: another user's resources answer `404`.
  *Evidence:* F9 (13 operations).
- Memory facts can be listed, corrected and deleted. *Evidence:* F5 (HTTP; no app screen).

### Reliability

- No duplicate sends under duplicate inbound events, an offline phone, a lapsed device lease,
  retryable and permanent failures, takeover mid-flight, and server kills (SIGKILL/SIGTERM).
  *Evidence:* X1 to X5 and the run-wide zero-duplicate-sends check (every duplicate counter 0).
  **Known issue:** a burst of identical inbound uploads can intermittently hit a database deadlock
  and answer `500` for some of the copies (X1 failed twice on PR #36's CI, then passed; duplicate
  counters stayed 0). See [`known_limitations.md`](known_limitations.md#backend-and-operations).
- A phone that never reports back is shown as "outcome unknown", never as sent or failed, and is
  never retried automatically. *Evidence:* X2; D-016.

### SMS (UNVERIFIED)

- **Settings > Connections > Phone SMS** pairs this phone and shows `Send replies: UNVERIFIED` and
  `Autopilot: not available for SMS`. *Evidence:* SMS-emu step 2 (role and permissions granted by
  `adb`); pairing PR #30, contract v1.2.0 PR #28.
- On the CI emulator, an incoming text reached Wingman, a draft was approved in the app, sent with
  the emulator's `SmsManager`, and the server recorded it sent. *Evidence:* SMS-emu. **This is not
  a physical device, SIM or carrier; SMS read and send remain UNVERIFIED (I-003).**

### Operator tooling (new in this release, WING-026)

- `scripts/release/check.sh`: runs `backend-check.sh`, `contracts/validate.sh`,
  `migration-check.sh`, `scripts/e2e/run.sh` in order and stops at the first failure; `--paid`
  refuses while G5 is blocked. *Evidence:* `tests/release` (stub repositories) and the full run
  recorded in this PR.
- `scripts/release/build.sh` / `version.sh`: versioned debug APK (versionName/versionCode through
  Gradle properties) and backend image tag. *Evidence:* `tests/release`; APK built and checked with
  `aapt2` (`install.md` §7).

## Not in this beta

Not in the beta.1 build (`266243a`): Tinder, Bumble, Hinge, iMessage, notification reply,
swiping/discovery, date proposals and calendar, subscriptions. **Update 2026-11-02:** Tinder, Bumble
and Hinge (notification-assisted), Hinge (experimental API), the iMessage Mac companion and the
discovery review have since been built (WING-027 to 031). They are **assisted-only or experimental
and UNVERIFIED** (never run on a real account, phone or Mac); swiping, direct notification reply,
dating autopilot and calendar stay disabled. Evidence and modes:
[`cross_platform_test_matrix.md`](../integrations/cross_platform_test_matrix.md),
[`config/release/feature_gates.json`](../../config/release/feature_gates.json). UNVERIFIED: anything on a physical phone, draft quality
with a real LLM (I-009), OIDC sign-in. Known gaps: no background sync (I-024), no mark-read (I-022),
MMS dropped while Wingman is the default SMS app. Details: [`known_limitations.md`](known_limitations.md).
