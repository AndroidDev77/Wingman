# Cross-platform test matrix, evidence log and final report (WING-032)

Owner: WING-032. State as of 2026-11-02, `main` @ `a3fb8ea` plus the WING-032 branch.

**Headline: nothing has run on a real device, Mac or dating account.** Every real-device cell
below is `NOT RUN` or `BLOCKED`, with the reason. What passes is the deterministic mock suite
(`PASSED WITH MOCK`): the real backend, worker and Postgres driven over HTTP by protocol-faithful
simulators of each connector. A mock pass proves the backend's rules (what may be drafted, what
waits for approval, what can never be sent, idempotency, audit); it proves **nothing** about what
Tinder, Bumble, Hinge, Messages or a carrier actually do. Labels per `AGENT_RULES.md`:
`VERIFIED` / `UNVERIFIED` / `UNSUPPORTED` / `BLOCKED`.

Contents: [1 Final report](#1-final-report) · [2 Inventory](#2-inventory-of-merged-connectors) ·
[3 Matrix](#3-test-matrix) · [4 Mock suite](#4-mock-e2e-suite-testse2eplatforms) ·
[5 Manual scripts](#5-manual-real-device-scripts) · [6 How to run](#6-how-to-run) ·
[7 Evidence log](#7-evidence-log) · [8 Gaps](#8-what-the-mock-suite-cannot-show)

## 1. Final report

### Which apps can we read?

**None is verified.** Reading is **implemented and UNVERIFIED** for: Android SMS (default SMS app
role), Tinder, Bumble and Hinge notification previews (notification listener), Hinge through the
experimental unofficial API (sideloaded experimental build only), and iMessage through the Mac
companion (`chat.db`, Full Disk Access). The backend handles each of them correctly with mock
envelopes (§4). No real SMS, notification, Hinge API response or `chat.db` row has ever reached
Wingman.

### Which can we actually reply to?

**None, verified.** What exists:

| Platform | Reply path today | Verified? |
|---|---|---|
| Android SMS | Wingman sends after **your approval** (device outbox, `SmsManager`) | UNVERIFIED on a phone (emulator only, "emulator SMS stack exercised"; G5 ⛔, I-003) |
| iMessage (Mac companion) | Wingman sends after **your approval** (AppleScript, ack from `chat.db`) | UNVERIFIED (never run on a Mac; unsigned; I-035) |
| Hinge (experimental API) | Wingman sends after **your approval** (Sendbird, echo-confirmed) | UNVERIFIED (owner live test L0-L10 not run; I-031) |
| Tinder, Bumble, Hinge (notifications) | **Assisted only**: Wingman drafts, you copy and send in the app, "I sent it" is recorded as `user_reported_sent` | Wingman cannot send there (`send: UNSUPPORTED`); direct reply (RemoteInput) disabled and unobserved |

No platform can be replied to **automatically**: autopilot is refused on every real connection
(policy `capability_unverified`, or assisted delivery where send is UNSUPPORTED), shown in P1,
P3, P4, P7.

### Which support real swiping?

**None.** No connector has a `swipe` capability other than `UNSUPPORTED`; there is no swipe
executor and no route to report one (D-027). Discovery is a review workflow (import, score,
decide in Wingman); "Execute" is refused (`422 swipe_execution_disabled`) on every platform and
path (P5, P7). Tinder and Bumble API paths are BLOCKED (D-023); Hinge's rating/like endpoints are
excluded by policy (D-021).

### Which combinations were tested?

| Combination | Where | Result |
|---|---|---|
| Backend + worker + Postgres 16 + dev mock LLM, driven by simulated SMS phone, Tinder/Bumble/Hinge notification plugins, Hinge experimental connector, iMessage companion (golden pairing body), profile import | `scripts/e2e/platforms/run.sh`, locally (3 consecutive runs) and CI job `Platform E2E (mock connectors)` | **PASSED WITH MOCK** (§4) |
| Same backend with the `mock` simulator and the SMS simulator (WING-025) | `scripts/e2e/run.sh`, CI `E2E backend` | PASSED WITH MOCK (`docs/e2e_results.md`) |
| Debug Android app + backend + **emulator** SMS stack (API 34) | CI `E2E Android emulator` (WING-025) | "emulator SMS stack exercised"; not a device, changes no label |
| Android notification plugins, Hinge connector, SMS connector (pure Kotlin) | JVM tests in CI `Android` job | Unit level only |
| macOS companion core | XCTests on Linux and macOS in CI (`macos-companion.yml`) | Unit/fixture level; AppleScript never executed |
| **Any physical Android phone, any dating app, any Mac with Messages** | — | **NOT RUN** (§3a) |

### Go / no-go per automation feature

Enforced by `config/release/feature_gates.json` (`docs/integrations/release_gates.md`).

| Feature | Verdict | Release mode | Why |
|---|---|---|---|
| SMS reply after approval | **GO (limited beta, labeled UNVERIFIED)** | assisted only | Backend path proven with mock (P1, C3-C5); real phone never run (I-003) |
| SMS autopilot | **NO-GO** | disabled | Needs `send` VERIFIED on a phone (G5) and periodic sync (I-024), D-019 |
| Tinder / Bumble notification-assisted drafts | **GO as assisted-only, behind the reading opt-in, labeled UNVERIFIED** | assisted only | Backend never sends and never drafts for non-chat or unlinked events (P2); real notification shapes unknown until N1-N13 |
| Hinge notification-assisted drafts | **NO-GO until the owner decides I-030** | assisted only (gate) | Hinge's terms restrict AI-generated content (I-030); technically identical to Tinder/Bumble |
| Direct reply from notifications (RemoteInput) | **NO-GO** | disabled | No reply action observed for any app (N9/N13 not run); outcome would be unknown (I-033) |
| Autopilot on Tinder / Bumble / Hinge | **NO-GO** | disabled | Wingman cannot send there |
| Hinge experimental API (approval-only) | **NO-GO for any release; owner-only sideload for the live test** | experimental | L0-L10 not run, `verify-experimental` not recorded, I-030, I-031, ban risk (D-021) |
| iMessage companion replies after approval | **NO-GO until the first real-Mac run** | experimental | Unsigned, M1-M14/A1-A17 not run (I-035), Full Disk Access risk (I-034) |
| iMessage autopilot | **NO-GO** | disabled | send UNVERIFIED |
| Discovery review (rank and decide in Wingman) | **GO as assisted-only** | assisted only | Records decisions only; nothing happens in any app (P5) |
| Swipe execution / auto-swipe | **NO-GO** | disabled | No swipe path exists (D-027) |
| Date proposals / calendar | **NO-GO (not built)** | disabled | WING-018 not built; proposals always need approval, confirmations ask the user (C8) |

### Prioritized blockers (owner actions first)

1. **Owner: run the SMS device checklist** (`android_sms.md` §7 D3, D4-D7, B3, then P1-P8) on a
   spare phone with a SIM, record it as `R-SMS-<date>` (§7), then the operator `verify` CLI.
   This alone unblocks G5, the paid-launch gate and any SMS autopilot discussion (I-003).
2. **Owner: decide I-030** (Hinge terms vs AI drafts) before any Hinge draft is shown, on either path.
3. **Owner: run N1-N13 + T1-T8 for Tinder and Bumble** on a phone with test accounts and record
   the real notification shapes; fix the plugin rules from them (synthetic today).
4. **Owner: Apple Developer ID, notarization and the companion's Auth0 Native app** (I-035), then
   M1-M14 and A1-A17 on a Mac; decide read NO-GO if Full Disk Access is impractical (I-034).
5. **Owner: decide I-031** (experimental build distribution) and **I-032** (Tinder/Bumble stay
   assisted-only); only then the Hinge live test L0-L10 with the owner's own account.
6. Engineering: periodic Android sync (I-024), so approved SMS replies do not wait for the app to open.
7. ~~Engineering: the backend ignores `connection.status_changed` (`error`/`disconnected`) when
   queuing or leasing.~~ **Fixed** (PR #58, D-030): such a connection holds its sends (no new
   instruction, direct send or lease; nothing canceled) and resumes once on `connected` (mock E2E P8).
8. Engineering: contract follow-ups already recorded: a "handed to app" outcome for RemoteInput
   (I-033), an `is_group` flag (D-028), a swipe outcome event (D-027).
9. Engineering: a scripted adversarial LLM mock for guardrails in E2E (I-026; WING-032's
   `e2e_scripted_server.py` covers date proposals only).

## 2. Inventory of merged connectors

Capability descriptors as the **client claims** them at pairing (`connector_version` 0.1.0 for
all). The server stores a client `VERIFIED` as `UNVERIFIED` (ADR 0007, D-016); only the operator
CLI with evidence can verify, and it has never been used for a real platform.

| Connector | Ticket / PR | Code | Platform / transport | read | draft | send | profile_read | swipe | Device evidence | Blockers |
|---|---|---|---|---|---|---|---|---|---|---|
| Android SMS | WING-016 PR #12, #21, #30 | `android/.../integrations/sms/` (`SmsCapabilities.kt`) | `sms` / `sms_provider` | UNVERIFIED | UNSUPPORTED | UNVERIFIED (`reports_delivery_receipts: true`) | n/a | UNSUPPORTED | Emulator only (WING-025 B-phase) | I-003, I-024 |
| Notification listener core | WING-017 PR #46 | `android/.../integrations/notifications/` | (shared) | — | — | replies only from a claimed instruction; `submitted` never `sent` | — | — | none | I-003, I-033 |
| Tinder notifications | WING-027 PR #50 | `TinderNotificationPlugin.kt` (`com.tinder`, cert not pinned) | `tinder` / `notification` | UNVERIFIED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | none (shapes synthetic) | I-003, I-032 |
| Bumble notifications | WING-028 PR #50 | `BumbleNotificationPlugin.kt` (`com.bumble.app`) | `bumble` / `notification` | UNVERIFIED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | none | I-003, I-032 |
| Hinge notifications | WING-029 PR #51 | `integrations/hinge/notifications/` (`co.hinge.app`) | `hinge` / `notification` | UNVERIFIED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | none | I-003, I-030 |
| Hinge experimental API | WING-029 PR #51, #55 (flag-gated, `src/experimental`) | `HingeConsent.kt` `HingeCapabilities.document()` | `hinge` / `unofficial_api` (consent required) | UNVERIFIED | UNSUPPORTED | UNVERIFIED (approval-only until `verify-experimental`) | UNSUPPORTED (not allowlisted) | UNSUPPORTED (policy) | none (L0-L10 not run) | I-030, I-031, D-021 |
| iMessage companion | WING-030 PR #54 | `macos/wingman-companion` (`Capabilities.swift`) | `imessage` / `companion` | UNVERIFIED (UNSUPPORTED without Full Disk Access) | UNSUPPORTED | UNVERIFIED (UNSUPPORTED if Automation denied or draft-only) | UNSUPPORTED | UNSUPPORTED | none (no Mac run) | I-034, I-035 |
| Discovery and swipe | WING-019 PR #49, WING-031 PR #55 | `backend/app/discovery/`, `features/discovery/` | any dating connection | — | — | — | profiles pasted by hand only | **no executor** (`queued` terminal, nothing sets `executed`) | none | D-027 |
| Mock simulator | WING-006 | `backend/app/connectors/mock.py` | `mock` / `simulator` (refused in prod) | VERIFIED (simulator only) | UNSUPPORTED | VERIFIED (simulator only) | — | UNSUPPORTED | n/a, not a platform | — |

Server-side rules that apply to all of them (contract 1.3.1, ADR 0008): only a `chat_message`
with `identity: linked`, not truncated and not hidden, becomes a message; everything else is an
inbound notice (no contact, no draft, no send). `send: UNSUPPORTED` on a dating or iMessage
connection gives an assisted delivery; on SMS it blocks. Experimental connections are
approval-only until an operator records a live test.

## 3. Test matrix

Columns per the ticket. Status values: `PASSED ON DEVICE` / `PASSED WITH MOCK` / `FAILED` /
`BLOCKED` / `NOT RUN`. A row's device status can only become `PASSED ON DEVICE` from an entry
in the evidence log (§7); `scripts/release/check_feature_gates.py` refuses any autonomous gate
whose row does not say so.

### 3a. Real device / real account

| Row | OS / device / app version | Read | Conversation identity | AI draft | Auto-send | Real delivery | Profile-read | Swipe | Approval mode | Status | Evidence | Date tested |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Android SMS | none (needs Android 10+ phone with SIM) | NOT RUN | NOT RUN | NOT RUN | NOT RUN (disabled) | NOT RUN | n/a | n/a | every reply approved | BLOCKED: no physical phone available to agents (I-003); emulator run does not count | R-SMS (§5.1), `android_sms.md` §7 | — |
| Tinder (notification) | none (needs phone + 2 test accounts) | NOT RUN | NOT RUN | NOT RUN | n/a (send UNSUPPORTED) | n/a (user sends by hand) | n/a | n/a | assisted only | BLOCKED: owner device and test accounts (I-003) | R-TINDER (§5.2) | — |
| Bumble (notification) | none | NOT RUN | NOT RUN | NOT RUN | n/a | n/a | n/a | n/a | assisted only | BLOCKED: owner device and test accounts (I-003) | R-BUMBLE (§5.2) | — |
| Hinge (notification) | none | NOT RUN | NOT RUN | NOT RUN | n/a | n/a | n/a | n/a | assisted only | BLOCKED: owner device, test accounts, and I-030 | R-HINGE-N (§5.2) | — |
| Hinge (experimental) | none (needs experimental APK + owner's own account) | NOT RUN | NOT RUN | NOT RUN | NOT RUN (approval-only) | NOT RUN | n/a (not allowlisted) | n/a (policy) | every reply approved | BLOCKED: owner live test L0-L10, I-030, I-031 | R-HINGE-X (§5.3) | — |
| iMessage (Mac companion) | none (needs macOS 14+, signed app) | NOT RUN | NOT RUN | NOT RUN | NOT RUN (disabled) | NOT RUN | n/a | n/a | every reply approved; groups notice only | BLOCKED: Developer ID / notarization / Auth0 Native app (I-035), I-034 | R-IMESSAGE (§5.4) | — |
| Profile / swipe | any | n/a | n/a | n/a | n/a | n/a | NOT RUN (no profile_read anywhere) | NOT RUN (no swipe path exists) | decisions recorded only | BLOCKED: nothing to run (D-027, D-023) | R-SWIPE (§5.5) | — |

### 3b. Mock (this suite, `tests/e2e/platforms`)

| Row | OS / device / app version | Read | Conversation identity | AI draft | Auto-send | Real delivery | Profile-read | Swipe | Approval mode | Status | Evidence | Date tested |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Android SMS | simulator (`PlatformDevice` sms) | inbound stored once, dedup | `sms:+<number>` thread | yes, cites the SMS | refused (`capability_unverified`) | simulated ack only | n/a | n/a | approval | PASSED WITH MOCK | P1, C3, C4-C5, P7 | 2026-11-02 |
| Tinder (notification) | simulator (plugin envelope shapes) | chat + match/like/system notices | `shortcut:` only; unlinked/ambiguous never merged | assisted delivery | never (UNSUPPORTED) | `user_reported_sent` only | n/a | refused | assisted | PASSED WITH MOCK | P2, C2, C5, C6, C8, P7 | 2026-11-02 |
| Bumble (notification) | simulator | chat + match/reaction/expiry notices | as Tinder | assisted delivery | never | `user_reported_sent` only | n/a | refused | assisted | PASSED WITH MOCK | P2, C3, C6, C9, P7 | 2026-11-02 |
| Hinge (notification) | simulator | chat + match/comment notices | as Tinder | assisted delivery | never | `user_reported_sent` only | n/a | refused | assisted | PASSED WITH MOCK | P2, C7, P5, P7 | 2026-11-02 |
| Hinge (experimental) | simulator (connector shapes) | chat via `unofficial_api` | match channel | yes | refused; forged autopilot canceled at claim and dispatch | simulated echo ack | n/a | refused | approval | PASSED WITH MOCK | P3, P5, P7 | 2026-11-02 |
| iMessage (Mac companion) | simulator (Swift golden pairing body) | 1:1 linked, group ambiguous | `imessage:chat:<guid>` | yes | refused | simulated `chat.db` ack; lease expiry = unknown | n/a | n/a | approval | PASSED WITH MOCK | P4, C4, C5, C9, P7 | 2026-11-02 |
| Profile / swipe | manual import | n/a | n/a | evaluation with reasons | n/a | n/a | manual import only | `422` everywhere, auto-swipe `422`, rate limit `429` | decision recorded | PASSED WITH MOCK | P5, P7 | 2026-11-02 |

## 4. Mock E2E suite (`tests/e2e/platforms`)

One command: `scripts/e2e/platforms/run.sh`. Same harness as WING-025 (D-018): the real backend
started as `scripts/dev-run.sh` does (API + worker in one process, dev auth stub with a random
secret, labeled `[dev mock]` LLM) through `scripts/e2e/platforms/scripted-dev-run.sh`, which adds
one labeled extension (`[e2e scripted]` replies that propose or confirm a date, triggered only by
a marker in the contact's message; `e2e_scripted_server.py`, no backend file changed). Postgres 16
migrated by Alembic. Each connector is a `PlatformDevice` that builds that connector's own
envelopes and capability claim (see `platform_harness.py`), claims with the paired install,
makes at most one transport attempt per key, and reports `connector_event.v1` outcomes. **No
real message is sent anywhere.**

| ID | Scenario | What is asserted | Result |
|---|---|---|---|
| P1 | SMS inbound -> contextual draft -> configured send | Client claim `VERIFIED` stored `UNVERIFIED`; duplicate upload deduplicated; with **autopilot selected** the reply waits for approval (`capability_unverified`); draft cites the inbound SMS; nothing outbound before approval; approve -> one instruction -> one transmission -> `sent` (not `delivered`); audit order received < draft < approval < approved < queued < attempted < acknowledged; next inbound again needs approval | PASSED WITH MOCK |
| P2 kinds (x3) | Tinder / Bumble / Hinge: match, like/comment, expiry, system; unlinked, ambiguous, truncated, hidden chats | Each is a notice with the right `kind` and `source: notification`; no conversation, draft, approval, assisted delivery, outbound, instruction | PASSED WITH MOCK |
| P2 assisted (x3) | Linked complete chat with autopilot on | Assisted delivery (draft only), no approval, no outbound, no instruction; re-posted notification deduplicated; "I sent it" = `user_reported_sent`, `sent_at`/`delivered_at` null, never upgraded by the worker; no `send_attempted`/`send_acknowledged`/`delivery_confirmed` | PASSED WITH MOCK |
| P2 no-reply (x3) | Notification without a reply action | Claim returns nothing; a forged acknowledgement is `422`; the user's own send is `422 capability_unsupported`; no send audit | PASSED WITH MOCK |
| P3 pair | Hinge experimental pairing | Without consent `422 experimental_consent_required`; with consent `experimental`, never verified, consent audited | PASSED WITH MOCK |
| P3 flow | Autopilot on Hinge experimental | Approval with `capability_unverified`; approved reply sent once; **fault injection**: an approved send whose origin is forged to `autopilot` in the DB is canceled at claim (`send_canceled` `capability_unverified`) and, after a retryable failure, canceled by the worker at dispatch (no attempt 2) | PASSED WITH MOCK |
| P3 revoke | Revoke experimental consent | Pending instruction and approval canceled, connection disconnected, all capabilities UNSUPPORTED, claims and new events `422`, audit `experimental_consent_revoked` | PASSED WITH MOCK |
| P4 | iMessage: online, group, offline, asleep | Approved reply sent once after the companion's ack; group = `ambiguous` notice only; Mac offline: approved send stays `queued` with a `pending` instruction; Mac asleep after a claim: `lease_expired` (`outcome_unknown`, `retried: false`), message stays `sending`, never `sent`/`failed`, never re-leased | PASSED WITH MOCK |
| P4 denied | Automation permission denied | Device lowers `send` to UNSUPPORTED; queued approved send canceled at claim; next reply is an assisted delivery | PASSED WITH MOCK |
| P5 | Discovery on Tinder, Bumble, Hinge, Hinge experimental | Import -> evaluation with reasons -> ranked queue -> decision `not_executable`; execute `422 swipe_execution_disabled`; auto-swipe `422`; refusals audited; nothing `queued` or `executed` | PASSED WITH MOCK |
| P5 rate | Decision rate limit | 31st decision in a minute `429 rate_limited` with `Retry-After`; a replay of an earlier key is still answered | PASSED WITH MOCK |
| C1 | Per-conversation override | Contact set to manual never sends; sibling on autopilot sends | PASSED WITH MOCK |
| C2 | Global kill switch | Pause cancels a queued delayed autopilot send; new drafts wait with `paused`; nothing automated sent after the delay | PASSED WITH MOCK |
| C3 | Permissions revoked, app upgrade | SMS role lost (send UNSUPPORTED): queued send canceled at claim, no approval, no assisted fallback; upgraded connector claiming `VERIFIED` stored `UNVERIFIED`; notification access revoked + unpair: assisted delivery canceled, uploads and claims `422` | PASSED WITH MOCK |
| C4 | Retries | Retryable failure -> the worker creates attempt 2 with a new key -> sent once; one transport attempt per key | PASSED WITH MOCK |
| C5 | Idempotency across restarts | SIGKILL then SIGTERM restart with work in flight on SMS, iMessage and Tinder: duplicate re-upload, replayed claim (ledger refuses a second attempt), replayed assisted confirm (`Idempotent-Replayed`), late ack accepted once, pending instruction leased once | PASSED WITH MOCK |
| C6 | Identity ambiguity and continuity | "Riley" on Tinder, Bumble and SMS: three contacts, never merged; unlinked and truncated "Riley" notifications are notices; an explicit user link is listed on both linked contacts only and merges no history; deleting it removes it | PASSED WITH MOCK |
| C7 | Serious -> casual mid-thread | Contact intent override: conversation `effective_intent` and new drafts become `casual_dating`; earlier messages and the earlier draft unchanged; user-wide intent unchanged | PASSED WITH MOCK |
| C8 | Date suggestions (WING-018 not built) | A proposed date waits for approval (`date_or_commitment`) even on autopilot with a VERIFIED simulator; a confirmation becomes ask-user (no send); on Tinder the assisted draft is flagged `date_or_commitment`; no calendar or date route is served; `calendar_write` UNSUPPORTED on every connection | PASSED WITH MOCK |
| C9 | Manual takeover mid-flight | iMessage pending send and Bumble assisted draft canceled; no new drafts while taken over | PASSED WITH MOCK |
| P7 | Release gates vs backend | For every gate in `config/release/feature_gates.json`, the backend's real behaviour with the platform's own claim and autopilot selected matches the gate (`approval`, `assisted_delivery`, `refused`, no route); a non-autonomous gate never sees an autonomous send; artifact `gate_crosscheck.json` | PASSED WITH MOCK |
| ZZ1 | Run-wide invariants | Zero duplicate acknowledgements, transport attempts, instructions, drafts, approval sends, over-replied conversations, simulated transport attempts; **no false send claims**: nothing sent/queued/leased on a notification connection, no `user_reported_sent` with `sent_at`, no autopilot send on a real platform, no profile action `queued`/`executed`; acks = sent | PASSED WITH MOCK |
| ZZ2 | Server log | No traceback, integrity error, failed dispatch or 500 over the run (incl. two restarts) | PASSED WITH MOCK |

Determinism: three consecutive local runs, same results (see the PR). Artifacts per run in
`tests/e2e/platforms/.artifacts/`: `server.log`, `junit.xml`, `platform_invariants.json`
(zero-duplicate counters), `scenario_results.json`, `gate_crosscheck.json`.

## 5. Manual real-device scripts

Each script points to the platform's existing checklist (not duplicated) and says what to
capture for the unified evidence log (§7, template
`scripts/e2e/platforms/manual/evidence_log_template.md`). Common to every script:

- **Setup:** the backend (`scripts/dev-run.sh` on a LAN machine or the beta backend), a
  signed-in debug build with `-Pwingman.useMockApi=false`, a spare device, and **a peer that
  agreed to take part**. Never a real person's conversation.
- **Controlled input:** fixed probe texts `WING032 <platform> probe <n>` (so logs can be
  searched and then redacted), sent by the tester from the peer device or account.
- **Artifacts:** `collect_backend_evidence.py --run-id <id>` output (IDs, states, counts, reason
  codes only) and redacted logs; every file must pass
  `redaction_check.py --forbid WING032 --forbid <match label>` before it is attached.
- **Pass/fail:** a capability passes only when the peer device or official app shows the
  observed effect exactly once; "executed" or an exit code is never evidence of delivery.

### 5.1 R-SMS: Android SMS (phone with a SIM)

| Step | Setup / controlled input | Expected | User action | Artifact | Pass / fail |
|---|---|---|---|---|---|
| S1 | `android_sms.md` §7 D1-D2 (role, permissions) | default SMS app; read/send UNVERIFIED in the capability JSON | grant role and permissions | screenshot of the harness (numbers blurred) | role held and capabilities shown honestly |
| S2 | D3: peer sends `WING032 sms probe 1` | one `message.received` envelope; the peer receives nothing | none | backend evidence: `messages: inbound/contact/received = 1` | exactly one inbound, no outbound |
| S3 | P3-P5: pair from Settings; approve the draft Wingman shows | approval reasons include `capability_unverified`; after approval the phone claims and sends | approve in Approvals | backend evidence: `outbound/approved_draft/sent = 1`, audit `send_acknowledged` once | peer receives the reply **once**; Wingman shows Sent (not Delivered unless a carrier report arrives) |
| S4 | D5-D6 + B2/B4: replay and force-stop | no second SMS | `adb shell am force-stop com.wingman`, reopen | backend evidence `messages_sent_twice: []` | peer still has exactly one reply |
| S5 | D7: airplane mode during a send | `send_failed` `not_connected` retryable, no automatic resend of that key | toggle airplane mode | audit `send_failed` then a new attempt only after the worker's backoff | never two copies |
| S6 | P6/P8: unpair with a queued reply | queued reply canceled, nothing more sent | Disconnect in Settings | backend evidence: `outbox.by_state.canceled >= 1` | peer receives nothing after unpair |

PASS of S2 makes `read` eligible, S3-S5 `send` (operator `verify` CLI with
`--evidence "cross_platform_test_matrix.md R-SMS-<date>"`).

### 5.2 R-TINDER / R-BUMBLE / R-HINGE-N: notification path (phone + test accounts)

| Step | Setup / controlled input | Expected | User action | Artifact | Pass / fail |
|---|---|---|---|---|---|
| T1 | `notification_matrix.md` §4 N1-N3, N10; `tinder.md` T1 | package exact match; cert digest recorded | grant notification access | inspector line (redacted), digest | correct package; digest pinned in a follow-up PR |
| T2 | N4-N8: second account sends `WING032 tinder probe 1`, then a long one, then locked; trigger a match and a like (Bumble: expiry, reaction, first move; Hinge: comment) | chat -> `chat_message` (linked only with `shortcut_id`); everything else -> notice of the right kind | none | per-app shape table in `notification_matrix.md`; backend evidence `notices` by kind/identity | every non-chat is a notice; no draft for unlinked/hidden/truncated |
| T3 | T3-T5: reading on; a linked chat arrives | assisted delivery under **Send by hand**; no approval, no send | Copy, Open app, send in the dating app by hand, tap **I sent it** | backend evidence: `assisted_deliveries.user_reported_sent = 1`, `messages: outbound/assisted_draft/user_reported_sent = 1`, no `send_*` audit | the peer receives exactly the text the tester sent; Wingman never shows Sent/Delivered |
| T4 | N9: reply by hand from the shade (if an action exists) | informational only: does the app's RemoteInput work at all | reply in the shade | note yes/no | informs a future `send` UNVERIFIED (approval-only), never autopilot |
| T5 | T7/N11: revoke notification access | reading stops, capability downgrade uploaded | system settings | backend evidence: `capabilities.read = UNSUPPORTED` | next message not recorded |
| T6 | T8/N12: privacy | no text or names in logs | none | `redaction_check.py` on the logcat | clean |

Bumble adds `bumble.md` B1-B2; Hinge (notification) runs only after I-030 is decided.

### 5.3 R-HINGE-X: experimental Hinge API (owner only)

Run `hinge.md` §9 **L0-L10** exactly as written (owner's own account, experimental APK, consent
screen). Add to the evidence entry: backend evidence after L4 (no duplicate inbound), L5-L6
(`approval_requested` with `capability_unverified` before any send; `send_acknowledged` once),
L7-L8 (`messages_sent_twice: []`, at most one attempt per key), L9 (`experimental_consent_revoked`,
no further claims). Stop at the first `blocked`. Pass = L0-L10 all pass; only then
`verify-experimental` and `verify --capability read|send`.

### 5.4 R-IMESSAGE: Mac companion

Run `imessage_companion.md` §6 **M1-M14** (probe) then **A1-A17** (signed app). Add to the
evidence entry: backend evidence after A4 (inbound linked), A5 (`notices` `chat_message/ambiguous`),
A6 (`outbound/approved_draft/sent = 1`, ack only after the `chat.db` row), A7
(`send_failed` `permission_denied`, no retry), A12 (`lease_expired` `outcome_unknown`, never
`sent`), A13 (`messages_sent_twice: []`). Pass for `read`: M2-M4, A3, A4; for `send`: M8, M9,
M12, M14, A6-A8, A13.

### 5.5 R-SWIPE: discovery

Nothing to run on a device: no connector reads profiles and no swipe executor exists (D-027).
The only manual check is UI honesty: Discovery shows "Decision recorded" and "Not executable on
<platform>", Execute stays disabled with its reason, Settings > Auto-swipe cannot be turned on.
Artifact: screenshots (names blurred). Pass = no label claims a platform action.

## 6. How to run

```bash
scripts/e2e/platforms/run.sh                 # whole suite, about 6 minutes (two 120 s lease waits are the bulk)
scripts/e2e/platforms/run.sh -k imessage     # one area
python3 scripts/release/check_feature_gates.py --summary
python3 -m unittest discover -s tests/release -v
scripts/release/check.sh                     # gates + the five release checks
python3 scripts/e2e/platforms/manual/redaction_check.py --self-test
```

Needs `uv`, Postgres 16 server binaries (or `WINGMAN_E2E_DATABASE_URL` pointing at an EMPTY
database) and a free port 8000. CI: workflow `Platform E2E` (`.github/workflows/platform-e2e.yml`),
jobs `Platform E2E (mock connectors)` and `Release feature gates`.

## 7. Evidence log

Real-device and real-account runs only, newest last, one block per run from
`scripts/e2e/platforms/manual/evidence_log_template.md`. A gate may cite a run here only as
`PASSED ON DEVICE` with `verification: VERIFIED` in `config/release/feature_gates.json`.

| Run id | Platform | Date | Result | Entry |
|---|---|---|---|---|
| — | — | — | No real-device or real-account run has been recorded. | — |

## 8. What the mock suite cannot show

- **Anything about the real apps**: notification shapes, `shortcut_id` presence, truncation,
  RemoteInput behaviour, Hinge endpoints and enforcement, AppleScript and `chat.db` behaviour on
  current macOS, carrier delivery reports. The simulators follow the connectors' code and
  fixtures, which are themselves synthetic until the device runs.
- **The Android and macOS client code paths**: the suite drives the backend with their wire
  shapes; the clients' own logic is covered by JVM tests and XCTests (and, for SMS, the WING-025
  emulator job), not here.
- **Expired connection handling server-side** (resolved after WING-032 by PR #58, D-030):
  `connection.status_changed` `error`/`disconnected` now holds the connection's sends in the
  dispatcher, the claim and the worker; `connected` resumes them exactly once (mock E2E P8,
  `backend/tests/automation/test_connection_status_hold.py`). What a real device reports, and when,
  is still UNVERIFIED.
- **Content guardrails** beyond dates (fabricated facts, explicit wording, escalation): unit
  level only (I-026).
- **Real LLM quality**: only the deterministic dev mock ran (I-009).
- **Autopilot `dispatch`/`claim` cancel on experimental connections** cannot be produced through
  the API at all (the policy never queues an autopilot send there); P3 reaches those re-checks
  only by fault injection in the database.
