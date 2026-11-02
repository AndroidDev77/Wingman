# Wingman Companion for macOS (iMessage): WING-030

**Overall status: UNVERIFIED on a real Mac.** No message has been read from a real
`chat.db` and **no iMessage has been sent** by this code. What *is* verified:

| What | Status | Evidence |
|---|---|---|
| `WingmanCompanionCore` logic (reader, cursor, dedup, `attributedBody`, mapper, executor, client, engine) | VERIFIED (fixtures) | 73 XCTest cases, Swift 6.0.3 on Linux and Swift 6.1.2 / Xcode 16.4 on macOS 15.8.2 (CI `macos-companion.yml`) |
| The whole package, including the SwiftUI app and the macOS adapters, compiles on macOS | VERIFIED (compile only) | CI job `macos-app` |
| An ad-hoc-signed `.app` with hardened runtime, `NSAppleEventsUsageDescription`, the apple-events entitlement and no sandbox | VERIFIED (structure only) | `Packaging/build-app.sh --adhoc` in CI (`codesign --verify --strict`) |
| The backend accepts the companion's exact pairing body and envelopes (inbox, claim binding, takeover, pause, unpair, dedup, assisted delivery) | VERIFIED (fixtures, real API) | `backend/tests/connectors/imessage/` (14 tests) on the Swift golden files |
| Reading `chat.db`, Full Disk Access, Apple-event sends, Automation consent, Keychain, Auth0 sign-in, `SMAppService` on a real Mac | **UNVERIFIED** | §6 is the manual test that would change this |
| Developer ID signing and notarization | **UNVERIFIED** | needs the owner's Apple Developer account (§5) |

There is no iMessage API. The companion automates the user's **own** Messages app on the
user's **own** Mac, with permissions the user grants. It works only while that Mac is on,
logged in and signed in to Messages. It follows the WING-020 design
([`imessage.md`](imessage.md)) and decision D-024 (conditional GO). **Autopilot stays off for
iMessage** until `send` is VERIFIED through the operator CLI with a recorded §6 run; groups are
never replied to (§4).

## 1. What was built

`macos/wingman-companion/` is a SwiftPM package (Swift 6, macOS 14+).

| Target | Platform | What |
|---|---|---|
| `CSQLite` | both | the **system** SQLite (macOS SDK / `libsqlite3-dev`), no bundled copy |
| `WingmanCompanionCore` | macOS + Linux | all decisions: `ChatDbReader` (read-only `mode=ro` URI, never `immutable=1`, WAL-aware, short autocommit reads, schema-drift tolerant), `TypedStream` (`attributedBody`), `InboundPoller` (ROWID cursor, GUID dedup, no-history baseline, 30 s hold-back, **bounded queue** of 1000 with back-pressure), `Envelope` (`connector_event.v1`, `source: companion`), `OutboxExecutor` (lease deadline, one attempt per key persisted first, ack only from the chat.db row), `AppleScriptCommand` (constant script, argv only), `WingmanAPI` + `Backoff`, `Auth` (PKCE, token refresh), `StateStore` (0600, atomic), `CompanionEngine` (the cycle, switches, pairing, disconnect), `LogEvent` (no message data) |
| `WingmanCompanionApp` | macOS only | `MenuBarExtra` UI, `AppleScriptTransport` (`NSAppleScript`, in-process), `KeychainSecretStore`, `URLSessionTransport` (outbound HTTPS only), `WebSignIn` (`ASWebAuthenticationSession`), `LoginItem` (`SMAppService.mainApp`), `MemoryLog` (`os.Logger`, public IDs and counts only) |

The Python spike in `macos/imessage_spike/` is unchanged and stays the reference. Event IDs
are byte-identical (`DigestTests.testEventIdsMatchPythonSpike`). Deliberate differences:

| Spike | Companion | Why |
|---|---|---|
| contact = the row's `handle.id` | contact = the address in the **1:1 chat GUID** (`iMessage;-;<address>`), `identity: linked` | identity is linked only by the chat GUID; an e-mail alias writing into a phone-keyed chat stays one person |
| group = ordinary `message.received` | group = `identity: ambiguous` (an inbound notice, never drafted or replied to), or not uploaded (`GroupPolicy.skip`) | contract v1.3.1 has no group flag, so the backend could not keep groups approval-only (D-024) |
| no 1.3.0 payload fields | `kind: chat_message`, `identity`, `source: companion` | contract v1.3 (ADR 0008) |
| an unsafe instruction is left to expire | **refused**: key used up, no transport call, `send_failed` / `capability_unsupported` / not retryable | an expiry is audited as "outcome unknown", which would be false; nothing was sent |
| `osascript` child process | `NSAppleScript` in-process, script adds `with timeout of 30 seconds` | one TCC identity; `-1712` maps to `timeout` (not retryable) |
| reader kept open | chat.db opened and closed **every cycle** | a revoked Full Disk Access grant shows at the next open |
| Keychain `AfterFirstUnlockThisDeviceOnly` | `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, data-protection keychain, not synchronizable | WING-030 ticket; locked keychain = "Waiting for unlock", never a file fallback |

**The cycle** (`CompanionEngine.runCycle`, every 30 s and on a `chat.db-wal` change, which is
only a wake-up hint): open chat.db → poll new rows → reconcile earlier sends → upload the queue
(`Idempotency-Key = event_id`) → **claim** (unless the kill switch, draft-only, unreadable
chat.db or denied Automation says not to) → execute each instruction → reconcile → upload →
close chat.db. **No listening port**: everything is an outbound HTTPS request.

**Switches.** *Companion on* (off: nothing is read, uploaded or claimed). *Stop all sends from
this Mac* (local kill switch: no claim from the next cycle, and an instruction already claimed is
refused, checked again before **each** send). *Draft-only* (claims `send: UNSUPPORTED`; the
backend makes assisted deliveries; nothing is claimed). Global pause and per-contact takeover
are enforced **server-side** at dispatch and at claim (D-014); the companion never caches an
authorization.

## 2. Install, permissions, recovery

Requirements: macOS 14 or later (13 and earlier UNSUPPORTED: `MenuBarExtra`, `SMAppService`),
Messages signed in, the Mac awake and logged in. For SMS through the Mac, Text Message
Forwarding on the user's iPhone.

1. Open the notarized `WingmanCompanion.dmg`, drag **Wingman Companion** to Applications, open it.
   It appears in the menu bar only (no Dock icon).
2. **Sign in with Wingman**: the system browser opens the Wingman Auth0 page (Google). After it
   redirects back, the app pairs as **"<Mac name> iMessage"** (`platform: imessage`,
   `transport: companion`) and records the current newest message as its baseline. **No history
   is uploaded.**
3. **Full Disk Access.** macOS has no prompt for it. *Open Settings* goes to System Settings >
   Privacy & Security > Full Disk Access; add **Wingman Companion**, then quit and reopen it.
4. **Automation → Messages.** macOS asks the first time an approved reply is sent, showing the
   `NSAppleEventsUsageDescription` text. Choose **Allow**.
5. Optional: *Start at login* (`SMAppService.mainApp`, one signed binary, one TCC identity).

**Recovery after a revoke.**

| Revoked | What the app does | User fix |
|---|---|---|
| Full Disk Access | chat.db stops opening at the next cycle: status *Needs Full Disk Access*, one `connection.capabilities_changed` with `read: UNSUPPORTED`, no claims (an ack needs chat.db) | Re-add the app in Full Disk Access, reopen it; the next good poll announces `read: UNVERIFIED` again and resumes. Rows that arrived meanwhile are read from the cursor (nothing older than the baseline) |
| Automation | the send returns `-1743` → `send_failed` / `permission_denied` / not retryable, `send: UNSUPPORTED` announced, claims stop | Re-allow in Privacy & Security > Automation, then *I allowed it in Settings: retry sends* (`resetAutomationCheck`) |
| Sign-in (refresh token revoked or expired) | `invalid_grant` deletes the stored token; status *Not signed in*; reading and queuing continue locally | Sign in again; the queue uploads |
| Pairing (unpaired in the Wingman app) | inbound upload gets `422` at `/connection_id` or the claim `422` at `/device_install_id` (D-017): the app forgets the connection, cursor and dedup set, keeps the send ledger, shows *Unpaired* | Sign in / pair again (a new baseline) |

**Disconnect** (menu): unpairs server-side first (`unpairConnection` cancels unclaimed
instructions, queued sends, approvals and assisted deliveries, ADR 0007), revokes the refresh
token at Auth0, deletes both Keychain items, and deletes the state file. If the server cannot be
reached, nothing is wiped (sends are stopped at once); the user may then *Delete local data
anyway* and must also remove the connection in the Wingman app.

## 3. Auth0 setup the owner must do (not done by this PR)

The Mac signs in with the same tenant and API as Android ([`../auth0.md`](../auth0.md)) but as
its **own Native application**, so it can be revoked separately and has its own redirect. The
backend checks only `iss` and `aud`, so **no backend change is needed**.

1. Auth0 dashboard > Applications > **Create Application** > *Native* > name
   **Wingman Companion (macOS)**.
2. Settings: **Allowed Callback URLs** and **Allowed Logout URLs** =
   `com.wingman.companion:/oauth2redirect` (exact). **Grant types**: Authorization Code and
   Refresh Token. **Refresh Token Rotation**: on (the app stores the rotated token). Token
   endpoint authentication: *None* (public client with PKCE; there is no client secret).
3. Connections tab: enable **google-oauth2** for this app.
4. The API `https://api.wingman.app` must have **Allow Offline Access** on (already required for
   Android).
5. Put the new **Client ID** and the backend's HTTPS base URL into `Packaging/Info.plist`
   (`WingmanAuth0ClientId`, `WingmanAPIBaseURL`; the issuer, audience, redirect and connection are
   prefilled with the beta values). Values starting with `REPLACE_` disable sign-in with a
   message. For local development the same keys can come from environment variables, and
   `http://127.0.0.1` / `localhost` are the only plain-HTTP hosts the transport allows.

Token handling: authorization code + PKCE `S256` (RFC 7636, tested with the RFC vector) in
`ASWebAuthenticationSession`, `state` checked, `offline_access` scope. The refresh token is in
the Keychain only; the access token is in memory only; neither is ever written to the state
file, logs or command lines (`testStateNeverHoldsTokens`, `testLogsNeverContainMessageData`).

**Follow-up (backend/contract, not here):** a connection-scoped device credential (ingest,
claim, unpair for one connection) instead of a full user token (WING-020 §6).

## 4. Capability matrix

What the companion claims at pairing (`Capabilities.document`). The server stores a client
claim as UNVERIFIED at best (ADR 0007).

| Capability | Status | Reason |
|---|---|---|
| **read** | **UNVERIFIED** | Implemented and fixture-tested; needs Full Disk Access; never run on a Mac. **NO-GO** if M1 to M4 fail or the 2026-10-02 Full Disk Access controls make it impractical (I-034, §8) |
| **draft** (fill the Messages compose field) | **UNSUPPORTED** | Not in the Messages scripting dictionary; GUI scripting (Accessibility) is excluded. Wingman-internal drafts and assisted delivery work regardless |
| **send** | **UNVERIFIED** (UNSUPPORTED in draft-only mode or after `-1743`) | Implemented: constant script, argv only, ack only from the sent row. Never executed. Stays UNVERIFIED until M8, M9, M12 and M14 pass; otherwise ship draft-only (D-024) |
| **profile_read** | **UNSUPPORTED** | Messages has no dating profiles |
| **swipe** | **UNSUPPORTED** | Not applicable |
| calendar_write | UNSUPPORTED | Not applicable |

Finer behaviour (all UNVERIFIED on a Mac unless marked):

| Behaviour | Status |
|---|---|
| New inbound 1:1 iMessage / SMS (forwarded) / RCS → `message.received`, `identity: linked` | UNVERIFIED (fixture-tested) |
| Inbound group message → inbound notice (`identity: ambiguous`), never replied to | UNVERIFIED (fixture-tested, backend-tested) |
| Send into an existing 1:1 chat | UNVERIFIED |
| Send into a group chat | UNSUPPORTED by default (refused locally; `allowGroupSends` exists for a future, approval-only decision) |
| Start a new conversation | UNSUPPORTED (GUIDs are never built from addresses) |
| History upload | UNSUPPORTED by design (baseline only) |
| Attachments | inbound count only (UNVERIFIED); outbound UNSUPPORTED |
| Reactions (tapbacks) | skipped inbound; UNSUPPORTED outbound |
| Edits and unsends | UNSUPPORTED: they UPDATE rows the ROWID cursor never revisits; an unsend that was already uploaded stays in Wingman (a retracted row not yet read is skipped) |
| Delivery receipt (`is_delivered`) | UNVERIFIED; `reports_delivery_receipts: false` |
| Typing, read receipts | UNSUPPORTED |

## 5. Packaging: Developer ID, hardened runtime, notarization

Files: `Packaging/Info.plist` (`LSUIElement`, `NSAppleEventsUsageDescription`,
`CFBundleURLTypes` for `com.wingman.companion`, the Wingman keys),
`Packaging/WingmanCompanion.entitlements` (`com.apple.security.automation.apple-events`;
**no** `com.apple.security.app-sandbox`; the application identifier and keychain access group
for the data-protection keychain), `Packaging/build-app.sh`.

Why not the Mac App Store: a sandboxed app cannot read `~/Library/Messages`, and sending Apple
events from the sandbox needs temporary-exception entitlements (Apple QA1888). Developer ID
software must be notarized for Gatekeeper.

One-time (owner, Apple Developer Program):

1. A **Developer ID Application** certificate in the login keychain.
2. An App ID `ai.wingman.companion` and a **Developer ID provisioning profile** for it (needed
   for the keychain access group of the data-protection keychain; without it every Keychain call
   fails with `errSecMissingEntitlement`, which the app shows as an error, never a file fallback).
3. Notary credentials: `xcrun notarytool store-credentials wingman-notary --apple-id <id> --team-id <TEAMID>`
   (an app-specific password, or an App Store Connect API key).

Build, sign, notarize, staple:

```bash
cd macos/wingman-companion
Packaging/build-app.sh \
  --identity "Developer ID Application: <Name> (<TEAMID>)" --team <TEAMID> \
  --profile ~/Downloads/WingmanCompanion_DeveloperID.provisionprofile \
  --notary-profile wingman-notary
# -> .build/package/Wingman Companion.app and WingmanCompanion.dmg (notarized, stapled)
```

The script signs with `--options runtime --timestamp`, verifies the signature, fails if the
apple-events entitlement is missing or a sandbox key is present, then `hdiutil` → `notarytool
submit --wait` → `stapler staple` → `spctl --assess`. CI runs only `--adhoc` (structure checks).

## 6. Manual real-Mac test script

Run on a Mac you own, with your own Apple account and a peer device you own. Record each run as
a new section `## Mac run YYYY-MM-DD` at the end of this file, with the macOS version, the
Messages account setup (iMessage address, SMS forwarding on/off) and the app build. **For every
step, record what the script or app reported and what you observed on the peer device in
separate columns**: an exit code or "executed" is never evidence of delivery. Never paste
message text, phone numbers or full GUIDs.

Spike probe (M1 to M14, unchanged, from Terminal, before the app):
`P="python3 macos/imessage_spike/manual/mac_probe.py"` then `$P env`, `$P read --limit 200`,
`$P watch --seconds 120`, `$P send --chat-guid '<GUID>' --text 'WING030 probe 1'`.

| # | Step | Expected | Moves |
|---|---|---|---|
| M1 | `$P env` without Full Disk Access | `full_disk_access: NOT readable` | read: failure path |
| M2 | Grant Full Disk Access to Terminal, `$P env` | readable; `-wal`/`-shm` present; columns present | read: open |
| M3 | `$P read --limit 200` | `text_source=none` 0 (or reactions/system rows); note how often `attributedBody` was needed | read: decoding, groups |
| M4 | `$P watch`; peer sends a 1:1 iMessage | one row, `from_me=false`, lag < 5 s | **read** (with M2, M3) |
| M5 | `$P watch`; peer sends in a group | `group=true` | groups inbound |
| M6 | `$P watch`; tapback, edit, unsend | tapback is a new row; edit and unsend add no row | reactions, edits |
| M7 | `$P watch`; an SMS arrives via forwarding | `transport=sms` | transport |
| M8 | `tccutil reset AppleEvents`; `$P send`; **Don't Allow** | `-1743`; peer receives nothing | send: denied |
| M9 | `$P send` again, allowed | exit 0; `sent_row_found: true`, `error 0`; peer receives **once** | **send** (with M8, M12, M14) |
| M10 | `$P send` into a group GUID | each member once (informational: the app refuses groups) | groups outbound |
| M11 | `$P send` into an `SMS;-;` chat | peer phone gets one SMS, or record the failure | SMS via forwarding |
| M12 | `$P send` with Messages quit; screen locked; after sleep/wake | record each: launch? sent? error? | reliability |
| M13 | Remove Full Disk Access and Automation; `$P env`, `$P send` | not readable; `-1743` | revocation |
| M14 | `$P watch`, Ctrl-C, peer sends, `$P watch` again | the new row shown once | restart |

The app (signed build from §5; remove Terminal's grants first so the app's own TCC identity is
tested):

| # | Step | Expected (app / backend) | Observe separately |
|---|---|---|---|
| A1 | Launch; *Sign in with Wingman* | browser → Google → back; status *Signed in*, then *Connected* or *Needs Full Disk Access*; backend: a `imessage`/`companion` connection with this `device_install_id`, `read`/`send` UNVERIFIED | the connection appears in the Wingman app |
| A2 | Without Full Disk Access | *Needs Full Disk Access*; backend `read: UNSUPPORTED` (`capabilities_changed`) | no messages appear in Wingman |
| A3 | Grant Full Disk Access, reopen | status *Connected*; nothing from history uploaded | Wingman inbox shows no old threads |
| A4 | Peer sends a 1:1 iMessage | within ~30 s the thread appears in the Wingman inbox (`listConversations`), contact `imessage:<address>` | text matches; lag |
| A5 | Peer writes in a group | an inbound notice in Wingman; no draft, no approval | |
| A6 | In Wingman, approve a reply (or send one yourself) | the app claims it; the Automation prompt appears the first time (allow); `send_acknowledged` only after the row shows up in chat.db | peer receives it **once**; the Wingman message turns *sent* |
| A7 | Same as A6 but **Don't Allow** at the prompt | `send_failed` / `permission_denied`; *Automation* row red; claims stop | peer receives nothing; message *failed*, not retried |
| A8 | Re-allow in Settings, *retry sends*, approve another reply | resumes; one send | once |
| A9 | Approve a reply, then flip *Stop all sends from this Mac* before the next cycle | no claim; the instruction stays pending server-side (or, if already claimed, `send_failed` / `capability_unsupported`) | nothing sent |
| A10 | Approve a reply, then **takeover** the contact in Wingman before the next cycle | the claim returns nothing; the message is *canceled* | nothing sent |
| A11 | Turn on Wingman's global pause with an autopilot send queued (only possible after `send` is VERIFIED) | canceled at claim | nothing sent |
| A12 | Approve, then put the Mac to sleep for 3 min before the cycle | lease expires; Wingman shows *outcome unknown* | on wake: no late send of an expired lease; if one was mid-send, a single late ack |
| A13 | Quit the app (Activity Monitor: force quit) right after an approval is claimed, reopen | the ledger blocks a second attempt; the ack comes from the row, or `timeout` after 60 s | peer receives at most one |
| A14 | Lock the screen / log out with an approved reply pending | record: does the send happen while locked? (Keychain is `WhenUnlocked`: expect *Waiting for unlock*) | |
| A15 | *Disconnect* | connection *disconnected* in Wingman; Keychain items gone (`security find-generic-password -s ai.wingman.companion` finds nothing); `~/Library/Application Support/ai.wingman.companion/` empty | |
| A16 | Two Apple accounts in Messages (if available): reply into a chat of each | each reply leaves from the chat's own account | |
| A17 | Console.app, subsystem `ai.wingman.companion` | only IDs, counts and codes; no text, numbers or GUIDs | |

A capability becomes VERIFIED only through the operator CLI with the recorded run as evidence:
`python -m app.services.connections verify --user-id <user> --connection-id <connection> --capability read --evidence "docs/integrations/imessage_companion.md Mac run YYYY-MM-DD"`
(read: M2 to M4 and A3, A4; send: M8, M9, M12, M14 and A6 to A8, A13), for that macOS version
and `connector_version` `0.1.0`.

## 7. Failure cases and what the code does

| Case | Behaviour | Test |
|---|---|---|
| Mac offline or asleep | Nothing runs. Queued envelopes stay on disk; leases expire and the backend records `lease_expired` (outcome unknown, never retried). On wake the cycle resumes; a late ack is still accepted | `test_mac_asleep_lease_expires_unknown_never_retried`; `testBackendUnreachableBacksOffAndKeepsTheQueue` |
| Backend unreachable | Upload stops at the first failure (order kept); exponential backoff with jitter (5 s doubling to 15 min); no claim; local reading continues up to the 1000-envelope bound, then the cursor waits | `testBackendUnreachableBacksOffAndKeepsTheQueue`, `testBoundedQueueAppliesBackPressureWithoutLoss`, `testDrainOrderStopsOnRetryAndContinuesAfterUnpaired` |
| Permission revoked (Full Disk Access / Automation) | §2 table: `capabilities_changed` once, claims stop, UI explains the fix | `testFullDiskAccessRevokedLowersReadAndStopsClaims`, `testAutomationDeniedIsReportedAndClaimsStop` |
| Multiple accounts in Messages | A reply goes into the chat GUID it came from, which carries its account (UNVERIFIED, A16) | n/a |
| Group conversation | Inbound → notice (`ambiguous`) or skipped; outbound refused | `testGroupIsAmbiguousNoticeOrSkipped`, `testUnsafeInstructionsAreRefusedNeverSent`, `test_group_messages_never_draft_or_send_even_on_autopilot` |
| Duplicate messages | GUID dedup set (14 days) + `event_id` = UUIDv5; cursor rewinds and rebuilt databases dedup; server dedups on event and on `external_message_id` | `testDedupAcrossRestartCursorRewindAndNewRowid`, `testCrashBeforeSaveReplaysIdenticalEnvelopes`, `test_duplicate_envelopes_dedup_across_a_restart` |
| Reactions or edits | Tapback rows skipped; edits/unsends invisible to the cursor (§4) | `testClassifySkipsWhatIsNotAnInboundMessage` |
| Recent history unavailable | Not uploaded by design; rows older than the baseline (e.g. a late iCloud sync) are never emitted | `testFirstPollBaselinesAndEmitsNoHistory`, `testLateRowsOlderThanBaselineAreNeverEmitted` |
| Locked keychain | Status *Waiting for the keychain to unlock*; no fallback; sync resumes after unlock | `testLockedKeychainWaits`, `testLockedKeychainSurfacesAsLocked` |
| Conversation deleted in Messages | Its `chat id` no longer resolves: `-1728` → `send_failed` / `rejected_by_platform` / not retryable. A deleted row is never re-emitted (dedup) | `testAppleEventErrorMapping` |
| AppleScript unsupported or broken by an update | Compile/run errors map to `transport_error` (not retried); the ack rule means a silent failure becomes `timeout` (not retried), never a false *sent*. Fallback: draft-only | `testScriptSuccessIsNotAnAckUntilTheRowAppears`, `test_assisted_delivery_when_send_is_unsupported` |
| Restart mid-send | The key is in the ledger before the Apple event; a restart never sends it again; the ack is recovered from the row (by length when the text was lost) or `timeout` after 60 s | `testKeyIsClaimedBeforeTransportAndCrashRecoversAsUnknownTimeout`, `testCrashAfterMessagesSentRecoversAckWithoutTheText`, `testRestartWhileWaitingForTheRowStillAcksOnce` |
| Lease about to expire | No Apple event at or after `lease_expires_at - 15 s` (server-relative); `timeout` / retryable | `testLeaseDeadlineBlocksTheSend` |
| Instruction for another connection, or not leased | Ignored, no answer | `testForeignOrNonLeasedInstructionsAreIgnored` |
| Unpaired elsewhere | `422` → local unpair, no further claims; late outcomes still upload | `testServerSideUnpairStopsClaims`, `test_unpair_stops_claims_and_refuses_inbound_but_takes_late_outcomes` |
| Another install tries to claim | `422` at `/device_install_id` (D-017) | `test_claim_is_bound_to_the_companion_install` |
| Takeover / global pause after queuing | Canceled server-side; the claim returns nothing | `test_takeover_cancels_pending_automated_sends_and_claims_get_nothing`, `test_global_pause_cancels_autopilot_sends_even_with_verified_send` |

## 8. I-034: the Full Disk Access risk

On 2026-10-02 Apple announced additional Full Disk Access controls requiring "very explicit user
action", naming communication apps and AI agents. Neither the mechanism nor a date has been
published as of this PR (not re-checked on a Mac: none was available). The read path depends
entirely on Full Disk Access; there is no supported alternative (private frameworks and code
injection into Messages are excluded). If a macOS release makes the grant impractical, **read is
NO-GO** (D-024) and the companion has no inbound path at all; draft-only would then need another
inbound source. The first §6 run must note the macOS version and exactly what granting took.
I-034 stays open.

## 9. Integration notes (for the integrator)

* **No contract change and no backend code change.** The companion uses contract v1.3.1 as is.
* **Proposals (not made here):** an optional `is_group` (and `transport`) inbound payload field,
  so group threads can be ingested as approval-only conversations instead of notices; a
  connection-scoped device credential (§3).
* **CI:** new workflow `.github/workflows/macos-companion.yml` (two jobs: Linux Core in the
  digest-pinned `swift:6.0.3-noble` image; macOS 15 build + tests + ad-hoc bundle). Path-filtered
  to `macos/wingman-companion/**`. Public repository, so the macOS runner is free; if that ever
  changes, the macOS job can be limited to `push` on `main`.
* **Golden files** `macos/wingman-companion/Fixtures/*.json` are produced by the Swift tests
  (`WINGMAN_UPDATE_GOLDEN=1 swift test`) and consumed by `backend/tests/connectors/imessage/`.
  A change to the mapper must regenerate them in the same PR.
* Owner actions: §3 (Auth0 Native app, Client ID and API URL in `Info.plist`), §5 (Developer ID,
  provisioning profile, notary credentials), §6 (the real-Mac run).

```bash
cd macos/wingman-companion && swift build && swift test      # Core: Linux or macOS
Packaging/build-app.sh --adhoc                                # macOS: bundle + checks
cd backend && uv run pytest tests/connectors/imessage         # backend, real PostgreSQL
```
