# Android SMS connector (WING-016)

**Overall integration status: UNVERIFIED.** No physical device and no Android SDK were available
to the authoring agent (I-001, I-003, I-008). The pure-Kotlin core is unit-tested and its envelopes
validate against the frozen contract. The Android adapters have never been compiled or run.
Every device claim in this document is **UNVERIFIED** unless it says otherwise. Section
[Device test checklist](#device-test-checklist) lists the exact steps that move it to VERIFIED.

| Item | Status | Evidence |
|---|---|---|
| Inbound mapping to `connector_event.v1` `message.received` (platform `sms`) | VERIFIED (JVM) | `SmsInboundTest`, schema validation of emitted fixtures |
| Inbound dedup: duplicate broadcast, redelivery after a restart (persisted state) | VERIFIED (JVM) | `SmsInboundTest`, `SmsConnectorTest` |
| Send bookkeeping: one transport attempt per key (D-006), replay, key conflict, crash during submit | VERIFIED (JVM) | `SmsSenderTest` |
| Sent / delivered result state machine, multipart, typed failure codes | VERIFIED (JVM) | `SmsSenderTest` |
| Capabilities document (`read`/`send` UNVERIFIED, others UNSUPPORTED) | VERIFIED (JVM) | `SmsCapabilitiesTest`, schema validation |
| Android adapters compile | **UNVERIFIED** | no SDK in sandbox |
| Real SMS received on a device | **UNVERIFIED** | checklist D3 |
| Authorized SMS sent on a device | **UNVERIFIED** | checklist D4 |
| No double reply on duplicate or restart, on a device | **UNVERIFIED** | checklist D5, D6 |
| Failed send in airplane mode on a device | **UNVERIFIED** | checklist D7 |
| Delivery reports from a real carrier | **UNVERIFIED** | checklist D4 |
| Upload of envelopes to the backend | **BLOCKED** (I-010) | no contract route |

## 1. What it does

```
 SMS_DELIVER broadcast                     authorized dispatch path (WING-011)
        │                                              │ SmsSendCommand(key, outbound_message_id, thread, text, authorized_by)
 SmsDeliverReceiver (android/)                         ▼
        │ IncomingSms (already-extracted fields)   SmsSender ── claim key durably ──► SmsTransport.submit (AndroidSmsTransport, SmsManager)
        ▼                                              ▲                                  │ per-part "sent" / "delivered" PendingIntents
 SmsInboundProcessor ─ dedup claim (durable)           └──────── SmsResultReceiver ◄──────┘
        │                                              │
        ▼                                              ▼
            SmsOutbox (durable, keyed by event_id) ──► SmsEnvelopeUploader (TODO I-010)
```

* **Pure Kotlin** (`com.wingman.integrations.sms`, no `android.*` imports): models, envelope
  factory, deterministic IDs, durable state store, dedup, outbox, sender state machine, failure
  mapping, capabilities. Unit-tested on the JVM.
* **Thin Android adapters** (`com.wingman.integrations.sms.android`): `SmsDeliverReceiver`,
  `SmsResultReceiver`, `AndroidSmsTransport`, `SmsProviderWriter`, `SmsRoleHelper`, the three
  role-qualifying components in `SmsRoleComponents.kt`, `SmsConnectorRuntime` (process holder),
  and the debug-only `SmsDemoActivity` harness.
* **No autonomous sending.** The inbound path has no reference to the sender.
  `SmsSender.send` is the only thing that calls the radio. The automation controller is its only
  intended caller, and on a debug build the demo screen's explicit "Send" tap also calls it.
  `RESPOND_VIA_MESSAGE` is declared only so the app qualifies for the role, and it refuses to send.

### Identities and dedup

| Field | Value | Why |
|---|---|---|
| `external_message_id` (inbound) | `sms:in:` + first 40 hex chars of SHA-256 over the normalized sender, the SMSC timestamp from the PDU, the subscription ID and the body | Identical for a duplicate broadcast and for an OS redelivery after a restart. The device receive time is *not* an input. The body is hashed, never embedded |
| `event_id` | name-based UUID of `(user_id, type, external_message_id or idempotency_key)` | The contract requires a re-sent event to reuse its `event_id` |
| `external_thread_id` | `sms:` + normalized address (e.g. `sms:+15550101234`) | 1:1 threads only. The sender parses it back to get the destination |
| `external_contact_id` | normalized address | Formatting is stripped (`+1 (555) 010-1234` becomes `+15550101234`). No country code is inferred |
| `external_message_id` (outbound ack and receipt) | `sms:out:` + idempotency key | SMS assigns no platform ID to our own sends |
| `occurred_at` / `observed_at` (inbound) | SMSC timestamp / device receive time | |

Dedup uses the contract key `(user_id, platform, external_message_id)`. The device keeps it in
`SmsInboundDedup`, which is persisted and pruned after 7 days. The backend applies the same key
permanently.
The known collision: two *different* messages from the same sender with the same body, the same
SMSC second and the same SIM become one message. That is preferred over a double reply.

Crash ordering: the receiver enqueues the envelope (keyed by `event_id`, so it is stored once)
and only then claims the dedup key. Neither order of a crash loses the message or duplicates it.

### Send semantics

* The key is persisted as `claimed` (`transport_called = true`) **before** `SmsManager` is
  called. Every later call with the same key is answered from the ledger and never reaches the
  radio, before or after a restart (D-006). The same key with a different body or thread returns
  `KeyConflict`.
* A precondition failure (no telephony, no `SEND_SMS`, capability UNSUPPORTED, or a thread
  ID that isn't an SMS thread) also uses up the key, without a transport call.
* All parts `RESULT_OK` emits `message.send_acknowledged`. That is contract state `sent`, **not delivered**.
* `message.delivery_receipt` is emitted only when the status report of **every** part says
  delivered (GSM TP-Status 0x00 to 0x1F). A failed status report keeps the message at `sent`
  (the contract has no event for it) and is recorded locally as `delivery_failed`.
* Status reports can arrive before the last "sent" result. They are recorded and applied once
  the message is acknowledged.
* An unknown outcome is failed as `timeout` with `retryable: false`, because the message may
  already have gone out. This covers a process that died between the claim and the submit, and
  "sent" results still missing after 2 minutes (`SmsSender.expireStale()`, run by
  `SmsConnector.maintain()`).
* A multipart message where some parts went out and others failed is never retryable.

| Platform result (`SmsManager`) | `failure.code` | `retryable` |
|---|---|---|
| `RESULT_ERROR_RADIO_OFF` (airplane mode) | `not_connected` | true |
| `RESULT_ERROR_NO_SERVICE` | `not_connected` | true |
| `RESULT_RADIO_NOT_AVAILABLE` | `not_connected` | true |
| `RESULT_ERROR_GENERIC_FAILURE` | `transport_error` | true |
| `RESULT_ERROR_NULL_PDU` | `transport_error` | false |
| `RESULT_ERROR_LIMIT_EXCEEDED` | `rejected_by_platform` | true |
| `RESULT_NETWORK_REJECT` | `rejected_by_platform` | true |
| `RESULT_ERROR_FDN_CHECK_FAILURE` | `rejected_by_platform` | false |
| `RESULT_ERROR_SHORT_CODE_NOT_ALLOWED` / `_NEVER_ALLOWED` | `permission_denied` | false |
| any other code | `transport_error` | false |
| `SecurityException` from the submit | `permission_denied` | false |
| `IllegalArgumentException` from the submit | `transport_error` | false |
| no "sent" result in time, or a crash mid-submit | `timeout` | false |
| partial multipart failure | code of the first failed part | false |

`retryable` means that a **new** attempt with a **new** key (`send:<message_id>:<attempt>`,
D-006) may succeed. The connector itself never retries.

### Capabilities

| Capability | Published | Notes |
|---|---|---|
| `read` | UNVERIFIED | UNSUPPORTED without telephony. The note lists what is missing (role, `RECEIVE_SMS`) |
| `send` | UNVERIFIED, `reports_delivery_receipts: true` | UNSUPPORTED without telephony. Autopilot stays off until VERIFIED (ADR 0005) |
| `draft` | UNSUPPORTED | Never writes into another app's compose field |
| `swipe` | UNSUPPORTED | |
| `calendar_write` | UNSUPPORTED | |

**Notification access is never SMS capability.** `SmsDeviceState.notificationListenerEnabled`
exists only so that a test can prove it changes nothing. `SmsRoleHelper` does not probe it.
`VERIFIED` is produced only from an `SmsDeviceVerification` that a human recorded with the
checklist below, and only while the role and permission are still held.

## 2. Default-SMS-app role

Since Android 4.4, only the **default SMS app** receives `SMS_DELIVER`, may write to the SMS
provider, and is expected to store every message. Other apps only get the passive
`SMS_RECEIVED` broadcast, which needs `RECEIVE_SMS` (a hard-restricted permission, see Play
policy). On Android 10+ the role is `RoleManager.ROLE_SMS`. `SmsRoleHelper.requestRoleIntent()`
returns `createRequestRoleIntent(ROLE_SMS)` (API 29+) or `ACTION_CHANGE_DEFAULT` (API 26 to 28).
The user must confirm a system dialog. The role holder automatically gets the SMS permission
group (UNVERIFIED per OEM).

Why full send and receive needs it (UNVERIFIED on device):

* **Receive:** `SMS_DELIVER` is only delivered to the role holder. Relying on
  `SMS_RECEIVED` without the role is a Play-policy violation for this use case and doesn't make
  the message ours to store.
* **Send:** `SEND_SMS` works without the role technically, but Play only allows it to the
  default handler or to approved exception categories. Without the role, the sent message is
  also not written to the user's SMS history.
* **Consequence:** while Wingman is the default, **it is the user's SMS app**. Every other SMS
  app becomes read-only. Wingman must store incoming and sent messages in the provider
  (`SmsProviderWriter` does this best-effort), and MMS (out of scope) is **not handled**. This
  is acceptable for a test device and a blocker for a general release (see
  [Limits](#5-platform-limits)).

A device lists an app as a role candidate only if the manifest declares all four components:

| Component | Class | Behaviour in the MVP |
|---|---|---|
| `SMS_DELIVER` receiver (`BROADCAST_SMS`) | `SmsDeliverReceiver` | Full inbound path |
| `WAP_PUSH_DELIVER` receiver (`BROADCAST_WAP_PUSH`, `application/vnd.wap.mms-message`) | `MmsWapPushDeliverReceiver` | No-op: MMS unsupported |
| `RESPOND_VIA_MESSAGE` service (`SEND_RESPOND_VIA_MESSAGE`) | `RespondViaMessageService` | Refuses: it would bypass the dispatch path |
| `SENDTO` activity (`sms:`, `smsto:`, `mms:`, `mmsto:`) | `ComposeSmsActivity` | Opens the app, never sends |

## 3. Manifest patch (integrator-owned, D-003)

Add the following to `android/app/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <!-- WING-016 SMS connector -->
    <uses-feature android:name="android.hardware.telephony" android:required="false" />
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.SEND_SMS" />
    <uses-permission android:name="android.permission.READ_SMS" />
    <!-- Held by default SMS apps; UNVERIFIED whether the role needs it on every OEM. -->
    <uses-permission android:name="android.permission.RECEIVE_MMS" />

    <application ...>
        <!-- existing MainActivity unchanged -->

        <!-- Default-SMS-app role components (all four are required to qualify) -->
        <receiver
            android:name=".integrations.sms.android.SmsDeliverReceiver"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS">
            <intent-filter>
                <action android:name="android.provider.Telephony.SMS_DELIVER" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".integrations.sms.android.MmsWapPushDeliverReceiver"
            android:exported="true"
            android:permission="android.permission.BROADCAST_WAP_PUSH">
            <intent-filter>
                <action android:name="android.provider.Telephony.WAP_PUSH_DELIVER" />
                <data android:mimeType="application/vnd.wap.mms-message" />
            </intent-filter>
        </receiver>

        <service
            android:name=".integrations.sms.android.RespondViaMessageService"
            android:exported="true"
            android:permission="android.permission.SEND_RESPOND_VIA_MESSAGE">
            <intent-filter>
                <action android:name="android.intent.action.RESPOND_VIA_MESSAGE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="sms" />
                <data android:scheme="smsto" />
                <data android:scheme="mms" />
                <data android:scheme="mmsto" />
            </intent-filter>
        </service>

        <activity
            android:name=".integrations.sms.android.ComposeSmsActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <action android:name="android.intent.action.SENDTO" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="sms" />
                <data android:scheme="smsto" />
                <data android:scheme="mms" />
                <data android:scheme="mmsto" />
            </intent-filter>
        </activity>

        <!-- Per-part sent/delivered results from our own PendingIntents. NOT exported. -->
        <receiver
            android:name=".integrations.sms.android.SmsResultReceiver"
            android:exported="false" />
    </application>
</manifest>
```

Add the debug-only harness in a **new** `android/app/src/debug/AndroidManifest.xml`, so it never
ships in release (the activity also refuses to run when the build is not debuggable):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name=".integrations.sms.android.SmsDemoActivity"
            android:exported="true"
            android:label="Wingman SMS test" />
    </application>
</manifest>
```

`exported="true"` on the debug activity lets `adb shell am start` reach it. It adds no launcher icon.

### Dependencies

No new Gradle dependencies are required. The core uses only `kotlinx-serialization-json`
(already declared) and the JDK. A periodic `SmsConnector.maintain()` needs a scheduler. The
integrator can add `androidx.work:work-runtime-ktx` later, or call `maintain()` on app start and
from the demo screen until then.

### AppContainer wiring (integrator, after merge)

* Call `SmsConnectorRuntime.configure(context, SmsConnectionScope(userId, connectionId))` when the
  user enables the SMS connection. Pairing isn't in the contract yet (D-004), so the demo screen
  uses the fixed IDs from `contracts/examples`.
* Call `SmsConnectorRuntime.setUploader(...)` with an HTTP uploader once the I-010 route exists.
  It lives in `core/api`, which WING-016 doesn't own.
* Call `SmsConnectorRuntime.get(context)?.maintain()` on app start and periodically.
* WING-013 onboarding launches `SmsRoleHelper.requestRoleIntent()` from an explicit user action.

## 4. Play Store policy constraints

Google Play's *SMS and Call Log permissions* policy (UNVERIFIED against the current text, so a
human must re-read it before any Play submission):

* `RECEIVE_SMS`, `READ_SMS`, `SEND_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` and `WRITE_SMS` are
  restricted. An app may request them only if it is, or is actively asking to become, the
  **default SMS handler**, or qualifies for a listed exception. "AI reply assistant" is not a
  known exception.
* The app must submit a **Permissions Declaration Form** in Play Console, and Play reviews it.
  Without approval the build is rejected or removed.
* The core function has to be SMS messaging, and the app must stay usable as a full SMS app. A
  default handler that drops MMS (as this MVP does) is very unlikely to pass review.
* Sideloaded or internal-test builds are not policy-reviewed. That is fine for device testing,
  but it is **not** a path to the public Play Store as is.
* Android 13+ marks SMS permissions on sideloaded APKs as "restricted settings". The user may
  have to allow them from the app-info screen (UNVERIFIED per version and OEM). Granting the role
  through the system dialog is the supported path.

## 5. Platform limits

* **Multipart (concatenated SMS):** inbound PDUs from one broadcast are joined in order
  (`getMessagesFromIntent`). Parts of a long message that arrive in **separate** broadcasts
  are not reassembled by the app. The telephony stack normally reassembles them before
  `SMS_DELIVER` (UNVERIFIED per carrier). Outbound uses `divideMessage` and
  `sendMultipartTextMessage`, with one sent and one delivered PendingIntent per part.
* **MMS: out of scope.** Group chats, pictures and long messages that a carrier converts to MMS
  are not received or sent. While Wingman is the default SMS app, MMS is **lost** for that user.
  This is the main reason the role is only acceptable on test devices.
* **Dual SIM:** the inbound subscription comes from the `android.telephony.extra.SUBSCRIPTION_INDEX`
  extra (API 30+), with the legacy `subscription` extra as a fallback (UNVERIFIED per OEM). It is
  part of the inbound identity. Outbound uses the default SMS subscription unless a
  `subscriptionId` is passed. The dispatcher has no field for it yet, so a reply may leave from a
  different SIM than the one the message came in on (UNVERIFIED).
* **Carrier differences:** delivery reports are optional per carrier and may never arrive, so
  messages then stay at `sent`. That is correct, not a bug. Some carriers send reports for the
  last part only (UNVERIFIED). CDMA status reports use a different status encoding than GSM
  TP-Status (UNVERIFIED). SMSC timestamps have one-second resolution and some networks report
  the local time zone wrongly. The identity only needs them to be stable, not correct.
* **Short codes and premium numbers:** Android may show a confirmation dialog or refuse
  (`SHORT_CODE_*`, mapped to `permission_denied`).
* **Rate limits:** the OS limits apps to about 30 SMS per 30 minutes before prompting (UNVERIFIED,
  differs by version), which maps to `rejected_by_platform`.
* **Process death:** receivers run with `goAsync()` and a worker thread, and stay well under the
  10 s broadcast budget. Everything that matters is in the app-private state file
  `files/wingman-sms-state.json` (dedup claims, send ledger, outbox). It contains message text
  while envelopes wait for upload. `allowBackup="false"` keeps it out of backups.
* **Direct Boot:** SMS that arrive before the first unlock after a reboot are not handled,
  because the receiver is not `directBootAware`. The OS redelivers `SMS_DELIVER` after unlock
  (UNVERIFIED).

## 6. Testing prerequisites

* A **physical** Android phone (API 26+, Android 10+ preferred) with an active SIM and SMS plan.
  Emulators can fake inbound SMS (`adb emu sms send`), but that doesn't count as VERIFIED.
* A **second phone** (the "peer") that can send SMS to and receive SMS from the test phone.
* A machine with the Android SDK and `adb`, and USB debugging enabled on the test phone.
* Accept that, for the length of the test, **Wingman is the default SMS app** on that phone (MMS is
  dropped). Afterwards, switch the default back to the original SMS app.
* A debug build with the manifest patch above applied: `cd android && ./gradlew :app:installDebug`.

## 7. Device test checklist

Record every run in a new section at the end of this file (`## Device run YYYY-MM-DD`), with the
device model, Android version, carrier, build commit, and PASS/FAIL per step. A capability may be
published as VERIFIED only with `evidence` pointing at that section and `verified_at` set to the run
time.

Useful commands (run on the computer):

```bash
PKG=com.wingman
DEMO=$PKG/.integrations.sms.android.SmsDemoActivity
adb shell am start -n $DEMO                                    # open the harness
adb shell cmd role get-role-holders android.app.role.SMS       # who holds the role
adb shell am force-stop $PKG                                   # simulate process death
adb shell run-as $PKG cat files/wingman-sms-state.json | python3 -m json.tool   # inspect state (debug build only; contains private text)
adb shell content query --uri content://sms/inbox --projection _id:address:date_sent --sort "date DESC" | head -3
adb logcat -s ActivityManager:I | grep -i wingman             # process kills / receiver starts
```

| # | Step | Expected | Verifies |
|---|---|---|---|
| D1 | Install the debug build. Run `am start -n $DEMO`. Tap **1. Request default-SMS-app role** and accept the system dialog | `get-role-holders` prints `com.wingman`. The screen shows `default SMS app: true` | role qualification (all four components) |
| D2 | Tap **2. Request SMS permissions** (if not already granted) and **3. Configure demo connection scope** | `RECEIVE true; SEND true`. `scope configured: true`. The capabilities JSON shows `read`/`send` as `UNVERIFIED` and the note says nothing is missing | probing, capability document |
| D3 | From the peer phone, send `WING016 hello 1` to the test phone. Tap **Refresh** | Outbox grows by 1. The pending envelope has `"type":"message.received"`, `"platform":"sms"`, `external_message_id` starting `sms:in:`, `external_thread_id` `sms:+<peer number>`, `payload.text` = `WING016 hello 1`, `occurred_at` ending in `Z`. The message also appears in the system SMS inbox (`content query` above). **The peer receives nothing** | **receive (`read`)**, envelope mapping, no autonomous reply |
| D4 | Enter the peer number and text `WING016 reply 1`. Tap **4. Send authorized SMS (NEW key)** | The status line shows `Submitted, replayed=false, transportAttempts=1`. Within about 10 s, Refresh shows a `message.send_acknowledged` envelope with the same `idempotency_key`. The peer phone receives the text **once**. If the carrier sends reports, a `message.delivery_receipt` follows. Note in the run log whether it did | **send**, ack is not delivery, delivery receipts per carrier |
| D5 | Tap **5. Send again with the SAME key** twice | Each tap shows `Acknowledged` (or `Submitted`), `replayed=true`, `transportAttempts=1`. The peer receives **nothing more** | D-006 one attempt per key |
| D6 | Run `am force-stop $PKG` (process death). Reopen with `am start -n $DEMO`. Tap **5** again. Then tap **Re-inject last received SMS** | Step 5 shows `replayed=true` and the peer receives **nothing**. Re-inject shows `Duplicate` and the outbox size is unchanged. Also send the same text again from the peer (`WING016 hello 1`): it **is** a new message (new SMSC timestamp), shows `Accepted`, and the outbox grows by 1 | **no double reply after restart**, durable dedup and ledger |
| D7 | Turn on airplane mode, tap **4** with text `WING016 offline`. Turn airplane mode off. Tap **5** | First tap: within seconds a `message.send_failed` envelope appears with `failure.code` `not_connected`, `retryable: true`, and `external_message_id: null`. The **5** tap returns `Failed, replayed=true` and the peer receives nothing (a retry needs a new key). Tapping **4** again (new key) sends it | failed send, failure mapping, no automatic retry |
| D8 | Send a 400-character text with **4** | The peer receives one joined message. The ack only arrives after all parts are reported | multipart outbound |
| D9 | Ask the peer for a 300-character SMS | One `message.received` with the full text | multipart inbound |
| D10 | (Dual SIM only) Send to each SIM from the peer | Both arrive. Dedup IDs differ when the text and timestamp are equal but the SIMs differ | subscription handling |
| D11 | Copy the outbox envelopes into files and validate them against the schema (next section) | All valid | contract conformance of real envelopes |
| D12 | Restore the original default SMS app (Settings, Default apps, SMS app) | Wingman's capabilities note shows `Missing: default-SMS-app role` | role loss is detected |

D3 PASS makes `read` VERIFIED. D4 + D5 + D6 + D7 PASS make `send` VERIFIED. The
acceptance criterion "no double reply on duplicate broadcast/restart" is D5 + D6.

What the harness cannot do: send a **forged** `SMS_DELIVER` broadcast. It is protected by the
`BROADCAST_SMS` signature permission, so `adb shell am broadcast` cannot imitate the radio.
"Re-inject" re-runs the exact fields of the last real SMS through the same
`SmsInboundProcessor` the receiver uses, after a real process death. A true OS redelivery can
only be observed if the process is killed inside `onReceive`, which can't be scripted reliably.

### Short demonstration (about 5 minutes)

1. `adb shell am start -n com.wingman/.integrations.sms.android.SmsDemoActivity`. Show that the default SMS app is `true` and the capabilities are UNVERIFIED.
2. Peer phone sends "Hey! Free tonight?". Show the `message.received` envelope and that no reply went out.
3. Type "Yes! 8pm?" and tap **4**. The peer receives it once, and the `send_acknowledged` envelope appears.
4. Tap **5** twice, force-stop, reopen and tap **5** again. The peer still has exactly one reply.
5. Airplane mode on, tap **4**. A `send_failed` / `not_connected` envelope appears.

### Schema validation of emitted envelopes

JVM fixtures (what the authoring agent ran, see the PR): set `-Dwingman.smsFixturesDir=<dir>` for
the unit tests, so `SmsContractFixturesTest` writes one file per envelope type. Then validate:

```bash
uv run --no-project --with 'jsonschema[format-nongpl]==4.26.0' --with referencing==0.37.0 python - contracts <dir> <<'EOF'
import json, sys, pathlib
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
c, d = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]); reg = Registry(); S = {}
for p in (c / "schemas").glob("*.json"):
    s = json.loads(p.read_text()); S[p.name] = s; r = Resource.from_contents(s)
    reg = reg.with_resource(s["$id"], r).with_resource(p.name, r)
for f in sorted(d.glob("*.json")):
    v = Draft202012Validator(S["connector_event.v1.json" if f.name.startswith("event.") else "connector_capabilities.v1.json"], registry=reg, format_checker=FormatChecker())
    errs = [e.message for e in v.iter_errors(json.loads(f.read_text()))]
    print("OK  " if not errs else "FAIL", f.name, errs[:2])
EOF
```

For D11, save each envelope from the device state file as `event.<n>.json` in `<dir>` and run the
same command.

## 8. Proposed ingest route (I-010, for a separate additive contract PR)

There is no contract route for device-side connectors to push `connector_event.v1` envelopes.
`NoIngestRouteUploader` returns `RetryLater`, so envelopes wait in the durable outbox. No endpoint
is invented in code. Proposal:

```yaml
  /v1/connections/{connection_id}/events:
    parameters:
      - $ref: "#/components/parameters/ConnectionId"   # new parameter, same shape as ConversationId
    post:
      tags: [connections]
      operationId: ingestConnectorEvent
      summary: Device-side connector reports one connector_event.v1 envelope
      description: |
        For connectors that run on the user's device (WING-016 SMS, WING-017 notifications).
        The body is exactly one `connector_event.v1` envelope. The server rejects it with 404 if
        `connection_id` (path and body must match) is not owned by the authenticated user, or if
        `user_id` is not the caller. Dedup follows ADR 0004: a repeated `event_id`, or a repeated
        `(user_id, platform, external_message_id)` for inbound messages, returns `200` with
        `duplicate: true` and causes no second message or state transition. `mock.*` types and
        `platform: mock` are rejected with 422 on this route. Send outcome events
        (`message.send_acknowledged`, `message.send_failed`, `message.delivery_receipt`) must
        reference an `outbound_message_id` and `idempotency_key` the dispatcher issued to this
        connection; anything else is a 422.
        Clients use the envelope's `event_id` as the `Idempotency-Key`.
      parameters:
        - $ref: "#/components/parameters/IdempotencyKey"
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "schemas/connector_event.v1.json"
      responses:
        "202":
          description: Accepted (new event).
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ConnectorEventIngestResult"
        "200":
          description: Already seen (duplicate event or duplicate inbound message). No side effect.
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ConnectorEventIngestResult"
        "400":
          $ref: "#/components/responses/BadRequest"
        "401":
          $ref: "#/components/responses/Unauthenticated"
        "404":
          $ref: "#/components/responses/NotFound"
        "409":
          $ref: "#/components/responses/Conflict"
        "422":
          $ref: "#/components/responses/Error"
        default:
          $ref: "#/components/responses/Error"

# components.schemas
    ConnectorEventIngestResult:
      type: object
      additionalProperties: false
      required: [event_id, duplicate]
      properties:
        event_id:
          $ref: "#/components/schemas/Id"
        duplicate:
          type: boolean
```

Device uploader mapping: `2xx` returns `Accepted`. `400`, `404` and `422` return `Rejected`
(dead letter, never retried). Network errors, `409`, `429` and `5xx` return `RetryLater`.

**The downlink is also missing.** No contract route delivers a dispatched send
(`SmsSendCommand`) to the device. Polling `GET /v1/connections/{id}/dispatches?cursor=` or FCM are
the options. It needs the same separate contract PR, coordinated with WING-011. Until then, sends
on a device come only from the debug harness, where the user's explicit tap is the authorization.

## 9. Files

* Pure Kotlin: `android/app/src/main/java/com/wingman/integrations/sms/*.kt`
* Android adapters: `android/app/src/main/java/com/wingman/integrations/sms/android/*.kt`
* Tests: `android/app/src/test/java/com/wingman/integrations/sms/*.kt`
