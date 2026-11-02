# Android notification listener and direct reply (WING-017)

**Overall status: UNVERIFIED.** No physical device was available to the authoring agent (I-003). The
pure-Kotlin core is unit-tested on the JVM and its envelopes validate against contract v1.3.0
(ADR 0008). The Android adapters compile against `android-35` (locally and in the CI `android` job) and
have never run. **No dating app has been tested.** Every device claim below is UNVERIFIED until the
[device test procedure](#4-device-test-procedure-using-the-inspector) has been run and recorded.

This is the shared, generic foundation for the notification-assisted path of the Tinder (WING-027),
Bumble (WING-028) and Hinge (WING-029) connectors. Owner decision D-021 adds an experimental
unofficial-API path for those apps, built separately; this notification path is the fallback that
ships regardless. It is a feasibility foundation, **not** a promise of Tinder, Bumble or Hinge support.

## 1. Capability matrix

`read` = notification preview observed and parsed; `send` = reply through the notification's own
RemoteInput action (status `submitted` only); `draft`, `swipe`, `profile_read` are never possible on
this path. No accessibility-service tapping or swiping exists, and none will be added.

| Platform | Package (from Play listing, UNVERIFIED) | read | RemoteInput reply action seen? | send | draft | swipe | profile_read | Plugin |
|---|---|---|---|---|---|---|---|---|
| Tinder | `com.tinder` | UNVERIFIED | unknown (not inspected) | UNSUPPORTED until a RemoteInput is observed and the user enables direct reply; then UNVERIFIED | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | `TinderNotificationPlugin` (WING-027, PR #50) |
| Bumble | `com.bumble.app` | UNVERIFIED | unknown (not inspected) | same rule | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | `BumbleNotificationPlugin` (WING-028, PR #50) |
| Hinge | `co.hinge.app` | UNVERIFIED | unknown (not inspected) | same rule | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | `HingeNotificationPlugin` (WING-029, PR #51; every build, separate from the experimental API connector) |
| SMS (reference) | n/a, WING-016 uses the default-SMS-app role | not via this path | n/a | not via this path | UNSUPPORTED | UNSUPPORTED | n/a | none: notification access is never SMS capability (`docs/integrations/android_sms.md`) |

Per-app fields to fill from the inspector (all **unknown** today):

| Observation | Tinder | Bumble | Hinge |
|---|---|---|---|
| App version / Android version / device | | | |
| Signing-cert SHA-256 (`cert_sha256`) | | | |
| Template (`MessagingStyle`, `BigTextStyle`, none) | | | |
| `messages` > 0 (MessagingStyle) | | | |
| `shortcut_id` present (stable conversation ID candidate) | | | |
| Sender `Person` key present (`message_sender_keys`) | | | |
| Text shown on an unlocked phone / hidden ("new message") | | | |
| Text truncated (`text_ends_with_ellipsis`) | | | |
| Group summary posted (`group_summary=true`) | | | |
| Reply action: `actionN.free_form=true`, result key | | | |
| `semantic=1` (REPLY) on that action | | | |
| Match / like / comment / expiry notifications: shape | | | |
| Reply typed in the system shade appears in the app's chat | | | |

What the code does with these today:

* `read` is **UNVERIFIED** while notification access is granted, a plugin for the platform is
  installed, and the user enabled reading that platform. Otherwise **UNSUPPORTED** (with a note).
* `send` is **UNSUPPORTED** until a free-form RemoteInput was observed on an authentic notification
  of that platform AND the user turned on direct reply for it; then **UNVERIFIED**, which ADR 0005
  allows only through explicit user approval, never on autopilot. The connector never reports
  `VERIFIED` itself (D-016: operator CLI with evidence only).
* `send.reports_delivery_receipts` is `false`: nothing on this path can observe delivery.

## 2. Architecture

```
 StatusBarNotification ─► WingmanNotificationListenerService (android/)
        │  package not in plugin allowlist? ──► return (nothing read)
        ▼  NotificationSnapshotExtractor: extras ─► NotificationSnapshot (pure Kotlin)
 NotificationListenerCore ─ isAuthentic ─ snapshot dedup ─ plugin.parse ─ event dedup ─► NotificationInbox (durable)
        │                                                         └─► LiveReplyActions (in memory, bounded)
        ▼
 capabilities(), capability changes (access revoked/granted)

 claimOutboxInstructions (platform connector's poller) ─► NotificationClaimExecutor
        ─► NotificationContractMapping.replyCommandFrom ─► NotificationReplyAdapter.reply
        ─► ledger CLAIMED (durable) ─► checks ─► AndroidReplyActionHandle.fire (RemoteInput + PendingIntent) ─► SUBMITTED
```

* **Pure Kotlin** (`com.wingman.integrations.notifications`, no `android.*` imports): models,
  plugin interface and registry, authenticity, listener core, dedup, inbox, reply adapter and
  ledger, capabilities, inspector, explainer text, and the contract mapping.
* **Thin Android adapters** (`com.wingman.integrations.notifications.android`):
  `WingmanNotificationListenerService`, `NotificationSnapshotExtractor`,
  `AndroidReplyActionHandle`, `NotificationConnectorRuntime` (process holder),
  `NotificationAccessActivity` (explainer + debug inspector toggle).
* **No plugin shipped in WING-017.** Since WING-027..029 (PRs #50, #51) the allowlist is
  `PlatformPlugins.all()`: Tinder, Bumble and Hinge, registered by default so a cold process the
  system starts for the listener has them too. Reading a platform still needs the user's opt-in
  in Settings, which pairs it first (`transport: notification`, I-033 closed on the client side).
  Every shape the plugins parse is synthetic until the device run (N1 to N13, I-003).

### Plugin interface (for WING-027..029)

```kotlin
interface NotificationAppPlugin {
    val platform: String                         // "tinder" | "bumble" | "hinge"
    val packageNames: Set<String>                // exact match only
    val trustedSigningCertSha256: Set<String>    // default empty = not pinned yet
    fun isAuthentic(source: NotificationSource, snapshot: NotificationSnapshot): AuthenticityVerdict
    fun parse(snapshot: NotificationSnapshot): List<ParsedNotificationEvent>
    fun replyAction(snapshot: NotificationSnapshot): SnapshotAction?   // default: free-form RemoteInput, REPLY first
}
```

* `isAuthentic` default (`NotificationAuthenticity.check`): exact package match (lookalikes such as
  `com.tinder.fake`, `com.tinderr` or a different case are rejected); snapshot package equals the
  source package; when digests are pinned, the posting package's signing-cert SHA-256 must match,
  and unreadable certificates are rejected. Pin the digests from the inspector output.
* `parse` returns `ParsedNotificationEvent(kind, externalConversationId?, externalMessageId?,
  senderLabel?, body?, truncated, contentHidden, occurredAtMillis, identity)`. `kind` is one of
  `MESSAGE, MATCH, LIKE, COMMENT, EXPIRY, SYSTEM, UNKNOWN`; `identity` is `LINKED` (needs a stable
  conversation ID), `UNLINKED` (label only; never auto-merged) or `AMBIGUOUS`. It must be pure, total
  and silent (no logging). A throwing plugin is contained and yields no events.
* Helpers: `NotificationParsing.looksTruncated`, `occurredAt`, `incomingMessages`.

### Robustness

| Case | Behaviour | Test |
|---|---|---|
| Exact repost / replay after restart | Snapshot dedup on `(platform, key, postTime, contentHash)`, persisted; claimed after processing, so a crash mid-way replays and event dedup drops what was recorded | `exact repost is a duplicate`, `dedup survives a restart...` |
| Update re-listing old MessagingStyle messages | Event dedup on a content-derived message ID (`notif:` + hash of platform, kind, conversation or label, time, body); only new messages recorded | `update re-listing old messages...` |
| Same content, new post time | Recorded once | `same content reposted...` |
| Group summary | Skipped, no action registered | `group summaries are skipped` |
| Hidden content ("You have a new message") | `contentHidden=true`, `body=null`; reported with `content_hidden: true` and empty text, so the backend stores a notice | `hidden and truncated content are flagged` |
| Truncated preview | `truncated=true` | same |
| Empty notification | `can_read=false` | same |
| Match / like | Distinct kinds, never repliable | `match and like are kept apart...` |
| Unlinked sender | Recorded, never gets a reply action | `unlinked message is recorded...` |
| Access revoked | Stops processing, drops all live actions, records `access_revoked` capability change per platform; `read`/`send` become UNSUPPORTED; replies fail `ACCESS_REVOKED` | `revoked access stops...`, `revoked access refuses...` |
| Process death | State file rebuilt; live actions rebuilt from `getActiveNotifications()` on reconnect; a reply key left `CLAIMED` becomes `OUTCOME_UNKNOWN` (never re-fired) | `one submit per key across a restart`, `process death between claim and result...` |
| Notification removed | Its live action dropped | `removed notification drops its reply action` |
| Too many live actions | Bounded (64), oldest evicted | `live action map is bounded` |

### Reply semantics

* Only `NotificationReplyAdapter.reply(NotificationReplyCommand)` fires an action. Its only
  production constructor path is `NotificationContractMapping.replyCommandFrom`, from an
  `OutboxInstruction` this install leased (ADR 0006), via `NotificationClaimExecutor`. The listener
  core has no reference to the adapter.
* The key is persisted `CLAIMED` **before** anything can fire, and `action_fired=true` is persisted
  immediately before `fire()`. One attempt per key, before or after a restart (D-006). Same key,
  different text or conversation: `KeyConflict`, nothing fired. A refused precondition uses up the key.
* Preconditions, in order: access granted; plugin and read enabled; direct reply enabled for the
  platform; a live free-form RemoteInput action for **exactly** that platform and linked conversation;
  action not older than 6 h; lease deadline not passed (checked last).
* A fired PendingIntent is recorded `SUBMITTED`. It is never `sent` or `delivered`.

| Local reason | Contract `failure.code` | retryable |
|---|---|---|
| `ACCESS_REVOKED` | `permission_denied` | false |
| `PLATFORM_UNAVAILABLE`, `DIRECT_REPLY_DISABLED`, `ACTION_MISSING`, `ACTION_STALE` | `capability_unsupported` | false |
| `ACTION_CANCELED` (PendingIntent canceled) | `rejected_by_platform` | false |
| `FIRE_ERROR` | `transport_error` | false |
| `LEASE_DEADLINE_PASSED` | `timeout` | true |
| `OUTCOME_UNKNOWN` (process died after claim) | `timeout` | false |

### Contract mapping (isolated in `NotificationContractMapping.kt`)

Targets contract **v1.3.0** (ADR 0008; integrator update when v1.3 merged). Pair per app
(`tinder`/`bumble`/`hinge` with `transport: notification`) or as `android_notification`.

* **Every** processed event maps to `message.received` with the 1.3.0 payload fields `kind`
  (`MESSAGE` to `chat_message`, `LIKE` and `COMMENT` to `like_comment`, ...), `identity`,
  `truncated`, `content_hidden`, `sender_label`, `source: notification` and, when the plugin has
  one, `external_conversation_id`. Only a `linked`, complete `chat_message` reaches the backend
  pipeline; everything else becomes a notice that is never replied to or merged.
* `linked` needs the plugin's conversation ID (then `external_thread_id` = `external_contact_id` =
  that ID). Without one the event is sent as `unlinked`, with no thread and a fixed
  `<platform>:unlinked` contact placeholder: a sender label never becomes an identity. Missing
  text is reported as `content_hidden` with empty text.
* A `SUBMITTED` reply produces **no** envelope. Firing a RemoteInput only hands the text to the
  other app; `send_acknowledged` would mean `sent`, and `user_reported_sent` is only for an
  assisted draft the user confirms. The message stays `sending` until its lease lapses and the
  backend records `lease_expired` (outcome unknown, never `sent`, never retried). Failures map to
  `message.send_failed`.
* The capability document includes the 1.3.0 optional `profile_read` (UNSUPPORTED).

## 3. Privacy

* No notification text, title, sender name, conversation ID or reply text is ever logged. The core
  logs through `NotificationLog` with event names, enums, counts and hashes only. Every type that
  carries private text redacts it in `toString()`. Checked by a sentinel test
  (`NotificationPrivacyAndCapabilityTest`).
* Packages outside the allowlist: the service returns before reading any extra.
* Recorded events (with text) are kept only in the app-private `wingman-notification-state.json`
  (`allowBackup=false`).
* The explainer (`NotificationAccessExplainer`, shown by `NotificationAccessActivity`) says what is
  read, what is never done, and how to revoke, before the user goes to system settings.

### Debug-only notification inspector

On a **debuggable** build, after the tester turns it on in `NotificationAccessActivity`, the service
logs one REDACTED line per notification from the allowlist plus the three candidate packages
(`com.tinder`, `com.bumble.app`, `co.hinge.app`) to logcat tag `WingmanNotifInspector`. It logs
presence and length of each text field, counts, flags, template, category, channel ID, scrubbed
action titles (words that also appear in the notification's text become `<w>`, digits `#`),
RemoteInput result keys and free-form flags, the semantic action, and the signing-cert SHA-256. It
never logs text, names or IDs in clear. It does nothing on a release build. Example (synthetic):

```
I WingmanNotifInspector: pkg=com.example.fakedating key_hash=3f9c0a1e5b7d2c44 post_time=1793473200000 when_set=true
  title=len3 text=len18 big_text=null sub_text=null conversation_title=null text_ends_with_ellipsis=false
  is_group_conversation=false messages=1 message_senders_distinct=1 message_sender_keys=0 message_own=0
  message_with_data=0 shortcut_id=present#9a0e17c3d55b2f80 group=present group_summary=false category=msg
  channel_id=messages template=MessagingStyle public_version=false actions=2 action0.title=Reply
  action0.remote_inputs=1 action0.free_form=true action0.result_keys=key_text_reply action0.semantic=1
  action0.contextual=false action1.title=Mark_as_read action1.remote_inputs=0 action1.free_form=false
  action1.result_keys= action1.semantic=0 action1.contextual=false reply_action_candidate=0 cert_sha256=unavailable
```

## 4. Device test procedure (using the inspector)

Needs: a physical Android 10+ phone, a **debug** build with the manifest snippet below, and a test
account on each dating app with at least one test match that a second phone/account controls. Never
use a real person's conversation; never commit raw logs.

1. **N1 Install.** `adb install app-debug.apk`. Record Android version, device model, and each
   dating app's version (`adb shell dumpsys package com.tinder | grep versionName`, same for the others).
2. **N2 Explainer and access.** `adb shell am start -n com.wingman/.integrations.notifications.android.NotificationAccessActivity`.
   Check the explainer text, tap "Open notification access settings", grant access. Back in the
   screen, "Notification access: ON".
3. **N3 Inspector on.** Tap "DEBUG: start redacted inspector". Run `adb logcat -s WingmanNotifInspector:I WingmanNotif:I`.
4. **N4 Chat message, unlocked.** From the second account, send a short test message. Record the
   inspector line. Fill the per-app table: template, `messages`, `shortcut_id`, `text` presence,
   `text_ends_with_ellipsis`, and every `actionN.*`. A reply action is `free_form=true`.
5. **N5 Long message.** Send a 300-character message: is it truncated?
6. **N6 Locked phone and hidden content.** Lock the phone with "hide sensitive content" on, send
   again: record whether `text` changes (hidden) and whether actions remain.
7. **N7 Update.** Send two more messages quickly: is the notification updated (same `key_hash`,
   `messages` grows) or a new one posted? Is a group summary posted?
8. **N8 Other kinds.** Trigger a new match, a like (and for Hinge, a comment; for Bumble, an expiry
   reminder if possible). Record each shape.
9. **N9 Manual reply in the shade.** If a reply action exists, reply from the system notification
   shade by hand. Does it appear in the app's chat on the second phone? This tells whether the
   app's RemoteInput works at all, independent of Wingman.
10. **N10 Certificates.** Copy `cert_sha256` for each app into the plugin's
    `trustedSigningCertSha256`. If it shows `unavailable`, add the `<queries>` entries below and retry.
11. **N11 Revoke.** Turn notification access off in system settings. The screen shows OFF; the next
    message produces no `WingmanNotif` processing line.
12. **N12 Privacy check.** Save the logcat output and grep it for the test message words and the
    test match's name: there must be no hit. Attach only the redacted lines to the evidence.
13. **N13 (after WING-027..029 ship a plugin)** Enable the platform and direct reply, approve one
    test reply in Wingman, let the device claim it, and record: the reply shows `submitted` and
    whether it appeared in the app's chat on the second phone. Repeat the claim replay and a process
    kill (`adb shell am kill com.wingman`) to confirm a single submit per key.

Record the results in this file (the per-app table) with the date, device, versions and a redacted
evidence reference. A capability moves to VERIFIED only through the operator `verify` CLI with that
evidence (D-016).

## 5. Manifest snippet (integrator-owned, D-003)

Add to `android/app/src/main/AndroidManifest.xml`, inside `<application>`:

```xml
        <!-- WING-017: notification listener. Bound by the system only after the user grants
             notification access. Device behaviour UNVERIFIED. -->
        <service
            android:name=".integrations.notifications.android.WingmanNotificationListenerService"
            android:exported="true"
            android:label="@string/app_name"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>

        <!-- WING-017: notification-access explainer (and, on debug builds, the inspector toggle).
             Exported so the device test can open it with `adb shell am start`; it only shows text. -->
        <activity
            android:name=".integrations.notifications.android.NotificationAccessActivity"
            android:exported="true"
            android:label="Notification access" />
```

And, as a direct child of `<manifest>`, so the signing certificates of the three apps are readable
on API 30+ (package visibility):

```xml
    <!-- WING-017: read signing certificates of the dating apps for authenticity checks. -->
    <queries>
        <package android:name="com.tinder" />
        <package android:name="com.bumble.app" />
        <package android:name="co.hinge.app" />
    </queries>
```

No new permission is needed (`BIND_NOTIFICATION_LISTENER_SERVICE` is held by the system, not
requested). No Gradle change.

## 6. Limits

* A fired PendingIntent proves only that the app was handed the text. Apps may ignore it, require
  the app to be unlocked, or answer with an updated notification; none of that is observable here.
* Notification content is whatever the app chose to show: often truncated, sometimes hidden, and
  possibly only a display name with no stable conversation ID (then `UNLINKED`, never repliable).
* The live action map is in memory; after process death it is rebuilt only from notifications
  still showing.
* OEM battery managers can unbind listeners; `onListenerDisconnected` drops all live actions.
