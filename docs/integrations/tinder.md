# Tinder connector: notification-assisted (WING-027)

**Overall status: UNVERIFIED.** Nothing in this connector has run on a phone or against Tinder.
No Tinder notification has been inspected; every shape the plugin handles is a synthetic
assumption, covered by JVM tests only. The Android adapters compile in CI (`android` job) and have
never run on a device (I-003).

**Path: notification-assisted only.** Decision D-023 (from WING-021, `dating_platform_matrix.md`)
marks Tinder's private-API path **BLOCKED**: the public references date from 2018 to 2021 and the
current login involves captcha challenges. The D-021 addendum in the ticket does not apply. Owner
confirmation of this is tracked as I-032. Re-open only if the matrix §7 conditions are met.

What the user gets: Wingman reads Tinder's notification previews (after the user opts in), drafts a
reply on the server, and shows it under **Send by hand**. The user copies it, opens Tinder, sends it
themselves, and may tap **I sent it**. Wingman never sends, taps, types or swipes in Tinder.

## 1. Capability matrix

| Capability | Status | Why |
|---|---|---|
| read (notification previews) | **UNVERIFIED** | Plugin and listener are built and JVM-tested; what Tinder actually puts in its notifications is unknown until device steps N4 to N8. Reported to the server as `UNVERIFIED` only while notification access is on, a plugin is registered and the user turned reading on; otherwise `UNSUPPORTED`. |
| draft (Wingman writes a reply) | **Available (assisted)** | Drafts are written by the backend and delivered as assisted deliveries (`/v1/assisted-deliveries`, ADR 0008 §6). The connector capability `draft` (placing a draft inside Tinder) is `UNSUPPORTED`. |
| send (from Wingman) | **UNSUPPORTED** | No RemoteInput reply action has been observed. The direct-reply toggle stays disabled until the server stores `send: VERIFIED` (operator CLI with device evidence, D-016). If step N9/N13 shows a working RemoteInput, send can become **UNVERIFIED** (approval-only, never autopilot). |
| profile_read | **UNSUPPORTED** | A notification carries at most a name. |
| swipe | **UNSUPPORTED** | Never automated; no accessibility tapping exists or will be added. |
| calendar_write | **UNSUPPORTED** | Not applicable. |
| unofficial-API connector | **BLOCKED** | D-023. |

`send.reports_delivery_receipts` is `false`. A confirmed assisted delivery is
`user_reported_sent` ("You sent this"), never `sent` or `delivered`.

## 2. What the code does

| Piece | File |
|---|---|
| Plugin (package, authenticity, parsing, identity, reply-action filter) | `android/app/src/main/java/com/wingman/integrations/tinder/TinderNotificationPlugin.kt` |
| Pairing per platform (`POST /v1/connections`, `platform: tinder`, `transport: notification`) | `integrations/notifications/NotificationPairing.kt` |
| Upload queue, backoff, dead letters, outbox poller | `integrations/notifications/NotificationBackendSync.kt` |
| Host: toggles, `refreshAccess`, sync round | `integrations/notifications/NotificationSyncHost.kt` (Android holder: `android/NotificationSyncRuntime.kt`) |
| Settings toggles | `features/connections/NotificationConnectionState.kt` / `NotificationConnectionScreen.kt` |
| Send by hand (assisted deliveries, notices, badge) | `features/assisted/AssistedState.kt` / `AssistedScreen.kt` |

**Package.** `com.tinder`, from the Play listing, UNVERIFIED until step N1. Matching is exact:
`com.tinder.fake`, `com.tinderr`, `Com.Tinder`, `com.tinder.lite` are rejected and never parsed.

**Signing certificate.** Not pinned (`TinderNotificationPlugin.PINNED_CERT_SHA256` is empty, with a
TODO). Step N10 records the digests; once pinned, a notification whose certificate cannot be read
or does not match is rejected.

**Kinds** (synthetic rules; adjust after N8):

| Tinder kind | Wire `kind` | Rule | Reply target? |
|---|---|---|---|
| MESSAGE | `chat_message` | MessagingStyle with incoming messages, or category `msg`, or a generic "new message" text | Only if LINKED, complete and not hidden |
| MATCH | `match` | "It's a Match", "new match", "matched" in the title/channel, then text | Never |
| LIKE / SUPER_LIKE | `like_comment` | "likes you", "new like", "Super Like" | Never |
| SYSTEM | `system` | Categories such as `promo`, `recommendation`, `status`, `social` | Never |
| UNKNOWN | `unknown` | Anything else | Never |

Keyword rules read the title and channel first, the text last, and never a MessagingStyle message
body, so a chat that says "match" stays a chat and a match alert is never a chat.

**Identity.** `LINKED` only with a stable conversation ID: the notification's shortcut ID
(`shortcut:<id>`), else the single sender `Person` key of a one-to-one MessagingStyle conversation
(`person:<key>`). A display name is never an identity: without a stable ID the event is `UNLINKED`
(no thread, no contact; the server stores a notice), and several senders or keys in one
notification, or a group conversation, are `AMBIGUOUS`. Two matches with the same name are never
merged. Linking a notice to a contact is the user's job (WING-032).

**Partial content.** A text ending in "…" or "..." is `truncated`; a generic text ("You have a new
message", "New message", "sent you a message", "3 new messages") is `content_hidden` with no body.
Both become notices, never drafts.

**Reply action.** The plugin offers a RemoteInput action only on a chat-message notification; the
core registers it only for a LINKED message. While send is UNSUPPORTED no outbox instruction exists,
so the claim path normally receives nothing; if one ever arrived with direct reply off, the reply
adapter fails it as `direct_reply_disabled` and reports `message.send_failed` without firing.

## 3. Sync loop (I-033)

* **Pairing is the reading switch.** Settings > Connections > Dating apps > Tinder > *Read
  notifications* pairs first (`createConnection` with the persisted key, the shared per-install
  `device_install_id`, and a claim of `read: UNVERIFIED`, `send: UNSUPPORTED`) and only then turns
  reading on. Turning it off stops reading at once, drops unrecorded events and queued inbound
  envelopes for Tinder, and unpairs (the server cancels pending assisted deliveries).
* **Upload.** Each round turns recorded events into `message.received` envelopes in one persisted
  FIFO queue (all platforms, in order), drains it head-of-line to `ingestConnectorEvent`
  (`Idempotency-Key` = `event_id`), dead-letters `400`/`404`/`422`, and backs off on anything else
  (`Retry-After` honored, otherwise 5 s doubling to 15 min). Everything survives process death.
* **Claims.** Only while notification access is on, for each paired platform with reading on;
  instructions go to WING-017's `NotificationClaimExecutor` (one attempt per key, D-006).
* **refreshAccess on resume.** `MainActivity.onResume` calls `NotificationSyncRuntime.onAppResume`:
  a revocation found there stops reading, drops every live reply action, blocks claims and queues a
  `connection.capabilities_changed` downgrade, before any network round.
* The listener service requests a round after it records new events or an access change.

## 4. Device test (owner, physical phone)

Run `docs/integrations/notification_matrix.md` §4 **N1 to N13** for Tinder first (inspector,
package, shapes, RemoteInput, certificates, revoke, privacy). Then:

1. **T1 Package and certificate.** N1 confirms `com.tinder`; N10 gives `cert_sha256`. Put the digests
   in `TinderNotificationPlugin.PINNED_CERT_SHA256` and record them in the matrix.
2. **T2 Shapes.** From N4 to N8, fill the per-app table: template, `shortcut_id` present, sender
   keys, hidden/truncated behaviour, match/like/super-like shapes. Adjust the keyword rules if the
   real titles differ (add a synthetic test per real shape, never real text).
3. **T3 Pair.** Debug build against a dev backend, signed in. Settings > Tinder > *Read
   notifications* on: the card shows "Connected", read UNVERIFIED, send not supported, and
   "Assisted only: Wingman drafts, you send". Direct reply is greyed out with its explanation.
4. **T4 Message.** From the second account send a message. Check the backend received one
   `message.received` with `source: notification`; a LINKED one becomes a message and (with a
   draft) an assisted delivery; an UNLINKED/hidden one becomes a notice.
5. **T5 Send by hand.** Home > *Send by hand*: the draft shows with Copy, Open app, I sent it,
   Dismiss and a badge on Home. Copy, then Open app (Tinder opens at its home screen; Tinder
   publishes no conversation deep link), paste and send in Tinder, then *I sent it*: the row reads
   "You sent this" and the conversation shows the message as reported by you, never "Delivered".
6. **T6 Notices.** Trigger a match and a like: they appear under Notices with no reply option.
7. **T7 Revoke.** Turn notification access off in system settings, return to Wingman: Settings shows
   access OFF, the next Tinder message is not recorded, and the backend shows the capability
   downgrade.
8. **T8 Privacy.** `adb logcat -s WingmanNotif:I` over the whole run contains no message text or
   names (N12).

Record date, device, Android and Tinder versions and a redacted evidence reference in
`notification_matrix.md`. A capability moves to VERIFIED only through the operator CLI (D-016).

## 5. Limitations

* Previews only: whatever Tinder chooses to show, possibly truncated or hidden; no history, no
  photos, no profile.
* "Open app" launches Tinder's home screen (`getLaunchIntentForPackage`); the notification's own
  `contentIntent` is not kept, so the user finds the conversation themselves.
* No stable ID means no draft: an UNLINKED message stays a notice until the user links it.
* The connector cannot observe whether the user actually sent anything; "I sent it" is the user's
  word (`user_reported_sent`).
* A process killed between recording and upload replays from the state file; a notification shown
  only while the process was dead is lost.
* Tinder's terms forbid automation; this path only reads the user's own notifications and never acts
  in the app (`dating_platform_matrix.md` §3).
