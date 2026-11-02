# Bumble connector: notification-assisted (WING-028)

**Overall status: UNVERIFIED.** Nothing in this connector has run on a phone or against Bumble. No
Bumble notification has been inspected; every shape the plugin handles is a synthetic assumption,
covered by JVM tests only. The Android adapters compile in CI and have never run on a device (I-003).

**Path: notification-assisted only.** Decision D-023 marks Bumble's private-API path **BLOCKED**:
the only client is GPL-3.0 and depends on a reverse-engineered request signature (an anti-tampering
measure, a D-021 no-go). The D-021 addendum in the ticket does not apply (owner confirmation: I-032).

What the user gets: Wingman reads Bumble's notification previews (after the user opts in), drafts a
reply on the server, and shows it under **Send by hand** with Copy, Open app, I sent it and Dismiss.
Wingman never sends, taps, types or swipes in Bumble.

## 1. Capability matrix

| Capability | Status | Why |
|---|---|---|
| read (notification previews) | **UNVERIFIED** | Built and JVM-tested; Bumble's real notification content is unknown until device steps N4 to N8. `UNSUPPORTED` while access is off or reading is off. |
| draft (Wingman writes a reply) | **Available (assisted)** | Backend drafts become assisted deliveries (ADR 0008 §6). The connector capability `draft` (placing text inside Bumble) is `UNSUPPORTED`. |
| send (from Wingman) | **UNSUPPORTED** | No RemoteInput reply action observed. Direct reply is disabled until the server stores `send: VERIFIED` (D-016). If N9/N13 show a working RemoteInput, it can become **UNVERIFIED** (approval-only). |
| profile_read | **UNSUPPORTED** | Not in notifications. |
| swipe | **UNSUPPORTED** | Never automated. |
| calendar_write | **UNSUPPORTED** | Not applicable. |
| unofficial-API connector | **BLOCKED** | D-023. |

A confirmed assisted delivery is `user_reported_sent` ("You sent this"), never `sent`/`delivered`.

## 2. What the code does

Plugin: `android/app/src/main/java/com/wingman/integrations/bumble/BumbleNotificationPlugin.kt`.
Pairing, upload queue, claims, toggles, `refreshAccess` and the Send-by-hand screen are shared with
Tinder; see `docs/integrations/tinder.md` §2 and §3 for the files and the sync loop.

**Package.** `com.bumble.app` (Play listing, UNVERIFIED until N1). Exact match only:
`com.bumble.app.fake`, `com.bumble`, `com.bumble.apps`, `COM.BUMBLE.APP` are rejected.
**Certificate**: not pinned; TODO in `BumbleNotificationPlugin.PINNED_CERT_SHA256` (step N10).

**Kinds** (synthetic rules; adjust after N8):

| Bumble kind | Wire `kind` | Rule | Reply target? |
|---|---|---|---|
| MESSAGE | `chat_message` | MessagingStyle with incoming messages (unless the headline says expiry or reaction), category `msg`, or a generic "new message" text | Only if LINKED, complete, not hidden |
| EXPIRY | `expiry` | "expires", "expiring", "running out", "last chance", "hours left", "extend". **Checked before match**, so "Your match expires" is an expiry | Never |
| REACTION | `like_comment` | "reacted", "reaction", "liked your message/photo". The contract has no reaction kind; `like_comment` is the closest non-chat kind | Never |
| LIKE | `like_comment` | Beeline / admirers, "likes you" | Never |
| MATCH | `match` | "new match", "matched", "It's a match", and **first-move prompts** ("Make the first move", "your move") | Never |
| SYSTEM | `system` | Promo, status and similar categories | Never |
| UNKNOWN | `unknown` | Anything else | Never |

Keyword rules read the title and channel, then the text, never a MessagingStyle message body.

**Identity** follows the same rule as Tinder: `LINKED` only with the shortcut ID or the single sender
`Person` key of a one-to-one MessagingStyle conversation; otherwise `UNLINKED` or `AMBIGUOUS`, never
merged by display name. Truncated (ellipsis) and hidden (generic text) messages become notices.

## 3. Device test (owner, physical phone)

Run `docs/integrations/notification_matrix.md` §4 **N1 to N13** for Bumble. N8 must include an
expiry reminder if Bumble sends one during the test window, a reaction, a Beeline like and a new
match with the first-move prompt. Then run the Tinder steps **T1 to T8** of
`docs/integrations/tinder.md` §4 with Bumble (Settings > Dating apps (notifications) > *Manage* > Bumble), plus:

* **B1 Expiry is never a chat.** When an expiry reminder arrives, it appears under Notices as
  "Expiring", has no reply option, and produces no draft or assisted delivery.
* **B2 First move.** A "make the first move" prompt appears as a notice ("New match"); Wingman does
  not create a draft for a conversation that has no incoming message.

## 4. Limitations

* **Expiry and first-move rules come from notifications only.** Wingman keeps no 24-hour timer, does
  not know who may message first, and does not extend matches. If Bumble shows no notification, Wingman
  knows nothing; if the text changes, the rule may misclassify (safely: as a notice, never a chat).
* **No first-message opportunities are invented.** A draft exists only for a LINKED incoming chat
  message; a match or first-move prompt never yields one.
* A reaction is reported as `like_comment` until the contract adds a reaction kind.
* Previews only, possibly truncated or hidden; "Open app" opens Bumble's home screen (no published
  conversation deep link; the notification's `contentIntent` is not kept).
* "I sent it" is the user's report; Wingman cannot observe the send.
* Bumble's terms forbid scripts and bots; this path only reads the user's own notifications and
  never acts in the app (`dating_platform_matrix.md` §3).
