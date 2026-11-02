# Hinge connector (WING-029): experimental API path + notification fallback

**Overall status: UNVERIFIED.** Nothing in this document has run against Hinge or Sendbird. No
agent created an account, logged in, typed a code, or handled a real credential. The pure-Kotlin
core is unit-tested with fakes (MockWebServer for Hinge and Sendbird REST, a fake socket and a
MockWebServer WebSocket for Sendbird). The Android adapters compile only in CI and have never
run on a device (I-003). Every capability stays **UNVERIFIED** until the owner runs the live
test in [§9](#9-owners-live-test-l0-to-l10) and an operator records it (ADR 0007, D-021, D-022).

Decisions: D-021 (experimental route, binding conditions), D-022 (contract 1.3.0), D-023 (Hinge
experimental API per WING-021; never in a Play build). Design source:
[`dating_platform_matrix.md` §6](dating_platform_matrix.md#6-hinge-experimental-connector-design-wing-029).
Protocol reference: `f0rr0/hinge-rs` at `cffe397` (MIT OR Apache-2.0), reimplemented in Kotlin;
see `android/app/src/main/java/com/wingman/integrations/hinge/THIRD_PARTY_NOTICES.md`.

> **Open owner decisions that gate shipping (not code):** I-030 (Hinge terms §2d item 11 restrict
> automatically generated content; this affects AI drafts on every Hinge path, the notification
> fallback included) and I-031 (who may install the sideloaded experimental build, its signing,
> and the final consent text and version; the code uses `d021-v1`).

Contents:
1. [What was built](#1-what-was-built)
2. [Architecture](#2-architecture)
3. [Experimental build (integrator)](#3-experimental-build-integrator)
4. [Opt-in UX](#4-opt-in-ux)
5. [Rate limits](#5-rate-limits)
6. [Stop signals (circuit breaker)](#6-stop-signals-circuit-breaker)
7. [Send semantics](#7-send-semantics)
8. [Notification fallback plugin](#8-notification-fallback-plugin)
9. [Owner's live test L0 to L10](#9-owners-live-test-l0-to-l10)
10. [Capability matrix](#10-capability-matrix)
11. [Differences from the reference, limitations](#11-differences-from-the-reference-limitations)

---

## 1. What was built

| Item | Status | Evidence |
|---|---|---|
| Opt-in: off by default, plain-language ban-risk warning, explicit acknowledgement, consent record (text version, time, `ban_risk_acknowledged`) | VERIFIED (JVM) | `HingeAuthFlowTest` (consent cases), `HingeOptOutAndInertTest` |
| Pairing `POST /v1/connections` with `platform: hinge`, `transport: unofficial_api`, `experimental: true`, `experimental_consent`; key and body persisted first | VERIFIED (JVM) | `HingeMappingAndAllowlistTest` (keys equal the contract example), rig pairing in every flow test |
| Opt-out: opt-in flag cleared first, socket closed, session wiped and Keystore key destroyed, work cancelled, `revokeExperimentalConsent` (persisted, retried offline); then **zero** Hinge or Sendbird calls | VERIFIED (JVM) | `HingeOptOutAndInertTest` |
| Login state machine: install, SMS code, optional email code (412 + case id), chat token; wrong and expired codes; challenge, 426, 429 stop as BLOCKED | VERIFIED (JVM) | `HingeAuthFlowTest` |
| Session store: Keystore AES-256-GCM (existing `KeystoreSecretCipher`, own alias), file in `noBackupFilesDir`, atomic writes, never logged, never sent to Wingman | VERIFIED (JVM) for the file logic; Keystore UNVERIFIED (device) | `HingeTokenIsolationAndPrivacyTest` (static and dynamic) |
| REST client restricted to an allowlist of 9 reference-documented endpoints; everything else refused before the network | VERIFIED (JVM) | `HingeMappingAndAllowlistTest` |
| Sendbird: REST reads with the in-memory `Session-Key`; WebSocket send confirmed by the echo carrying our `req_id`; timeout = outcome unknown, never resent | VERIFIED (JVM, fake socket and a real OkHttp socket on MockWebServer) | `HingeSendTest`, `HingeOkHttpSocketTest` |
| Mapping to `connector_event.v1` 1.3: `source: unofficial_api`, `identity: linked` only with exact ids, `kind` per message type | VERIFIED (JVM) | `HingeMappingAndAllowlistTest` |
| Upload through `ingestConnectorEvent`, reusing the SMS connector's uploader mapping and persisted backoff | VERIFIED (JVM) | every flow test (fake backend) |
| Outbox executor: claim for this connection, one attempt per key persisted first (D-006), lease deadline, approval-only on the device too, no first messages | VERIFIED (JVM) | `HingeSendTest` |
| Rate limiter (persisted, configurable only downward) | VERIFIED (JVM) | `HingeRateLimitTest` |
| Circuit breaker on every stop signal, persisted, manual re-enable only | VERIFIED (JVM) | `HingeCircuitBreakerTest` |
| No message text or tokens in logs | VERIFIED (JVM, sentinels) | `HingeTokenIsolationAndPrivacyTest` |
| Inert without the experimental flag or the client identity | VERIFIED (JVM) | `HingeOptOutAndInertTest` |
| Hinge notification plugin (WING-017 `NotificationAppPlugin`, `co.hinge.app`) | VERIFIED (JVM, synthetic fixtures); real notification shapes UNVERIFIED | `HingeNotificationPluginTest` |
| Android adapters: `HingeModule`, worker, Keystore cipher, opt-in screen | Compiles in CI only; never run | CI `android` job |
| Anything against the real Hinge or Sendbird | **UNVERIFIED** | §9 |

## 2. Architecture

Package `com.wingman.integrations.hinge` (pure Kotlin unless noted):

```
HingeExperimentalActivity (android/) ──► HingeConnector (facade: consent, login, pair, sync, send, opt-out, re-enable)
                                             │
     HingeAuthFlow ──────────────┐           ├── HingeSync (15-min pass) ──► HingeEventMapper ──► HingeEnvelopeQueue ──► Wingman ingestConnectorEvent
     (codes typed by the user)   │           ├── HingeOutboxExecutor ◄── Wingman claimOutboxInstructions
                                 ▼           │          │
     HingeSessionStore ◄── tokens (Keystore, noBackupFilesDir; never to Wingman)
                                 │           │          ▼
     HingeHttp (allowlisted REST) ◄──────────┴── SendbirdChatTransport (REST reads, WS MESG send, Session-Key in memory)
                 │                                         │
                 └──────────► HingeNetGate ◄───────────────┘   (every call: flag, identity, opt-in, breaker, allowlist, rate limit)
                                   │
                    HingeCircuitBreaker (persisted Stopped(reason)) + HingeRateLimiter (persisted counters)
```

| File | Role |
|---|---|
| `HingeCore.kt` | Clock, sleeper, random, redacted log sink, hashes, `HingeCallRefused` |
| `HingeBuildFlags.kt` | `wingman.experimental` flag and the client identity, read reflectively from `BuildConfig` (absent = OFF) |
| `HingeSession.kt` | Session model (redacted `toString`), `HingeSecretCipher`, encrypted file store |
| `HingeNetwork.kt` | `HingeEndpoint` allowlist, `HingeAllowlist`, `HingeNetGate`, `HingeGateInterceptor`, `HingeHttp` |
| `SendbirdChatTransport.kt` | Socket abstraction, OkHttp socket factory, `LOGI`/`MESG`/`EROR` handling, Sendbird REST reads |
| `HingeAuthFlow.kt` | Login state machine |
| `HingeRateLimiter.kt` | `HingeLimits` (downward only) and the persisted limiter |
| `HingeCircuitBreaker.kt` | Stop reasons, response classifier, breaker |
| `HingeEvents.kt` | Sendbird message parsing, `connector_event.v1` mapper, upload queue |
| `HingeSync.kt` | One read pass |
| `HingeOutboxExecutor.kt` | Claim and one-attempt send |
| `HingeBackend.kt` | The Wingman operations used (`HttpHingeBackend`: typed client plus two raw 1.3.0 calls) |
| `HingeConsent.kt` | Warning text and version, consent record, capability claim, pairing body |
| `HingeConnector.kt` | Facade and status |
| `notifications/HingeNotificationPlugin.kt` | The notification-assisted fallback (not experimental) |
| `android/HingeModule.kt` | Init, plugin registration, connector wiring, WorkManager worker, Keystore cipher, stop notification |
| `android/HingeExperimentalActivity.kt` | Opt-in screen (framework views, `FLAG_SECURE`) |

**Allowlisted endpoints (the complete list).** Hinge: `POST /identity/install`,
`POST /auth/sms/v2/initiate`, `POST /auth/sms/v2`, `POST /auth/device/validate`,
`POST /message/authenticate`, `GET /connection/v2`. Sendbird: `GET /v3/users/{me}/my_group_channels`,
`GET /v3/group_channels/{channel}/messages`, and the WebSocket at the root of the WS host.
**Excluded:** recommendations, standouts, rating, likes and like limits, Hinge's REST send
(`/message/send`, dead since app 10.0.0 per the reference), Sendbird channel creation, every
profile route (profile_read is UNSUPPORTED in v1), settings, account, export and moderation
routes. The reference's own commit message mentions probing other paths for profile view
tokens; none of those is used.

**Backend.** No backend change. Hinge pairs as a device-side connection (contract 1.3.0); ingest,
dispatcher, approvals, pause/takeover and the outbox are unchanged. The backend never sees a
Hinge or Sendbird credential: no request body is built from the session (static test), and a
full run with sentinel tokens shows none of them in any Wingman call (dynamic test).

## 3. Experimental build (integrator)

The connector is **compiled into the app but inert** unless the build sets
`wingman.experimental=true` and provides the client identity. With the flag off,
`HingeBuildFlags.fromBuildConfig()` returns OFF, `HingeModule.init` builds nothing, no worker is
scheduled, and the gate refuses every call (tested). The notification plugin is registered
either way, because the notification-assisted path ships regardless (D-021 item 7).

### 3a. Gradle snippet (`android/app/build.gradle.kts`, inside `defaultConfig`, after the OIDC fields; it reuses `prop()` and `quoted()` defined there)

```kotlin
// WING-029 (D-021, D-023): the experimental Hinge connector is inert unless BOTH the flag and the
// client identity are set. Default OFF. Never set it for a Play build (the check below fails it).
val experimental = (project.findProperty("wingman.experimental") as String?)?.toBooleanStrictOrNull() ?: false
buildConfigField("boolean", "WINGMAN_EXPERIMENTAL", experimental.toString())
// Client identity documented by f0rr0/hinge-rs@cffe397 (src/settings.rs, src/client/transport.rs,
// src/client/sendbird.rs). Values live in ~/.gradle/gradle.properties of the person building the
// experimental APK, never in this repo. Empty in every non-experimental build.
fun hinge(name: String) = quoted(if (experimental) prop("wingman.hinge.$name") else "")
buildConfigField("String", "HINGE_BASE_URL", hinge("baseUrl"))
buildConfigField("String", "HINGE_SENDBIRD_API_URL", hinge("sendbirdApiUrl"))
buildConfigField("String", "HINGE_SENDBIRD_WS_URL", hinge("sendbirdWsUrl"))
buildConfigField("String", "HINGE_SENDBIRD_WS_ORIGIN", hinge("sendbirdWsOrigin"))
buildConfigField("String", "HINGE_SENDBIRD_APP_ID", hinge("sendbirdAppId"))
buildConfigField("String", "HINGE_SENDBIRD_SDK_VERSION", hinge("sendbirdSdkVersion"))
buildConfigField("String", "HINGE_APP_VERSION", hinge("appVersion"))
buildConfigField("String", "HINGE_BUILD_NUMBER", hinge("buildNumber"))
buildConfigField("String", "HINGE_OS_VERSION", hinge("osVersion"))
buildConfigField("String", "HINGE_DEVICE_PLATFORM", hinge("devicePlatform"))
buildConfigField("String", "HINGE_DEVICE_MODEL_CODE", hinge("deviceModelCode"))
buildConfigField("String", "HINGE_DEVICE_REGION", hinge("deviceRegion"))
buildConfigField("String", "HINGE_ACCEPT_LANGUAGE", hinge("acceptLanguage"))
```

and, at the end of the file:

```kotlin
// D-023: an experimental build must never become a Play bundle.
gradle.taskGraph.whenReady {
    val experimental = (project.findProperty("wingman.experimental") as String?)?.toBooleanStrictOrNull() ?: false
    if (experimental && allTasks.any { it.name.startsWith("bundle") }) {
        throw GradleException("wingman.experimental=true cannot build an app bundle (D-023: sideload/internal only)")
    }
}
```

Build the experimental APK with `./gradlew :app:assembleDebug -Pwingman.experimental=true`
(plus the `wingman.hinge.*` properties). CI and every other build stay OFF.

**Where each value comes from** (copy them from the reference at `cffe397`; re-check the
reference when Hinge answers 426, never guess): `baseUrl`, `sendbirdAppId`, `sendbirdSdkVersion`,
`appVersion`, `buildNumber`, `osVersion` are the `Settings::default()` values in `src/settings.rs`
(the Sendbird API and WS hosts are derived there from the lower-cased app id as
`https://api-<id>.sendbird.com` and `wss://ws-<id>.sendbird.com`). `devicePlatform` and
`deviceModelCode` are the `x-device-platform` and `x-device-model-code` values in
`src/client/transport.rs`; `sendbirdWsOrigin` is `SENDBIRD_WS_ORIGIN` in
`src/client/sendbird.rs`. `deviceRegion` and `acceptLanguage` should match the owner (the
reference's defaults `IN` / `en-GB` are placeholders). Note that every reference identifies
itself as the **iOS** app; no reference documents Android values (matrix §4).

**Follow-up (recommended):** move `integrations/hinge` except `notifications/` into an
`experimental` product-flavor source set once flavors exist, so the non-experimental APK does
not even contain the code (D-023 says the Play build contains no unofficial-API code; today it
contains it inert). The package layout already separates the two.

### 3b. Wiring (one line, `MainActivity.onCreate`, after the container is built)

```kotlin
HingeModule.init(applicationContext, container) // com.wingman.integrations.hinge.android.HingeModule
```

It registers the Hinge notification plugin with `NotificationConnectorRuntime.installPlugins`,
merged with any plugins already registered (so the Tinder/Bumble module's registration is kept,
in either order), and does nothing else unless the experimental flag and identity are present.
It must run before the notification core is first built (WING-017 rule).

### 3c. Manifest snippet (integrator-owned manifest)

```xml
<!-- WING-029: opt-in screen of the experimental Hinge connector. Not exported: reached from
     Settings > Connections, shown only when HingeModule.connector() != null. -->
<activity
    android:name=".integrations.hinge.android.HingeExperimentalActivity"
    android:exported="false"
    android:label="Hinge (experimental)" />
<!-- Optional (Android 13+): lets the connector post "Hinge connector stopped". Without it the
     reason is shown in-app only. -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

No SMS, notification-listener, accessibility or foreground-service permission is needed by the
experimental connector. `co.hinge.app` is already in `<queries>` (WING-017).

### 3d. Dependencies

None added. OkHttp (REST and WebSocket), WorkManager and kotlinx.serialization are already in
`android/app/build.gradle.kts`; MockWebServer was already a test dependency.

## 4. Opt-in UX

Settings > Connections > Hinge (experimental), only in the experimental build:

1. **Warning first** (`HingeConsentText`, version `d021-v1`): unofficial interface, may break
   Hinge's terms, the account can be restricted or banned, the user bears the risk, login stays
   encrypted on the phone, approval-only, about every 15 minutes, at most 20 replies a day,
   never swipes, likes or writes first, stops on any warning, can be turned off any time and
   that deletes the login. The user must tick "I understand that my Hinge account can be banned,
   and I accept that risk" and tap **I accept the risk**. The consent (version, time,
   `ban_risk_acknowledged: true`) is stored locally; no network call.
2. **Phone number** (E.164). Wingman says "Hinge will text you a code". At most 3 code requests a
   day, only on a tap.
3. **Code typed into Wingman.** If Hinge asks for an email code (HTTP 412 with a case id), the
   screen says so and the user types that code too. Wrong or expired codes: 3 attempts per code,
   then request a new one. Wingman never reads SMS or notifications for codes (no permission,
   static test). The screen sets `FLAG_SECURE`.
4. **Connect to Wingman**: pairs with the consent. Success text: "Connected (experimental,
   approval-only). Wingman will check for new messages about every 15 minutes. Every reply
   needs your approval."
5. **Stopped**: the reason's plain text (`HingeStopReason.userMessage`) and, after any cooldown,
   **Re-enable**. Nothing re-enables by itself.
6. **Turn off and delete my Hinge login**: immediate local wipe, then consent revocation on
   Wingman (retried when offline).

## 5. Rate limits

Defaults are the ceilings; `HingeLimits` rejects any looser value (tested).

| Operation | Limit |
|---|---|
| Sync (channel list + changed channels + matches) | at most once per 15 min (WorkManager periodic, network required) |
| All Hinge REST, Sendbird REST and WebSocket opens | at most 600 a day and 120 an hour (UTC buckets, persisted), at least 2 s apart |
| Message history | at most 20 channels per sync, 30 messages each, only channels whose last message is newer than the stored cursor |
| New-match notices | at most 20 per sync |
| Approved sends | at most 20 a day, 5 per conversation a day, 60 s apart, after a random 20 to 90 s delay that must fit inside the lease |
| First messages, likes, comments, swipes | 0 (not implemented, not allowlisted) |
| Code requests | 3 a day, user-initiated; 3 attempts per code |
| Chat-token refresh | only when expired, or once after a Sendbird 401/403/"Api-Token is missing" or a missing `LOGI` |

## 6. Stop signals (circuit breaker)

Any of these persists `Stopped(reason)`, closes the socket, enqueues
`connection.status_changed` (`disconnected`, `detail` = the reason code only), notifies the user
(in-app status; a system notification when permitted) and refuses every further Hinge or
Sendbird call. Only the user's **Re-enable** clears it (`HingeCircuitBreaker.reEnable`).

| Signal | Code | Extra |
|---|---|---|
| Hinge 401/403 (no Hinge token refresh is documented by the reference, so none is attempted); Sendbird 401/403 or "Api-Token is missing" after one chat-token refresh; no `LOGI` after one refresh | `auth_rejected` | session wiped; re-login required |
| 426 | `client_version_retired` | never auto-bumps the version |
| 429 or any `Retry-After` on an error | `rate_limited` | re-enable refused for 24 h |
| Captcha/challenge wording or vendor headers on an error, an HTML page where JSON was expected, a 412 outside the email-code step, a `LOGI` error | `challenge_presented` | **BLOCKED** (matrix §7 no-go) |
| Ban, suspension, restriction, review or deactivation wording in an error or `EROR` | `account_restricted` | **BLOCKED**; check the official app |
| 2 unexpected 4xx within 1 h | `unexpected_4xx` | |
| 3 consecutive 5xx | `server_errors` | |
| 2xx that cannot be decoded (shape drift) | `protocol_drift` | |
| 2 non-guest send refusals within 24 h | `sendbird_refused` | the first refusal fails that send only |

Wording is looked for only in error responses, `EROR` frames and HTML pages, never in a
successful JSON body, which contains chat text (a match writing "banned" does not stop anything;
tested). A network error just ends the pass. The codes are exactly the `enforcement_signal`
values of `experiments/dating_platforms/live_test_observation.schema.json` (tested).

## 7. Send semantics

- The server leases instructions (approval-only for an experimental-unverified connection,
  D-022). The device claims one at a time for this connection and install (D-017).
- Defense in depth on the device: only `approved_draft` with an approval id and `user_manual`
  are executed; `autopilot` gets `send_failed` `capability_unsupported` (`origin_not_allowed`).
- No first messages: the thread must be a Sendbird channel in which the match has already
  written to the user (seen by sync); otherwise `capability_unsupported`
  (`no_existing_conversation`). Channel creation is not allowlisted.
- Local limit or a lease too short for the pre-send delay: `send_failed`, nothing written.
- The ledger entry is written **before** the `MESG` frame (D-006). A key already in the ledger is
  never sent again, across restarts.
- Echo with our `req_id`: `message.send_acknowledged` with `external_message_id` `hinge:msg:<msg_id>`
  (sent, not delivered: no delivery receipts).
- Guest refusal (900030): `send_failed` `rejected_by_platform`, `retryable: true`, chat token
  dropped; the dispatcher's next attempt (a new key) re-authenticates first. This replaces the
  reference's in-call retry, so one key is still one frame.
- Other refusal: `send_failed` `rejected_by_platform`, `retryable: false`.
- **Timeout, or the socket closing after the frame was written: nothing is reported.** The lease
  expires and the server audits `lease_expired` with outcome unknown (ADR 0006, D-016). It is
  never resent and never shown as sent or failed.

## 8. Notification fallback plugin

`HingeNotificationPlugin` (`co.hinge.app`) implements WING-017's `NotificationAppPlugin`. It is
not experimental and is registered by `HingeModule.init` in every build. Every shape is an
assumption until the WING-017 device run (`notification_matrix.md`, matrix §8):

- Like, comment ("commented on", "liked your photo and said"), match, "Your Turn", standouts and
  promotions are never `MESSAGE`; like/comment wording is checked first, even for MessagingStyle.
- `MESSAGE` is `LINKED` only with a conversation shortcut id and a single sender; two senders are
  `AMBIGUOUS`; a title-only label is `UNLINKED`. Nothing is merged by name.
- Generic "You have a new message" is content-hidden; an ellipsis marks truncation; group
  summaries yield nothing; parsing never throws or logs.
- Reply only through a free-form `RemoteInput` on a MessagingStyle chat notification, never on a
  like, comment or match. A submitted reply stays UNVERIFIED and ends as `lease_expired` (I-033).
- Only one Hinge connection per install: the notification path (`transport: notification`) and
  the experimental API path (`transport: unofficial_api`) cannot both be paired (ADR 0008 §1).

## 9. Owner's live test L0 to L10

Run by the owner or a tester, **with their own account on their own phone**, in the experimental
build. Agents never enter numbers or codes. Record each step in a copy of
`experiments/dating_platforms/results_template.md` and one observation JSON per step that
validates against `experiments/dating_platforms/live_test_observation.schema.json`. **Redact
everything**: no phone number, email, code, token, name, message text, match id or channel URL;
counts, lengths, HTTP status codes and stop codes only. Stop at the first `blocked`.

Useful redacted counters: the screen shows "Hinge requests today"; `adb logcat -s WingmanHinge`
prints event names, endpoint names, status codes and stop codes only (never values).

| Step | What to do | What to record (redacted) | Pass | Fail / blocked |
|---|---|---|---|---|
| **L0 setup** | Build `assembleDebug -Pwingman.experimental=true` with the identity properties. Install on a spare phone that also has the official Hinge app. Open Hinge (experimental), read the warning, tick, accept | Hinge app version (Play store listing), Android SDK int, device model, Wingman commit, `f0rr0/hinge-rs@cffe397`, date, consent version shown | Warning shown, consent stored | Screen missing in the experimental build |
| **L1 login** | Type your number, tap send, type the SMS code; if asked, the email code | `email_code_required`, `challenge_presented`, whether the official app was signed out, final state | State "Logged in" (`ChatReady`) | Any captcha, challenge, attestation prompt, 426 or ban text: **blocked**, abort the program for Hinge (matrix §7) |
| **L2 list** | Connect to Wingman. Wait for (or trigger) one sync. Count conversations in Wingman vs the official app | `conversations_connector`, `conversations_official_app` | Counts equal (Wingman shows at most 20 active channels per pass; compare those) | Mismatch |
| **L3 read** | Pick one existing conversation where the match wrote last. Compare its last 5 messages | `messages_compared` = 5, `messages_matching_order_and_length` | 5 of 5 in order with equal lengths | Missing or out of order |
| **L4 ingest** | Check the backend: `message.received` notices/messages for them. Wait 15 min for another sync | `events_ingested` first and second sync; duplicates | No duplicate messages after the second sync | Any duplicate |
| **L5 draft and approval** | Let Wingman draft a reply in that conversation. Confirm it waits in the approval queue (`capability_unverified`). Approve it | approval reason codes, whether anything was sent before approval | Nothing sent before approval | Any send without approval |
| **L6 send** | The approved reply goes out within about 2 minutes (20 to 90 s delay). Only to a person who agreed to take part | `message.send_acknowledged` present (yes/no), visible on both phones (yes/no), times sent (count) | Visible once in the official app on both phones | Not visible, or visible twice |
| **L7 restart** | Approve a second test reply; force-stop Wingman right after "approve" (before the delay ends); relaunch | sends of that message (count) | 0 or 1, never 2; a lost outcome shows "outcome unknown", not "sent" | Duplicate |
| **L8 failure** | Approve a third test reply; turn on airplane mode before the delay ends | outcome code, number of attempts, calls in the next hour | `send_failed` or outcome unknown, no retry storm, never resent | Repeated attempts |
| **L9 kill switch** | Tap "Turn off and delete my Hinge login". Note "Hinge requests today"; wait 1 h with Wingman open and backgrounded; note it again | requests before/after, consent shows revoked on the backend | Counter unchanged; session file gone | Any further call |
| **L10 soak** | Opt in again (new consent and login). Use approval-only for 7 days within the limits; check the official app daily for warnings | stop codes seen (`enforcement_signal`), warnings in the official app | No stop signal, no warning | Any §6 signal or warning: stop the Hinge program pending owner review (matrix §7 #9) |

Only after L0 to L10 pass may an operator record evidence:
`python -m app.services.connections verify-experimental --evidence <results file>` (lifts the
experimental approval-only gate) and, separately, `verify --capability read|send --evidence ...`
for the app version tested (ADR 0007, D-022). Autopilot stays a separate, explicit owner decision.

## 10. Capability matrix

| Capability | Experimental API path | Notification fallback | Why |
|---|---|---|---|
| read | **UNVERIFIED** | **UNVERIFIED** | Implemented and JVM-tested; never run against Hinge (L2 to L4) / no device run of the notification shapes |
| draft | **UNSUPPORTED** (Wingman drafts in its own UI; nothing is written into Hinge's compose field) | **UNSUPPORTED** (assisted copy/open-app only) | By design; I-030 may restrict AI drafts on Hinge altogether |
| send | **UNVERIFIED**, approval-only | **UNVERIFIED** (RemoteInput only if present; ends as `lease_expired`) | Never run (L6 to L8) |
| profile_read | **UNSUPPORTED** in v1 | **UNSUPPORTED** | Profile routes not allowlisted |
| swipe | **UNSUPPORTED** (handoff: BLOCKED by policy, D-021) | **UNSUPPORTED** | No automated swiping, likes or first messages |

ADR 0008 §8: `BLOCKED` is not a capability label; it is used here only as a test-result word.
The capability document the device claims at pairing is `HingeCapabilities.document()`.

## 11. Differences from the reference, limitations

- **Hinge token refresh:** the reference documents none for the Hinge bearer token, so the
  connector never refreshes it; an expired or rejected token means a new login. Only the chat
  token is renewed (`/message/authenticate`).
- **Guest refusal:** the reference re-authenticates and resends within the same call. Here one
  key is one frame (D-006); the retry is a new dispatcher attempt.
- **Matches:** read from `/connection/v2` as `match` notices; likes received are not read
  (`/like/*` excluded). Comments are recognised only when Sendbird marks a message with a custom
  type naming a like or comment; that mapping is an assumption (UNVERIFIED).
- **Socket lifetime:** opened per sync or send pass and closed after it; no background socket,
  so no real-time inbound (at most 15 minutes late).
- **Install id:** the Hinge connection uses its own `device_install_id`, stored in the Hinge
  state file, not the SMS one. It is sent only to Wingman.
- **Queue file:** queued inbound envelopes (with message text) wait in the app-private,
  non-backed-up state file, like the SMS connector's; tokens are only in the encrypted session file.
- **Same-phone sign-out:** whether logging in from Wingman signs the official app out (or the
  reverse) is undocumented; recorded in L1.
- **Fragility** (matrix §5): version gate (426), transport drift, chat-token staleness, the iOS
  identity claim. Budget for breakage several times a year; the breaker stops on all of them.
