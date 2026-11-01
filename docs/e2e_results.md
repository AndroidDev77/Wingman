# WING-025: end-to-end acceptance results (gate G6)

**MVP status: BLOCKED, with proof of why.** No real channel has sent a response on a physical
device. The one real-channel candidate, Android SMS, was exercised end to end on an Android
**emulator** in CI (inbound SMS, backend ingest, approval, the app's `SmsManager` send, the
server records `sent`). An emulator is not a physical device or a carrier, so this does **not**
satisfy "one real channel sends a response on device". G5 stays ⛔ and SMS `read` and `send`
stay **UNVERIFIED** until a human runs the device checklist below (I-003). Everything that can be
proven without a phone and a SIM passes: the mock end-to-end flow with zero duplicate sends,
failure injection, and the Android app driving the backend on an emulator.

Labels follow `AGENT_RULES.md`: **VERIFIED**, **UNVERIFIED**, **UNSUPPORTED**. "PASS" below
means a test that ran and passed in the run linked next to it; nothing here was inferred.

## How to reproduce

| What | Command | Where it ran |
|---|---|---|
| Backend E2E suite (flows, failure injection, zero-duplicate invariant) | `scripts/e2e/run.sh` | Locally (sandbox, Postgres 16 throwaway cluster) and CI job `E2E backend` |
| Android on an emulator (app drives backend, emulator SMS) | `scripts/e2e/emulator-tests.sh` (needs a running emulator, `scripts/dev-run.sh` with the dev auth stub, and the APKs from `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pwingman.useMockApi=false`) | CI job `E2E Android emulator` only. The agent sandbox has no `/dev/kvm`, so no emulator could run locally; the APKs were built locally |
| Unchanged gates | `scripts/backend-check.sh`, `contracts/validate.sh`, `scripts/migration-check.sh`, `scripts/android-build.sh` | Locally and CI (`ci.yml`) |

### Environment

- **Backend under test:** `scripts/dev-run.sh` (`uvicorn app.worker.dev_server:app`): the normal
  API and the background worker in one process (I-016), `WINGMAN_ENV=dev`, the dev auth stub with
  a random secret per run, the labeled deterministic dev mock LLM (`[dev mock]`), Postgres 16
  migrated with `alembic upgrade head`. Worker ticks shortened for the test only
  (`WINGMAN_WORKER_DISPATCH_INTERVAL_S=1`, `..._LEASE_EXPIRY_INTERVAL_S=1` (backend suite) or `5`
  (emulator), `..._APPROVAL_EXPIRY_INTERVAL_S=5`). Lease length is the production default (120 s).
- **Transports:** `mock` connections use the in-process `MockConnector` (simulator,
  `VERIFIED` against the simulator only). In the backend suite, `sms` connections are driven by
  `SimulatedSmsDevice` (`tests/e2e/harness.py`), a protocol-faithful stand-in for the Android
  connector: it claims with `claimOutboxInstructions`, makes at most one "radio" attempt per
  instruction key, reports `connector_event.v1` outcomes, and sends nothing.
- **Emulator:** GitHub `ubuntu-24.04` runner with KVM, `reactivecircus/android-emulator-runner`
  v2.38.0 (pinned by SHA), API 34 `google_apis` x86_64, `pixel_6` profile. The app is the debug
  build with `-Pwingman.useMockApi=false` and `API_BASE_URL=http://10.0.2.2:8000/`, launched
  normally and driven through the accessibility tree with UiAutomator.
- Local runs: Linux sandbox, Python 3.12, `uv` 0.11, Postgres 16 server binaries, OpenJDK 21,
  Android SDK platform 35 (no KVM).

## Results per scenario

CI runs (PR #34): see [Evidence](#evidence). Integrator-verified: every result below was re-read
from the CI job logs of commit `863ce0a` (the first commit with both product fixes on which all
seven jobs passed), and the integrator reproduced the backend suite locally (17 passed in 195 s).

### Backend flows (`tests/e2e/test_flows.py`)

| ID | Scenario | What is asserted (all over HTTP, contract-validated bodies) | Result |
|---|---|---|---|
| F1 | Onboarding and preferences | New user: `version 0`, mode `manual`. Save with `If-Match: "0"` gives version 1; a stale `If-Match` is `412 precondition_failed` and overwrites nothing; history listed; audit `preferences_updated` (actor `user`) | PASS |
| F2 | Pairing a mock connection, message injection | `POST /v1/connections` `201`; same key replays the same connection (`Idempotent-Replayed: true`); mock `send` is `VERIFIED` with the "Simulated transport" note; injected `mock.inbound_message_injected` is `202 accepted`; conversation, detail and message read routes; audit `connection_paired`, `message_received`; default manual mode creates no approval and no send | PASS |
| F3 | Personalized reply (style, grounding, guardrails) | Draft is `send_candidate`, labeled `[dev mock]`, grounded in exactly the contact's message, intent `serious`. Style `punctuation: minimal` removes the `!` the dev handler writes; switching to `expressive` keeps it (createDraft on request). Assisted mode never sends without approval. Draft audit rows carry no text. **Not covered here:** content guardrails (fabricated user facts, explicit or coercive wording, unsupported escalation) need a model that produces such output; the dev mock never does. They are covered by `backend/tests/ai/replies` (unit level), not by this suite | PASS |
| F4 | Assisted approval, approve-with-edit, reject, send | Nothing outbound before approval. Approve: response says `queued` (never `sent`), then the server reports `sent` with exactly the draft text, origin `approved_draft`. Replayed approve key returns the same message; a second approve is `409 approval_not_pending`. Approve-with-edit sends exactly the edited text (`final_text`, audit `edited: true`). Reject sends nothing. Audit order `approval_approved` < `send_queued` < `send_attempted` < `send_acknowledged` | PASS |
| F5 | Memory: list, correct, delete | One fact seeded in Postgres (the dev mock extracts none, by design; every other operation is HTTP). Listed with its source message; another user gets `404` on list, correct and delete; correction is a new `user_correction` that supersedes it (history shows both); delete is `204` and idempotent | PASS |
| F6 | Autopilot with a reply delay, processed by the worker | First message to a contact needs approval (`first_message`, not `assisted_mode`). Next reply is an `autopilot` message, `queued` with `send_after` set; the request did not send it; the worker sends it once due (one `send_attempted`, actor `dispatcher`) | PASS |
| F7 | Takeover and pause per D-014 | Contact pause cancels a queued delayed autopilot reply (`canceled_message_count: 1`); while paused a new draft is an approval with reason `paused`; approving it still sends; the user's own send still goes. Global pause and resume. Takeover cancels the pending approval (`canceled_approval_count: 1`, later approve is `409`), no new drafts while taken over, the user's own send goes. Audit: `automation_paused`, `automation_resumed`, `automation_takeover`; cancellations carry `automation_paused` / `automation_takeover` | PASS |
| F8 | Unpair cancellations | On an `sms` connection: one approved reply queued with a pending instruction, one pending approval. Unpair returns `canceled_message_count 1`, `canceled_instruction_count 1`, `canceled_approval_count 1`, `in_flight 0`; connection `disconnected`, all capabilities `UNSUPPORTED`; claims are `422`, new events `422`, `sendMessage` `422 capability_unsupported`; one `connection_unpaired` audit; history kept; the device transmitted nothing | PASS |
| F9 | Cross-user isolation | 13 reads and actions on another user's connection, conversation, messages, approval, contact automation and preferences, outbox, approve, reject, send, draft, takeover and unpair: all `404 not_found` (never `403`); a forged event on the other user's connection is `404`; the intruder's listings are empty; the owner's state is unchanged | PASS |

The audit log is checked in every flow above (types, order, actor, reason codes, redaction).

### Failure injection (`tests/e2e/test_failures.py`)

| ID | Scenario | What is asserted | Result |
|---|---|---|---|
| X1 | Duplicate inbound, concurrent and sequential | Same event 12x in parallel: exactly one `202 accepted`, eleven `200 duplicate_event`. Same message under 12 different event IDs in parallel: one `202`, eleven `200 duplicate_message`. Then both again sequentially: duplicates. Exactly 3 inbound messages, 3 `message_received`, 3 drafts; at most one autopilot reply per distinct inbound message | PASS |
| X2 | Offline device, then lease expiry | An `sms` device that never claims: the approved reply stays `queued` with one `pending` instruction across ~6 worker ticks, never sent. A device that claims and goes silent: after the server-set 120 s lease the worker writes `lease_expired` (actor `system`, reason `outcome_unknown`, `retried: false`); the message stays `sending` (never `sent`, never `failed`), `attempt_count` 1, no new instruction, the same install cannot re-claim it, no `send_failed`. A late acknowledgement is still accepted and the message becomes `sent` | PASS |
| X3 | Retry after a retryable failure, on the worker, as a new attempt | Device reports `send_failed` `not_connected` `retryable: true`: message back to `queued` with the failure recorded and audit `send_queued` `retry: true, next_attempt: 2`. With no client call, the worker creates attempt 2 with a new key (`...:2`) after the 30 s backoff; the device sends it once; message `sent`, `attempt_count` 2; one radio attempt per key | PASS |
| X3b | Non-retryable failure | `send_failed` `permission_denied` `retryable: false` gives `dead_lettered`; no new instruction; nothing to claim | PASS |
| X4 | Cancellation via takeover mid-flight | One reply leased by the device (`sending`), one not yet claimed. Takeover cancels the unclaimed message and its instruction (`send_canceled`, reason `automation_takeover`), and cannot recall the leased one, whose real acknowledgement is still recorded (`sent`). The device never received the canceled one | PASS |
| X5 | Server restart mid-flow (SIGKILL, then SIGTERM) | Work in flight: one SMS instruction leased and transmitted but not acknowledged, one pending, one mock conversation with a sent reply. After `SIGKILL` and restart: states intact (`sending`, `queued`). The device replays its old claim key and gets the same lease (its ledger refuses a second radio attempt); the late ack is accepted (`202`), its re-upload is a duplicate (`200`). After `SIGTERM` and restart: the pending instruction is leased once and sent. A new mock send after the restarts is acknowledged with a distinct platform ID. Exactly one `send_acknowledged` per message | PASS (after the fix in [Product bug](#product-bug-found-and-fixed)) |

### Run-wide invariants (`tests/e2e/test_zz_invariants.py`, runs last)

**Zero duplicate sends over the whole run.** Checked in Postgres over every user of the run:
no message acknowledged twice, no `(message, attempt)` handed to a transport twice, no duplicate
`(message, attempt)` instruction, one draft per inbound message, at most one outbound message
per approval, no conversation with more agent sends than inbound messages, and every simulated
device at most one radio attempt per key. The summary is written to
`zero_duplicate_sends.json`. Local run and CI run (identical counts):

```
users 17, inbound 28, outbound 22, sent 18, send_acknowledged 18, send_attempted 20,
instructions 9, simulated radio attempts 7, server starts 3,
duplicate_acknowledgements 0, duplicate_transport_attempts 0, duplicate_instructions 0,
duplicate_drafts 0, duplicate_approval_sends 0, conversations_over_replied 0,
duplicate_radio_attempts 0
```

(`send_attempted` 20 vs. `sent` 18: the two attempts that never ended in an acknowledgement are
X3's failed attempt 1 (retried as attempt 2, which was sent) and X3b's dead-lettered send. X2's
lapsed lease is not one of them: its late acknowledgement was accepted and that message counts as
`sent`. Integrator correction: an earlier version of this paragraph listed X2 as well.)

The server log of the whole run has no traceback, `IntegrityError`, failed post-commit dispatch,
failed pipeline or HTTP 500.

### Android drives the backend (`android/app/src/androidTest`, CI emulator)

`AppDrivesBackendE2eTest`, one test, as a user on the emulator against the backend on the runner.
Each UI step is cross-checked against the server:

| Step | UI observation | Server check |
|---|---|---|
| A1 Sign in | Debug-only "Developer sign-in" panel; token minted by `python -m app.security.dev_stub` and passed as an instrumentation argument | `GET /v1/me` |
| A2 Onboarding | Opens on its own for a never-saved user; Serious relationship, skip style, Assisted (confirm dialog), Review, Save and finish, lands on the Inbox | preferences `version >= 1`, intent `serious`, mode `assisted` |
| A3 Inbox | After a contact's message is injected (the contact's side, over the API), the Inbox shows the contact and the message preview | conversation exists, approval pending |
| A4 Approvals | Draft labeled `[dev mock]`, "Nothing is sent until you approve."; tap Approve; "Approved. It shows as sent only after the messaging app confirms it." | outbound message `sent`, origin `approved_draft` |
| A5 Activity (audit timeline from the server) | Rows "Message received", "Draft proposed for your approval", "Draft approved (not sent yet)", "Queued, not sent yet", "Handed to the messaging app, not confirmed yet", "Sent (delivery not confirmed)" | audit has `send_queued`, `send_attempted`, `send_acknowledged`, `approval_approved` |
| A6 Takeover | Controls: flip "I'm taking over", confirm "Take over with ...?", see "You've taken over." and "You're in control: Wingman is off for this person" | contact `taken_over`, audit `automation_takeover` |

**Observed: PASS** in CI job
[124946258804](https://github.com/AndroidDev77/Wingman/actions/runs/41339483882/job/124946258804)
(commit `863ce0a`, `OK (1 test)`, 44 s), and before the integrator's fixes in
[124930492429](https://github.com/AndroidDev77/Wingman/actions/runs/41334216255/job/124930492429)
(commit `5c6feea`, 29 s). Logcat (`WING025-APP`) of the `5c6feea` run:

```
18:03:12.243 WING025-APP signed in; onboarding opened
18:03:20.592 WING025-APP onboarding saved: version=1 intent=serious mode=assisted
18:03:21.833 WING025-APP inbox shows Emu3342162551 with the message preview
18:03:23.456 WING025-APP approved in the app; server message 01a19847-c616-73b1-8825-d0db6c856c71 is sent
18:03:27.533 WING025-APP activity tab shows: message received, proposed, approved, queued, handed over, sent; server audit=[send_acknowledged, send_attempted, send_queued, approval_approved, approval_requested, draft_generated, analysis_updated, message_received]
18:03:30.729 WING025-APP takeover confirmed in the app and on the server (status=taken_over, audit automation_takeover)
```

How it got there (all failures were in the test harness, none in the app; each fix is its own
commit on the PR):
| Run (commit) | Phase A | Cause | Fix |
|---|---|---|---|
| 1 (`3d2d78e`) | FAIL | Under `createAndroidComposeRule` the Compose test environment replaces the Recomposer's dispatcher; the first-launch onboarding `LaunchedEffect` resumed on OkHttp's thread and `NavController.navigate` threw "setCurrentState must be called on the main thread" | Launch the unmodified `MainActivity` with `ActivityScenario` and drive it with UiAutomator |
| 2 (`0b1ebd5`) | FAIL | "UI never showed Next" after onboarding opened. Suspected the soft keyboard (opened by tapping the token field) covering the bottom; not confirmed, run 3 shows the real cause was the selector below | Set the token without a tap; hide the keyboard if shown; dump the UI hierarchy on a failed wait (kept: it is what diagnosed run 3) |
| 3 (`ed89a86`) | FAIL | The hierarchy dump showed the app in the expected state (onboarding saved, Inbox open, bottom bar visible). The bottom-nav label is a plain Text node and its clickable parent has no text, so `textContains(..).clickable(true)` matched nothing (the same selector style explains run 2's "Next") | Match the exact label; tap the takeover dialog's button below its title |
| 4 (`5c6feea`) | PASS | | |
| 5 (`6f4f9cf`, integrator) | FAIL | Both phases: the hierarchy dump showed the system dialog "Pixel Launcher isn't responding" (Close app / Wait) over the screen on the freshly booted emulator; the app was hidden behind it | `E2eDevice` taps "Wait" on a system ANR dialog of another app (never "Close app"; an ANR of Wingman fails the test). The sign-in helper now fails loudly instead of assuming "already signed in" |


### Real channel: SMS on the CI emulator ("emulator SMS stack exercised")

`EmulatorSmsRoundTripTest` plus `scripts/e2e/emulator-tests.sh`:

1. `pm grant` RECEIVE_SMS, SEND_SMS, READ_SMS; `cmd role add-role-holder android.app.role.SMS com.wingman` (retried until the holder is `com.wingman`).
2. Sign in, pair from **Settings > Connections > Phone SMS** (the app's own pairing flow,
   `createConnection` with this install's `device_install_id`). The screen shows
   "Status: Connected", "Send replies: UNVERIFIED (not yet tested on a device)" and
   "Autopilot: not available for SMS"; the server stored `send: UNVERIFIED`.
3. The host runs `adb emu sms send 5551234567 WING025-emulator-hello`. The app's `SMS_DELIVER`
   receiver records it; a sync uploads `message.received`; the backend has the inbound message
   with the exact text, thread `sms:5551234567`.
4. The approval has reasons `assisted_mode, capability_unverified, first_message`. Approve it in
   the app. A sync claims the leased instruction and calls the emulator's `SmsManager`; the
   per-part "sent" result arrives; the next sync uploads `message.send_acknowledged`; the server
   reports the reply sent.

**Observed: PASS** in CI job
[124946258804](https://github.com/AndroidDev77/Wingman/actions/runs/41339483882/job/124946258804)
(commit `863ce0a`, with the completion-marker check: `OK (1 test)`, the `RESULT` line, outbound
`delivered` from the emulator modem, `attempt=1`), in
[124930492429](https://github.com/AndroidDev77/Wingman/actions/runs/41334216255/job/124930492429)
(commit `5c6feea`), and earlier also in the first run
([124922334161](https://github.com/AndroidDev77/Wingman/actions/runs/41331480134/job/124922334161),
commit `3d2d78e`). Script and logcat lines from the `5c6feea` run (`WING025-SMS`, timestamps kept; the `863ce0a` run
prints the same sequence):

```
default SMS role holder before: com.google.android.apps.messaging
add-role-holder attempt 1:
default SMS role holder after: com.wingman
18:03:38.862 WING025-SMS device state: telephony=true role=true receive=true send=true default_sms_package=com.wingman
18:03:59.190 WING025-SMS paired connection=01a19848-512e-719f-9018-a97fc4f38828 install=705b1122-4fe7-4f0c-9ee2-3e38d97e40a0 send=UNVERIFIED
18:03:59.190 WING025-SMS READY_FOR_SMS (waiting for adb emu sms send 5551234567 ...)
[18:04:00] ==> test is ready: adb emu sms send 5551234567 WING025-emulator-hello
18:04:01.355 WING025-SMS sync -> uploaded 1, sent 0
18:04:01.384 WING025-SMS inbound ingested: conversation=01a19848-5c35-7698-942a-642c82142312 thread=sms:5551234567 text_matches=true
18:04:01.406 WING025-SMS approval reasons=[assisted_mode, capability_unverified, first_message]
18:04:02.951 WING025-SMS sync -> uploaded 0, sent 1
18:04:06.061 WING025-SMS sync -> uploaded 2, sent 0
18:04:06.072 WING025-SMS outbound state=delivered external_message_id=sms:out:send:01a19848-5fd4-7006-b72a-f1a484b50569:1 attempt=1
18:04:06.083 WING025-SMS audit=[delivery_confirmed, send_acknowledged, send_attempted, send_queued, approval_approved, approval_requested, draft_generated, analysis_updated, message_received]
OK (1 test)
```

What this shows and what it does not:

- It shows the whole Android SMS code path running against the real backend on an Android OS:
  role-qualifying components, `SMS_DELIVER` intake, envelope upload, pairing, claim with the paired
  install, `SmsManager.sendMultipartTextMessage`, the per-part sent and delivered
  `PendingIntent`s, outcome upload, and the server moving the message to `sent` and then
  `delivered`. "uploaded 2" is the acknowledgement and the delivery receipt.
- The **"delivered" came from the emulator's simulated modem**, which reports delivery for any
  message. It says nothing about a carrier.
- It is **not** a physical device: no SIM, no radio, no carrier, no second phone received anything.
  Per AGENT_RULES.md and the I-003 rule, that is not VERIFIED. The operator `verify` CLI was **not**
  run and SMS capabilities were **not** changed.
- Integrator hardening (after review): `EmulatorSmsRoundTripTest` returns early through
  `assumeTrue` when `wingman.sms` is not set, and an assumption-skipped test also prints
  `OK (1 test)`. `scripts/e2e/emulator-tests.sh` now also requires the `files/wing025-done` marker
  the test writes after its last assertion, so a skipped phase B can no longer count as PASS.
  Every run cited above did log the full round trip (the `RESULT` line), so no result changes.
- In the first run, `cmd role add-role-holder` printed `java.util.concurrent.TimeoutException`
  and `get-role-holders` still listed `com.google.android.apps.messaging` right after it, while the
  app's own check (`RoleManager.isRoleHeld`) said `role=true` about 10 s later and `SMS_DELIVER`
  reached the app. The script now retries until `get-role-holders` prints `com.wingman`; in the
  passing run it did on the first attempt, and `Telephony.Sms.getDefaultSmsPackage` was
  `com.wingman`.

## Connector matrix

| Connector | Read | Send | Status | Evidence / blocker |
|---|---|---|---|---|
| `mock` (WING-006 simulator) | VERIFIED (simulator only) | VERIFIED (simulator only) | Every backend flow and failure scenario above | "VERIFIED" means the deterministic simulator's contract is tested; it is not a platform. Never available in prod |
| SMS on the CI **emulator** | Exercised (emulator) | Exercised (emulator) | **"emulator SMS stack exercised"**: PASS in CI | Not a physical device, not a carrier. Does not change any capability label. Stored capability stays `UNVERIFIED` |
| SMS on a **physical device** | **UNVERIFIED** | **UNVERIFIED** | Blocked on **I-003** (needs a human, an Android phone with a SIM, and a second phone) | Run the checklist below; only then may an operator record evidence with the `verify` CLI |
| Tinder / Bumble / Hinge | UNSUPPORTED | UNSUPPORTED | Not built (WING-021 is research only, pending) | Not pairable in contract 1.2.0 |
| iMessage | UNSUPPORTED | UNSUPPORTED | Not built (WING-020 feasibility, pending) | Not pairable in contract 1.2.0 |
| Android notification direct reply | UNVERIFIED (no connector) | UNVERIFIED (no connector) | Not built (WING-017 optional spike, pending) | `android_notification` is pairable in the contract, but no app code pairs it |

### Physical-device checklist a human must run (I-003)

All steps are in [`docs/integrations/android_sms.md`](integrations/android_sms.md), section 7.
Prerequisites (section 6): a physical Android phone (API 26+, Android 10+ preferred) with an active
SIM, a second "peer" phone, `adb` with USB debugging, the debug build, and acceptance that Wingman is
the default SMS app for the test. Record the run as `## Device run YYYY-MM-DD` at the end of that
file (device model, Android version, carrier, build commit, PASS/FAIL per step).

| Group | Steps | What they prove |
|---|---|---|
| Device (harness) | D1 role, D2 permissions and capability document, D3 **receive**, D4 **send** (and carrier delivery reports), D5 same key sends nothing more, D6 no double reply after process death, D7 airplane-mode failure mapping, D8 multipart out, D9 multipart in, D10 dual SIM, D11 envelopes validate against the schema, D12 role loss detected | `read`, `send`, D-006 on a real radio |
| Backend round trip | B1 uplink `202`, B2 crash during upload (server dedup), B3 **downlink: approve in the app, peer receives once, server `sent`**, B4 claim replay after a lost response, B5 lease deadline (no send, `timeout` failure), B6 dead letter | The ADR 0006 protocol on a real phone |
| Pairing | P1 role from the screen, P2 permissions, P3 pair with an airplane-mode retry (one connection, same key), P4 honest capability display, P5 ingest and claims on the paired ID, P6 unpair cancels, P7 re-pair creates a new connection, P8 unpaired elsewhere detected on start | I-020 on a real phone |

Only after D3, D4 and B3 pass on a physical device may an operator run
`python -m app.services.connections verify --user-id U --connection-id C --capability send --evidence <link to the device run section>`
(and `read`). Until then G5 stays ⛔ and autopilot stays unavailable for SMS.

## Product bugs found and fixed

### 1. MockConnector outbound IDs collided

**MockConnector outbound IDs collided (dev/test simulator, `backend/app/connectors/mock.py`).**
Found by the first local E2E run (X5 and a probe with two mock connections). The simulator
numbered acknowledged sends `mock-msg-out-0001`, `-0002`, ... per `MockConnector` instance. The
backend stores outbound platform IDs under the unique `(user_id, platform, external_message_id)`.
A user with a second mock connection, or any mock connection after a process restart (a new
simulator starting at 1 again), got `mock-msg-out-0001` for a different message: the
acknowledgement failed with `IntegrityError` ("dispatch after commit failed"), and the message was
left `queued`/`sending` forever. Fix: the ID is derived from the per-attempt key,
`mock-msg-out:<idempotency_key>`, as SMS already does (`sms:out:<key>`), so it is unique per
attempt and stable across a replay. Regression test:
`backend/tests/connectors/test_mock_connector.py::test_outbound_ids_are_unique_across_connector_instances`
(fails before the fix, passes after). The E2E scenario X5 also fails without the fix (observed:
"timed out after 20s waiting for message ... in {'sent'}") and passes with it. No real platform is
affected; `MockConnector` is never used in prod.

### 2. Request transactions committed after the response was sent (integrator, during review)

**Found by the CI E2E run on the integrator's commit `6f4f9cf`**
([job 124942090473](https://github.com/AndroidDev77/Wingman/actions/runs/41338088423/job/124942090473)):
X2 failed with `assert 'queued' == 'sending'` right after a claim had returned the instruction
(`200`). The other 16 tests passed; earlier runs of the same test passed. Cause: `get_session`
(`backend/app/db/session.py`) is a `yield` dependency that commits on exit, and with FastAPI
0.162's default `request` scope that exit code runs only **after the response has been sent**
(shown under uvicorn with a minimal probe app: the client had the response before the dependency
exited; with `scope="function"` the order reverses). So any client could read its own write
before it was committed, and a failed commit could follow a success response. Fix: every
`Depends(get_session)` is now `Depends(get_session, scope="function")` (12 lines, no behavior
change besides the order). Regression test:
`backend/tests/test_app_wiring.py::test_request_sessions_commit_before_the_response_is_sent`
(fails on the previous code, passes after). Effect on earlier results: none of them depended on
the race except by luck of timing; every result in this document is from runs that include the
fix or that passed regardless (see [Evidence](#evidence)).

## Unresolved blockers and limits

| ID | Blocker | Effect on this ticket |
|---|---|---|
| I-003 | No physical Android device with a SIM is available to agents | "One real channel sends a response on device" cannot be met; MVP labeled blocked (above). G5 ⛔ |
| I-014 | No identity provider chosen | Android sign-in here uses the debug-only dev-token path; OIDC on a device is UNVERIFIED |
| I-016 | Mock state lives in process memory | Restart scenarios re-teach the mock thread with a new inbound message after a restart (a fresh simulator does not know old threads); delayed mock sends due after a restart are dead-lettered "unknown mock thread" (dev only, observed while exploring; not a real-platform issue) |
| I-024 | No periodic Android SMS sync | The emulator test triggers sync rounds itself (`SmsConnectorRuntime.syncInBackground`); on a real phone claims and uploads wait until the app opens |
| (new) | Compose UI tests under `createAndroidComposeRule` cannot launch `MainActivity` | The test rule's effect dispatcher made the first-launch onboarding `LaunchedEffect` call `NavController.navigate` off the main thread ("setCurrentState must be called on the main thread"). A test-environment artifact (the app launched normally does not run effects that way), so the instrumented tests use UiAutomator. Worth a follow-up if Compose-rule tests of the shell are wanted |
| (limit) | Content guardrails are not E2E-testable with the dev mock | The dev mock never writes unsafe, ungrounded or escalating text; those guardrails are covered at unit level (`backend/tests/ai/replies`) |
| (limit) | CI emulator noise | A freshly booted emulator can show a system ANR dialog for another app ("Pixel Launcher isn't responding", run 5); the helper taps Wait. Not an app failure; a Wingman ANR still fails the test |
| (limit) | Delivery receipts | Only the emulator's simulated modem produced one; carrier delivery reports are D4 on a physical device |

## Evidence

CI on PR [#34](https://github.com/AndroidDev77/Wingman/pull/34). Each job below was read in its log
by the integrator. Commit `863ce0a` is the first with both product fixes on which all seven jobs
passed; the PR description lists every job on the final head commit.

| Job | Commit `863ce0a` (with integrator fixes) | Commit `5c6feea` (author) |
|---|---|---|
| E2E backend (HTTP flows + failure injection) | [job 124946258567](https://github.com/AndroidDev77/Wingman/actions/runs/41339483882/job/124946258567): 17 passed in 186 s, zero-duplicate summary as above | [job 124930492384](https://github.com/AndroidDev77/Wingman/actions/runs/41334216255/job/124930492384): 17 passed in 189 s |
| E2E Android emulator (app drives backend + emulator SMS) | [job 124946258804](https://github.com/AndroidDev77/Wingman/actions/runs/41339483882/job/124946258804): phase A PASS, phase B (emulator SMS stack exercised) PASS | [job 124930492429](https://github.com/AndroidDev77/Wingman/actions/runs/41334216255/job/124930492429): both PASS |
| Backend (ruff, mypy, pytest) | [job 124946258153](https://github.com/AndroidDev77/Wingman/actions/runs/41339483728/job/124946258153): 954 passed | success (953 passed) |
| Migrations, Contracts, Backend image, Android (`ci.yml`) | success (all four) | success (all four) |

Failed runs on this PR, all explained above: emulator runs 1 to 3 (author, test harness) and 5
(`6f4f9cf`, system ANR dialog); E2E backend on `6f4f9cf`
([job 124942090473](https://github.com/AndroidDev77/Wingman/actions/runs/41338088423/job/124942090473),
X2, the commit-after-response race, product bug 2).

Artifacts uploaded by each E2E job (14 days): `e2e-backend` (`server.log`, `junit.xml`,
`zero_duplicate_sends.json`) and `e2e-emulator` (instrumentation output, logcat, observations,
server log).

Local runs in the sandbox (author's runs on `2f02717` unless marked integrator):

| Command | Result |
|---|---|
| `scripts/e2e/run.sh` | 17 passed in 192 s, `E2E SUITE PASSED`, zero-duplicate summary as above. Integrator: 17 passed in 191 s on `2f02717` and 195 s with the session fix, same counts |
| `scripts/backend-check.sh` | ruff, format, mypy clean; 953 passed (952 before + 1 regression test). Integrator, with the session fix: 954 passed |
| `contracts/validate.sh` | `CONTRACT VALIDATION PASSED` (63 valid + 34 invalid examples) |
| `scripts/migration-check.sh` | `MIGRATION CHECK PASSED (18 tables at head)` |
| `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest -Pwingman.useMockApi=false` | BUILD SUCCESSFUL, 398 JVM tests, 0 failures; the merged release manifest has no network-security config |
| Emulator tests | Not run locally: no `/dev/kvm` in the sandbox. CI only |
