# macOS iMessage companion: feasibility (WING-020)

**Overall status: UNVERIFIED.** The authoring agent had no Mac (Linux sandbox only). Nothing in
this document was observed on macOS. No message was read from a real `chat.db` and **no
iMessage was sent**. The pure-logic prototype in [`macos/imessage_spike/`](../../macos/imessage_spike/)
runs on Linux against a synthetic `chat.db`-shaped fixture that was built from the
community-documented schema. Every Mac-specific claim below is **UNVERIFIED** or
**UNSUPPORTED**, with a reason. The [manual real-Mac test](#7-manual-real-mac-test) lists the steps
that would move a claim to VERIFIED.

**There is no iMessage API.** Apple publishes no API for reading or sending iMessages, on the Mac,
on iPhone, or in iCloud. The design below automates the user's **own** Messages app on the
user's **own** Mac, with permissions the user grants in System Settings. It works only while that
Mac is on, logged in and signed in to Messages. It is not, and cannot become, an iPhone-wide or
cloud iMessage integration.

| Item | Status | Evidence |
|---|---|---|
| Incremental read by ROWID cursor, batches, restart from the persisted cursor | VERIFIED (fixture) | `tests/test_connector.py` |
| No history upload: the first poll only sets the baseline | VERIFIED (fixture) | `test_first_poll_baselines_and_emits_no_history` |
| Dedup across restarts, a cursor that moves back, and the same GUID under a new ROWID | VERIFIED (fixture) | `test_dedup_*`, `test_crash_before_save_replays_identical_envelopes` |
| `text` NULL, body recovered from `attributedBody` (synthetic typedstream) | VERIFIED (fixture) | `test_decoding.py`, `test_null_text_*` |
| Decoding **real** `attributedBody` blobs | **UNVERIFIED** | the fixture blobs follow the published layout, not Apple's encoder; manual M3 |
| Group detection (`;+;` GUID, participant count, `chatNNN` identifier) | VERIFIED (fixture) | `test_group_detection`, `test_is_group_chat_signals` |
| Read-only open, WAL rows visible before a checkpoint | VERIFIED (Linux SQLite) | `test_reader_never_writes`, `test_sees_rows_still_in_wal` |
| The same on a live `chat.db` that Messages holds open | **UNVERIFIED** | manual M2 to M4 |
| AppleScript send command: constant script, data only as argv, injection attempts | VERIFIED (string/argv only, never executed) | `tests/test_applescript.py` |
| Messages accepting that command on any macOS version | **UNVERIFIED** | manual M8 to M12 |
| Send ledger: one attempt per key, claimed before the transport, lease deadline, ack only after the sent row appears | VERIFIED (fixture + fake executor) | `tests/test_sendplan.py` |
| Envelopes and the capability document validate against `connector_event.v1` / `connector_capabilities.v1` | VERIFIED | `tests/test_contract.py` |
| Pairing `platform: imessage` with the backend | Contract v1.3.0 accepts it (`transport: companion`, ADR 0008); a real pairing is UNVERIFIED | `test_imessage_is_pairable_with_companion_transport` |

## 1. Architecture

```
 ┌──────────────────────────── the user's Mac (logged-in GUI session) ────────────────────────────┐
 │                                                                                                 │
 │  Messages.app ──writes──► ~/Library/Messages/chat.db (+ -wal, -shm)                             │
 │       ▲                              │ read-only SQLite, short read transactions                 │
 │       │ Apple event "send"           ▼                                                           │
 │       │ (Automation consent)   Wingman Companion.app  (Swift, menu-bar app, Developer ID)        │
 │       │                         ├─ ChatDbReader      ROWID cursor, attributedBody fallback       │
 │       └──────────────────────── ├─ Sender           one attempt per key, ack from chat.db row   │
 │                                 ├─ State (0600 JSON) cursor, dedup, envelope outbox, ledger      │
 │                                 ├─ Keychain         refresh token (ThisDeviceOnly)               │
 │                                 └─ Sync loop        upload events, claim leased instructions     │
 └─────────────────────────────────────────────┬───────────────────────────────────────────────────┘
                                               │ outbound HTTPS only (no listening port)
                                               ▼
     POST /v1/connections            (createConnection, platform `imessage` from contract v1.3)
     POST /v1/connections/{id}/events         (ingestConnectorEvent, contract v1.1, ADR 0006)
     POST /v1/connections/{id}/outbox/claim   (claimOutboxInstructions, contract v1.1, ADR 0006)
     POST /v1/connections/{id}/unpair         (unpairConnection, contract v1.2, ADR 0007)
```

It is the same device-side pattern as the Android SMS connector
([`android_sms.md`](android_sms.md) §8 and §9):

* **Pair** once per install, with a random `device_install_id` and the claimed capability document.
  The server stores every client `VERIFIED` as `UNVERIFIED` (ADR 0007).
* **Ingest.** New inbound rows become `message.received` envelopes. They go into a durable outbox
  and are uploaded in order, with `Idempotency-Key = event_id`. The response handling is
  WING-016's: `202`/`200` remove the envelope, `400`/`404`/`422` dead-letter it, and anything else
  stops the drain and retries later.
* **Claim.** The companion polls `claimOutboxInstructions` and runs each leased instruction at
  most once. It reports `send_acknowledged`, `send_failed` or `delivery_receipt` through the same
  outbox. **The backend dispatcher (WING-011) is the only thing that decides to send.** The
  companion never sends on its own. It exposes no local send endpoint and listens on no port.

### Inbound: reading `chat.db`

Apple does not document the schema. The prototype relies only on what independent references
agree on ([Jachimowicz][jachimo], [imessage-exporter][rx]):

| Table / column | Use |
|---|---|
| `message.ROWID` | the incremental cursor (`ROWID > cursor ORDER BY ROWID`) |
| `message.guid` | the stable identity: `external_message_id = imessage:<guid>` (never the ROWID, which a rebuilt database changes) |
| `message.text`, `message.attributedBody` | the body. `text` is often NULL since macOS 13, and then the body is the first `NSString` in the typedstream archive |
| `message.date` | nanoseconds since 2001-01-01 UTC (seconds on old databases). `wingman_imessage/timeconv.py` |
| `message.is_from_me`, `handle_id` -> `handle.id` | direction and sender (phone or e-mail) |
| `message.service` | `iMessage` / `SMS` / `RCS`: the transport, when it is filled in |
| `message.associated_message_type/guid` | tapbacks are separate rows (2000 to 3006). They are skipped |
| `message.item_type` | non-zero means a system item (rename, member change). It is skipped |
| `message.date_edited`, `date_retracted` | edits and unsends (macOS 13+). See the matrix |
| `message.error`, `is_sent`, `is_delivered` | the outcome of our own sends |
| `chat_message_join` -> `chat.guid`, `chat_identifier`, `display_name` | thread. `<service>;-;<address>` is 1:1 and `<service>;+;<id>` is a group |
| `chat_handle_join` | group membership (participant count) |
| `message_attachment_join` | attachment count only |

Reader rules (`wingman_imessage/chatdb.py`):

* **Read-only.** The reader opens `file:...chat.db?mode=ro`. It never uses `immutable=1`, which
  would ignore the `-wal` file and miss recent messages. It never checkpoints and never writes.
  SQLite can read a WAL database read-only when the `-wal` and `-shm` files exist ([SQLite WAL
  §5][wal]). That is the normal state while Messages runs (UNVERIFIED on a Mac).
* **Short read transactions.** Each poll is one autocommit `SELECT`. A long-lived read snapshot
  would block Messages' checkpoints ([SQLite WAL §6][wal]).
* **Schema drift.** Optional columns are discovered with `PRAGMA table_info` and read as NULL when
  they are missing (`test_missing_optional_columns_read_as_null`). The `message` table has
  around 94 columns on macOS 26 ([Jachimowicz][jachimo]), and it changes every year.
* **No history.** The first poll on a new install stores `MAX(ROWID)` and the time, and emits
  nothing. Rows dated before that baseline are never emitted, even if they arrive late (for
  example, from a Messages in iCloud sync).
* **Hold-back.** A row seen less than 30 s ago that has no `chat_message_join` yet stops the
  round, and the next poll retries from it. The write order inside Messages is UNVERIFIED. An
  older orphan row is skipped.
* **Change detection.** The spike polls once a second in the manual probe. WING-030 should use a
  file-system event (`DispatchSource` / FSEvents) on `chat.db-wal` as a *wake-up hint*, and still
  poll every 30 to 60 s as the source of truth (UNVERIFIED that WAL writes raise events reliably).

### Outbound: Messages AppleScript

The Messages scripting dictionary is available in Script Editor (File > Open Dictionary >
Messages). Apple does not publish it as a web page. The prototype supports exactly one form:
sending to an **existing** chat by its GUID.

```applescript
on run argv
  set chatGuid to item 1 of argv
  set messageText to item 2 of argv
  tell application "Messages"
    set targetChat to chat id chatGuid
    send messageText to targetChat
  end tell
end run
```

* **Injection safety.** The script is a constant. The chat GUID and the text reach it only as
  `argv`, through `execve` (`/usr/bin/osascript -e ... <guid> <text>`), with no shell. Neither
  the shell nor AppleScript ever parses user text. In Swift, compile the same constant source once
  (`NSAppleScript`/`OSAScript`) and call its `run` handler with a list descriptor. The GUID must
  match `^(iMessage|SMS|RCS|any);[-+];[A-Za-z0-9@._+%:-]{1,200}$`. The text must be 1 to 4000
  characters with no NUL, no C0 control characters except `\n\r\t`, and no lone surrogates.
  `applescript_string_literal` (escape `\` and `"`, then `\n`/`\r`/`\t`) exists only for the
  manual paste-into-Script-Editor path. `tests/test_applescript.py` covers quote breakouts,
  `do shell script` payloads, newline and `end tell` injection, chevrons and smart quotes, and
  argv option smuggling (`-e`).
* **Version drift (UNVERIFIED).** Third-party code reports that macOS 26 uses an `any;` service
  prefix in chat GUIDs, and that account selection changed in macOS 15 ([imessage-apple
  scripts.rs][imapple]). The GUID pattern accepts `any`. The companion must always use the GUID
  exactly as `chat.db` stores it, and never build one from a phone number.
* **What success means.** `osascript` exiting 0 means only that Messages accepted the Apple event.
  It does not mean the message was sent. The sender keeps three states apart, as WING-030 requires:
  1. *submitted*: the key is claimed in the durable ledger before the transport is called;
  2. *script success*: the exit code was 0. This is recorded, but no event is emitted;
  3. *acknowledged*: a new row with `is_from_me = 1` appears in the same chat, above the pre-send
     ROWID mark, with the same text and `error = 0`. Only then is `message.send_acknowledged`
     emitted, with that row's GUID. `is_delivered = 1` later yields `message.delivery_receipt`.

  A row with `error != 0` becomes `send_failed`/`rejected_by_platform`. If no row appears within
  60 s, the result is `send_failed`/`timeout` with `retryable: false`, because the message may
  still go out. The same applies after a crash between the claim and the result.
* **Apple-event errors** (`sendplan.py`; the mapping is UNVERIFIED): `-1743` (Automation not
  permitted) maps to `permission_denied`. `-1728` (no such chat) maps to `rejected_by_platform`.
  `-600`/`-609` (Messages not running or the connection is invalid) map to `not_connected` and
  are retryable. `-1712` (Apple-event timeout) maps to `timeout`. Anything else maps to
  `transport_error`.

## 2. Permission model (TCC)

| Permission | Why | How it is granted | Status |
|---|---|---|---|
| **Full Disk Access** | `~/Library/Messages` is protected. Without it, opening `chat.db` fails | The user adds the app in System Settings > Privacy & Security > Full Disk Access. There is **no prompt** an app can trigger. The companion can only explain the setting and open it ([Apple Platform Security][aps]) | UNVERIFIED |
| **Automation (Apple events) -> Messages** | to `send` | A system prompt appears on the first Apple event. It shows the `NSAppleEventsUsageDescription` string ([Apple][nsae]). A hardened-runtime app needs the `com.apple.security.automation.apple-events` entitlement ([Apple][aeent]). If the user denies it, the error is `-1743`. It can be changed later in System Settings > Privacy & Security > Automation | UNVERIFIED |
| Contacts | display names | **Not requested.** `display_name` is null. Names come from the backend's contact records | n/a |
| Accessibility | GUI scripting | **Not requested.** `draft` is UNSUPPORTED, and GUI scripting is too fragile | n/a |

* **TCC identity.** Grants belong to the code-signing identity of the process that makes the
  access. The companion should be **one** signed app process, a menu-bar app started at login with
  `SMAppService.mainApp` (macOS 13+) ([Apple][smapp]). A separate helper or LaunchAgent binary
  would need its own Full Disk Access grant, and a script run through `osascript` would make the
  Automation grant attach to the wrong binary. That is why WING-030 should run the constant script
  in-process (`NSAppleScript`) and not spawn `osascript`. The spike uses `osascript` only because it
  cannot run in-process on Linux.
* **Revocation.** Either grant can be revoked at any time. The companion re-checks on every poll.
  If `chat.db` stops opening, or a send returns `-1743`, it publishes
  `connection.capabilities_changed` with that capability set to `UNSUPPORTED`, shows the user how
  to fix it, and keeps running.
* **Full Disk Access is changing.** On 2026-10-02 Apple announced that it "will introduce
  additional controls" so that Full Disk Access can be granted only "with very explicit user
  action", and it named communication apps and AI agents as the concern ([Apple Developer
  news][fdanews]). Neither the mechanism nor a date has been published. **This is the largest
  risk to the read path.** WING-030 must re-check it against the current macOS release before it
  builds anything.
* **App Sandbox / Mac App Store: not viable.** A sandboxed app cannot read `~/Library/Messages`,
  and sending Apple events needs scripting-targets or temporary-exception entitlements, which are
  "intended to be temporary" ([Apple QA1888][qa1888]). Ship outside the App Store: Developer ID
  signing, hardened runtime, and notarization, which Gatekeeper requires for Developer ID software
  ([Apple][notary]).

## 3. macOS versions (all UNVERIFIED)

Not tested on any version. The column notes come from third-party references, not from runs.

| macOS | Read (`chat.db`) | Send (AppleScript) | Notes |
|---|---|---|---|
| 12 Monterey and older | UNSUPPORTED (out of scope) | UNSUPPORTED (out of scope) | `SMAppService` needs macOS 13. Not worth supporting |
| 13 Ventura | UNVERIFIED | UNVERIFIED | `text` often NULL, so `attributedBody` is needed. `date_edited`/`date_retracted` appear ([Jachimowicz][jachimo]) |
| 14 Sonoma | UNVERIFIED | UNVERIFIED | |
| 15 Sequoia | UNVERIFIED | UNVERIFIED | RCS and emoji tapbacks (`associated_message_emoji`) ([Jachimowicz][jachimo]). Script account selection changed ([imessage-apple][imapple]) |
| 26 Tahoe | UNVERIFIED | UNVERIFIED | about 94 `message` columns. Chat GUIDs reportedly use an `any;` prefix ([imessage-apple][imapple]) |
| later | UNVERIFIED | UNVERIFIED | The Full Disk Access changes announced 2026-10-02 may land here ([Apple][fdanews]). Re-run the probe on every major release |

Proposed WING-030 minimum: **macOS 14**, with 13 best-effort, and only the versions recorded
with the [manual test](#7-manual-real-mac-test) listed as supported.

## 4. Capability and reliability matrix

Capabilities published at pairing (`wingman_imessage/capabilities.py`): `read` and `send` are
**UNVERIFIED**. `draft`, `swipe` and `calendar_write` are **UNSUPPORTED**. `reports_delivery_receipts`
is `false` until a run shows that `is_delivered` is reliable. Below, "fixture" means the logic is
tested against the synthetic database, which says nothing about a Mac.

| Behaviour | Status | Reason |
|---|---|---|
| Event capture: new inbound 1:1 iMessage | UNVERIFIED | The logic is fixture-tested. It depends on Full Disk Access and on how fast Messages commits rows (manual M4) |
| Event capture: inbound group message | UNVERIFIED | Fixture-tested (`;+;`, participant count). The envelope thread is the group, and the sender is the member. `connector_event.v1` has no group flag |
| Read history | UNSUPPORTED by design | The first poll is only a baseline. History is never uploaded. This is a privacy choice, not a limit |
| `text` NULL with `attributedBody` | UNVERIFIED | Fixture-tested on synthetic blobs only. A real blob may differ, and a body that does not decode is skipped as `no_content` (a missed message, never a wrong one) |
| Send: 1:1 iMessage into an existing chat | UNVERIFIED | Not executed. It needs Automation consent, a running and signed-in Messages, and an awake, logged-in Mac |
| Send: SMS/MMS through Text Message Forwarding | UNVERIFIED | Same script, `SMS;-;` GUID. It needs the user's iPhone nearby with forwarding on. Delivery has more failure modes |
| Send: RCS | UNVERIFIED | `RCS;` GUIDs are accepted, but nothing about them is documented |
| Send: group chat | UNVERIFIED | `chat id` with a `;+;` GUID. The automation controller should keep groups approval-only (D-016 spirit) |
| Send: start a new conversation | UNSUPPORTED | Only existing chats from `chat.db` can be targeted. GUIDs are never built from addresses |
| Attachments inbound | UNVERIFIED | Only `attachment_count` (the contract field). Files are not read |
| Attachments outbound | UNSUPPORTED | The contract instruction has text only |
| Reactions (tapbacks) inbound | UNSUPPORTED | Rows are recognised and skipped. `connector_event.v1` has no reaction event |
| Reactions outbound | UNSUPPORTED | No AppleScript support is known. Private APIs are excluded (BlueBubbles' helper bundle injects into Messages for this ([BlueBubbles][bb])) |
| Edits and unsends | UNSUPPORTED | They UPDATE existing rows, so the ROWID cursor never sees them. The contract has no edit event. An unsent message that was already emitted stays in Wingman |
| Typing indicators, read receipts | UNSUPPORTED | No AppleScript support, and no contract field |
| Transport (iMessage vs SMS vs RCS) | UNVERIFIED | From `message.service` or the GUID prefix, carried in `external_thread_id`. An `any;` prefix (macOS 26) hides it, and the payload has no field for it |
| Send acknowledgement | UNVERIFIED | Only from the sent row in `chat.db`, never from the script's exit code (fixture-tested) |
| Delivery receipt | UNVERIFIED | `is_delivered` on our row. It probably applies to iMessage only. Published as `reports_delivery_receipts: false` |
| No double send (restart, duplicate claim, replayed claim) | VERIFIED (fixture) / UNVERIFIED (Mac) | The ledger is claimed before the transport. `test_one_transport_attempt_per_key_across_restart` |
| Mac asleep, logged out, or Messages signed out | UNSUPPORTED | Nothing runs. Leases expire, and the backend records `expired` without retrying (ADR 0006). The UI must say the Mac has to be on |
| Multiple Apple accounts in Messages | UNVERIFIED | A reply goes into the chat it came from, which carries its own account. Not tested |
| `draft` (fill the compose field) | UNSUPPORTED | Not in the scripting dictionary. GUI scripting would need Accessibility |

**Reliability risks** (all UNVERIFIED): AppleScript `send` has a history of silent failures
around macOS updates, so the ack-from-row rule exists for that reason. A `chat id` may not resolve
for chats that iCloud sync has not fully loaded. Messages may show an error badge without setting
`message.error`. The Full Disk Access tightening may remove the read path. Apple can change the
schema or the dictionary in any release without notice.

## 5. Pairing with the backend

1. **Sign-in.** The companion signs the user in with the same Auth0 tenant as Android
   ([`docs/auth0.md`](../auth0.md)). It uses a Native/public client with authorization code + PKCE
   in the system browser (`ASWebAuthenticationSession`) and the custom-scheme redirect. **Contract
   follow-up:** a separate Auth0 application, or at least a separate redirect URI, for the Mac
   client.
2. **Install ID.** On first launch the companion generates a random UUID `device_install_id`. It
   keeps it in the Keychain (it is not secret, but this way it survives a deleted state file) and
   in the state file. A reinstall that also deletes the Keychain item is a new lease holder, as in
   ADR 0006.
3. **Pair.** `createConnection` with `platform: "imessage"`, `display_name` (for example "Chris's
   MacBook Pro iMessage"), `device_install_id` and the capability document. The `Idempotency-Key`
   and the body are persisted before the request and replayed after a lost response, as in
   `SmsPairingManager`. Contract **v1.3.0** (ADR 0008) pairs `imessage` with
   `transport: companion`, `device_install_id` and the capability document
   (`test_imessage_is_pairable_with_companion_transport`).
4. **Sync.** Upload the outbox, then claim (`max_instructions` 1 to 5). Run each instruction and
   upload the results. There is **no inbound port.** Polling every 30 to 60 s is enough for
   replies a human approves. An APNs wake-up hint can come later, as FCM can for Android
   (ADR 0006 "Why pull").
5. **Startup check and unpair.** The same table as [`android_sms.md` §9](android_sms.md#9-pairing-i-020-contract-120-adr-0007).
   Unpair (user action, or the server says `404`/`unpaired_at`): stop claiming, clear the stored
   connection ID, the refresh token and the dedup set, and keep queued result envelopes so late
   send outcomes still upload. **Disconnect** in the menu also offers "delete local Wingman data",
   which removes the state file.

## 6. Credential and data protection

* **Tokens.** The refresh token goes in the login Keychain as a generic password, with
  `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and `kSecAttrSynchronizable = false` (it
  never syncs to iCloud Keychain). The access token stays in memory only. Tokens never go in the
  state file, logs, crash reports, `UserDefaults` or command lines.
* **Locked Keychain.** If the Keychain is locked, sync pauses and the status shows "Waiting for
  unlock". It never falls back to a file.
* **Least privilege (follow-up).** A user access token can call every `/v1` route. A
  connection-scoped device credential (ingest, claim and unpair for one connection) would limit
  the damage if the Mac is compromised. That is a backend and contract proposal for the
  integrator, not part of this spike.
* **Local state.** `~/Library/Application Support/<bundle id>/companion-state.json`, mode `0600`,
  replaced atomically (`state.py`). It contains message text while envelopes wait for upload, as
  WING-016's state file does. It is pruned after upload (dedup keys are kept for 14 days).
* **Logs.** IDs, counts and error codes only. `SendCommand.describe()` never includes the text or
  the address (`test_describe_is_redacted`). The manual probe prints hashes only.
* **Process.** The companion never writes to `~/Library/Messages`, never loads code into
  Messages, uses no private framework, and never asks for Accessibility. The only process it talks
  to is Messages, through Apple events.

## 7. Manual real-Mac test

`macos/imessage_spike/manual/mac_probe.py` uses only the system Python and this repo. Record each
run with [`manual/RUN_TEMPLATE.md`](../../macos/imessage_spike/manual/RUN_TEMPLATE.md) as a new
section `## Mac run YYYY-MM-DD` at the end of this file. Use the tester's own Apple account and a
peer device the tester owns. **Never paste message text, numbers or full GUIDs.**

```bash
cd <repo> && P="python3 macos/imessage_spike/manual/mac_probe.py"
$P env              # macOS version, files present, FDA yes/no, schema columns
$P read --limit 200 # counts only: text_source, transport, group, style, reactions, edits
$P watch --seconds 120
$P send --chat-guid '<GUID from Script Editor: tell application "Messages" to get id of chats>' --text 'WING020 probe 1'
```

| # | Step | Expected | Moves |
|---|---|---|---|
| M1 | Run `env` from Terminal **without** Full Disk Access | `full_disk_access: NOT readable` | read: the permission-failure path |
| M2 | Grant Full Disk Access to Terminal, restart Terminal, run `env` | readable; `chat.db-wal` and `-shm` present; the columns used by `chatdb.py` exist | read: open |
| M3 | `read --limit 200` | `text_source=none` is 0 (or the rows are reactions or system items). Record how often `attributedBody` was needed. Group rows: `style=?|group=True`, and record the real style value | read: decoding, groups |
| M4 | `watch`; the peer sends a 1:1 iMessage | one row, `from_me=false`, `transport=imessage`, lag under 5 s | **read VERIFIED** with M2 + M3 |
| M5 | `watch`; the peer sends in a group | `group=true` | groups (inbound) |
| M6 | `watch`; the peer adds a tapback, then edits, then unsends | the tapback is a new row with `reaction=true`. The edit and the unsend produce **no** new row (this confirms the matrix) | reactions, edits |
| M7 | `watch`; an SMS arrives through Text Message Forwarding | `transport=sms` | transport |
| M8 | Reset consent (`tccutil reset AppleEvents`), run `send`, click **Don't Allow** | `osascript_exit != 0`, stderr shows `-1743`, `sent_row_found: false`, and the peer receives nothing | send: denied path |
| M9 | Run `send` again; allow it in System Settings > Automation if needed | exit 0, `sent_row_found: true` with `error 0`, and the peer receives the text **once**. Note whether `is_delivered` turns true | **send VERIFIED** with M8 + M12 + M14 |
| M10 | `send` into a group chat GUID | every member receives it once | groups (outbound) |
| M11 | `send` into an `SMS;-;` chat | the peer phone receives an SMS once, or record the failure | SMS through forwarding |
| M12 | `send` with Messages quit; with the screen locked; after sleep and wake | record each one: does Messages launch, does the send happen, what error | reliability |
| M13 | Remove Full Disk Access and Automation, rerun `env` and `send` | readable: no; `-1743` | revocation |
| M14 | Start `watch`, stop it with Ctrl-C, have the peer send, start `watch` again | the new row is shown once (the cursor resumes from the new `max_rowid`; the restart behaviour of the full companion is fixture-tested) | restart sanity |

A capability may be published as VERIFIED only by the operator CLI (ADR 0007), with the recorded
section as `evidence`, for that macOS version and that connector version.

## 8. Go / no-go for WING-030

**Conditional GO** for a user-run, local-only companion. It is narrow: inbound text capture, plus
sending into existing chats only after explicit approval.

Conditions. WING-030 stops and reports **NO-GO** if any of these fails:

1. **A real Mac is available** and M1 to M4 pass on the current macOS release. If `chat.db`
   cannot be read, or the 2026-10-02 Full Disk Access controls make it impractical for users,
   then **read is NO-GO**. There is no supported alternative (private frameworks are excluded).
2. **Send stays UNVERIFIED until M8, M9, M12 and M14 pass.** If they don't, WING-030 ships
   **draft-only mode**: the reply is shown in the companion with a "Copy" button, the user sends
   it by hand, and no `send` instruction is ever executed. It never pretends that sends work.
3. Contract **v1.3** has merged with `imessage` pairable.
4. Autopilot stays off for `imessage` until `send` is VERIFIED through the operator CLI. Even
   then, group chats stay approval-only.

**Recommended stack.**

| Area | Choice | Why |
|---|---|---|
| Language/UI | **Swift 6, SwiftUI menu-bar app** (`MenuBarExtra`), macOS 14+ | First-party access to `NSAppleScript`/`OSAKit`, the Keychain (Security), `SMAppService` and `ASWebAuthenticationSession`. One signed binary keeps a single TCC identity |
| SQLite | the system `libsqlite3` through the C API (or GRDB.swift, MIT) | No runtime to bundle. `SQLITE_OPEN_READONLY` + URI `mode=ro` |
| `attributedBody` | a small typedstream reader ported from `typedstream.py`, then extended to walk the object graph | The spike only finds the first `NSString`. Do **not** copy GPL-3.0 code (imessage-exporter) into the app |
| Send | the constant script, compiled once, run in-process with an argv list descriptor | No `osascript` child process. Automation consent attaches to the app |
| Start at login | `SMAppService.mainApp` | macOS 13+ API. No hand-written LaunchAgent plist |
| Networking | `URLSession` against the existing `/v1` contract | No new backend technology |
| Packaging | Developer ID-signed `.app` in a notarized DMG, hardened runtime, `com.apple.security.automation.apple-events` entitlement, `NSAppleEventsUsageDescription`, **not sandboxed**, **not on the Mac App Store** | The sandbox blocks both paths (QA1888). Notarization is needed for Gatekeeper |
| Tests | Swift Testing / XCTest on a `macos-14`+ runner for the pure logic, ported from `macos/imessage_spike/tests` with the same fixture schema. Mac-only steps stay manual | Keeps WING-020's fixture tests as the spec |

**Why Python in this PR.** The sandbox had no Mac and no Swift toolchain. The logic is written as
a Python package with no dependencies so it can run in Linux CI and serve as WING-030's
executable spec. It is not meant to ship. Running a Python app on users' Macs would need a
bundled interpreter, and its TCC identity would be the interpreter.

**What this spike does not claim.** It does not claim any standalone iPhone or iMessage API, any
real read or send, delivery on any macOS version, or that the Apple-event error mapping is
correct.

## 9. Contract and integration notes (for the integrator)

* **v1.3 (in flight):** `ConnectionCreateRequest.platform` adds `imessage`, with
  `device_install_id` and `capabilities` required, as for `sms`.
* **Proposal (not made here):** optional inbound payload fields `transport`
  (`imessage|sms|rcs|unknown`) and `is_group`. `inbound_message_payload` has
  `additionalProperties: false`, so the spike can only carry these in the thread ID today.
* **Proposal:** a connection-scoped device credential (§6).
* **Thread IDs:** `imessage:chat:<chat.guid>` (at most about 215 characters, under the 256
  limit). The dispatcher must send into the thread a message came from. A reply to a
  Wingman contact who has no iMessage thread is not possible (UNSUPPORTED).
* **CI:** `.github/workflows/macos-imessage-spike.yml` runs the Linux fixture suite on changes
  under `macos/` or `contracts/` (Ubuntu, so no Mac is needed).

## Sources

Apple (primary):

* [Apple Developer news, "Updates to Full Disk Access in macOS" (2026-10-02)][fdanews]
* [Apple Platform Security: protecting app access to user data][aps]
* [Apple Events entitlement `com.apple.security.automation.apple-events`][aeent]
* [`NSAppleEventsUsageDescription`][nsae]
* [Technical Q&A QA1888: Sandboxing and Automation in OS X][qa1888]
* [Notarizing macOS software before distribution][notary]
* [`SMAppService`][smapp] and the legacy [Creating Launch Daemons and Agents][launchd]
* Messages scripting dictionary: Script Editor > File > Open Dictionary > Messages (not published on the web)

SQLite:

* [Write-Ahead Logging][wal] (read-only WAL access §5, checkpoint starvation §6)

Schema references and prior art (consulted for design only, no code copied):

* [Apple iMessage Databases on macOS and iOS (Jachimowicz)][jachimo]: tables, epochs, `attributedBody`, tapback codes, version notes
* [ReagentX/imessage-exporter][rx] (GPL-3.0): typedstream decoding, read-only export
* [jonmmease/jons-mcp-imessage, IMESSAGE_DATABASE_FORMAT.md][jons]: typedstream length prefix (`0x81` + 2-byte little-endian)
* [imessage-apple `scripts.rs`][imapple]: AppleScript `chat id` send, `any;` prefix on macOS 26, account selection on Sequoia
* [kb-macfor-io: group chats][kbgroups]: says `chat.style = 43` is a group. [Jachimowicz][jachimo] says 45. This is why the reader does not rely on `style`
* [BlueBubbles Private API helper bundle][bb]: shows that reactions, edits and typing need code injected into Messages (excluded here)

[fdanews]: https://developer.apple.com/news/?id=p6zjojqw
[aps]: https://support.apple.com/guide/security/secddd1d86a6
[aeent]: https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.automation.apple-events
[nsae]: https://developer.apple.com/documentation/bundleresources/information-property-list/nsappleeventsusagedescription
[qa1888]: https://developer.apple.com/library/content/qa/qa1888/_index.html
[notary]: https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution
[smapp]: https://developer.apple.com/documentation/servicemanagement/smappservice
[launchd]: https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatingLaunchdJobs.html
[wal]: https://www.sqlite.org/wal.html
[jachimo]: https://jachimo.github.io/research/imessage-chatdb-reference
[rx]: https://github.com/ReagentX/imessage-exporter
[jons]: https://glama.ai/mcp/servers/@jonmmease/jons-mcp-imessage/blob/3af876d28c0efe9f24b18db9936155308736c4b9/docs/IMESSAGE_DATABASE_FORMAT.md
[imapple]: https://docs.rs/crate/imessage-apple/latest/source/src/scripts.rs
[kbgroups]: https://kb-macfor-io.pages.dev/docs/communication/messages/group-chats
[bb]: https://docs.bluebubbles.app/helper-bundle
