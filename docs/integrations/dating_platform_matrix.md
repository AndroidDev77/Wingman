# Dating-platform feasibility: Hinge, Tinder, Bumble (WING-021, extended by D-021)

**Status of every live capability in this document: not tested.** No agent ran any client against
Tinder, Bumble or Hinge, created an account, logged in, or handled a credential. Nothing here is
VERIFIED on a device. The evidence is (a) the official terms, help-center and Android/Play
documentation, and (b) a read-only review of open-source clients of the apps' private APIs
(source, README, license, git history, issues). Clones were shallow, treated as untrusted data,
never executed, and deleted afterwards.

Assessed: 2026-11-01. Reference commits are pinned in §4 so the findings can be re-checked.

Labels: **VERIFIED** (reproduced on a device by a person, recorded), **UNVERIFIED** (documented
by a reference or official source, never observed by us), **UNSUPPORTED** (the path cannot do
it), **BLOCKED** (a hard precondition fails: no working auth flow, or it needs a captcha,
attestation or anti-bot bypass, or a license or policy forbids it).

Contents:
1. [Summary and recommendation](#1-summary-and-recommendation)
2. [Capability matrix](#2-capability-matrix)
3. [Terms of service, Play policy and legitimate data paths](#3-terms-of-service-play-policy-and-legitimate-data-paths)
4. [Repository evaluation](#4-repository-evaluation)
5. [Per-platform recommendation](#5-per-platform-recommendation)
6. [Hinge experimental connector design (WING-029)](#6-hinge-experimental-connector-design-wing-029)
7. [Order and no-go conditions](#7-order-and-no-go-conditions)
8. [Android device test procedure: notification path](#8-android-device-test-procedure-notification-path)
9. [Sources](#9-sources)

---

## 1. Summary and recommendation

| Platform | Notification-assisted path (WING-017) | Experimental unofficial-API path (D-021) | Recommendation |
|---|---|---|---|
| **Hinge** | read UNVERIFIED, reply action UNVERIFIED | **Viable to build, UNVERIFIED.** One reference (`f0rr0/hinge-rs`) was fixed against the current app on 2026-10-08 and describes the fixes as checked against a live account. Auth is SMS OTP, plus an email code when Hinge asks for one. No captcha or attestation appears in any Hinge reference. | **Build WING-029 first**, as an approval-only, opt-in, on-device Kotlin reimplementation that follows `hinge-rs`. |
| **Tinder** | read UNVERIFIED, reply action UNVERIFIED | **BLOCKED.** The public references date from 2018 to 2021. Their issues report the SMS auth API changed (2023) and token generation failing (2024). The newest community attempt describes Arkose captcha challenges and an extra email OTP. | Do not start WING-027's API path. Ship notification-assisted only. Re-evaluate only if a maintained reference documents a current auth flow without captcha solving. |
| **Bumble** | read UNVERIFIED, reply action UNVERIFIED | **BLOCKED.** The only API client (`henrydatei/bumble-api`, GPL-3.0) relies on a web session cookie the user copies out of the browser, plus a reverse-engineered request signature (`X-Pingback`). `hpats/bumapi` says itself that it no longer works. `BeeSpy-Enhanced` has no license and is a browser extension, not an API client. | Do not start WING-028's API path. Ship notification-assisted only. |

Cross-cutting findings that change product risk:

* All three terms of service forbid this kind of automation. Hinge's help center says accounts
  using unauthorized automation tools may be banned, and accounts that build or distribute them
  will be ([Hinge help][hinge-automation]). Tinder's terms allow a ban across affiliated Match
  Group services ([Tinder terms §4][tinder-terms]), so automating Tinder also puts the user's
  Hinge account at risk.
* Hinge's terms (§2d, Prohibited Content, item 11) also restrict content the member did not
  write, or that was generated automatically, unless Hinge authorizes it ([Hinge terms][hinge-terms]).
  That touches **every** Wingman path on Hinge, the assisted one included. The owner should read
  this clause themselves; this is not legal advice.
* **Play Store:** Play's Device and Network Abuse policy prohibits apps that use a service or API
  in a way that violates its terms ([Play policy][play-dna]). An experimental unofficial-API
  connector therefore cannot ship in a Play build. It belongs in a sideload or internal build
  flavor only (see §7).

## 2. Capability matrix

One row per capability, for each platform and path. "profile_read" and "detected reply action"
appear only in this matrix. The capability document in the contract (`connector_capabilities.v1`)
advertises only `read` / `draft` / `send` / `swipe` / `calendar_write`.

### 2a. Notification-assisted path (WING-017 listener + assisted copy/open-app fallback)

Nothing here has been observed on a device. No official source documents what these apps put in
their Android notifications, so every positive row is UNVERIFIED until §8 is run.

| Capability | Hinge (`co.hinge.app`) | Tinder (`com.tinder`) | Bumble (`com.bumble.app`) | Evidence | Confidence |
|---|---|---|---|---|---|
| read (incoming text from notification) | UNVERIFIED | UNVERIFIED | UNVERIFIED | Android lets a user-granted `NotificationListenerService` receive posted notifications ([Android][android-nls]). Whether each app puts message text in its notifications (or hides it as a preview setting) is unknown | med that something is readable; low on content |
| reply (send from notification) | UNVERIFIED | UNVERIFIED | UNVERIFIED | Only possible if the app attaches a `RemoteInput` reply action ([Android direct reply][android-reply]). Never assume it (ADR 0005) | low |
| detected reply action | UNVERIFIED | UNVERIFIED | UNVERIFIED | No official or reputable source states that any of the three offers in-notification reply. The §8 procedure records it | n/a until device test |
| profile_read | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | Notifications carry at most a display name and maybe an avatar, not a profile | high |
| swipe | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | Swiping is not a notification action. Automating it through the AccessibilityService API is a no-go (§3c) | high |
| auth | none needed (OS permission: notification access) | same | same | User grants it in system settings ([Android][android-nls]) | high |
| assisted fallback: open the conversation | UNVERIFIED: fire the notification's own `contentIntent`, else launch the app | same | same | No official deep-link scheme is published for any of the three. `PackageManager.getLaunchIntentForPackage` opens the app home ([Android][android-pm]), and a `<queries>` entry is needed on Android 11+ ([package visibility][android-queries]) | med |
| ToS risk | **Low-medium**: reading your own notifications is not API access. Hinge's rule on automatically generated content (§2d item 11) still applies to AI drafts | Low | Low | §3 | |

### 2b. Experimental unofficial-API path (D-021; on-device, opt-in, approval-only)

"UNVERIFIED" here means a reference **documents** it. It does not mean it works today.

| Capability | Hinge | Tinder | Bumble |
|---|---|---|---|
| auth | UNVERIFIED: device install registration, SMS OTP, optional email code (HTTP 412 with a case id), bearer token with a refresh endpoint, then a chat token for Sendbird ([hinge-rs auth.rs][hrs-auth], [hinge-client _core.py][hc-core]) | **BLOCKED**: references document `/v2/auth/sms/*` from 2018 ([fbessez README][fb-readme]). Issues #156 "SMS Authentication API changed" (2023) and #157 (2024) report it broken ([fbessez issues][fb-issues]). The newer attempt (PR #117) is protobuf-based and its thread reports Arkose captcha, an email OTP and temporary bans ([PR #117][fb-pr117]) | **BLOCKED**: web session cookie pasted from the browser, plus a request signature reverse-engineered from the web app ([bumble-api api.py][bb-api]). Web login uses a captcha and an SMS code ([Bumble help][bumble-login]). The password login in `bumapi` is dead ([bumapi README][bumapi]) |
| read (list conversations, read messages) | UNVERIFIED: Sendbird REST `my_group_channels` and `group_channels/{channel}/messages`, using a Session-Key obtained over the Sendbird WebSocket ([hinge-client chat.py][hc-chat], [hinge-rs][hrs-fix]) | BLOCKED (auth). The documented endpoints were `/v2/matches` and `/v2/matches/{id}/messages` ([tinder.py match.py][tp-match]) | BLOCKED (auth). `SERVER_OPEN_CHAT` over `/mwebapi.phtml` ([bumble-api][bb-api]) |
| reply (send) | UNVERIFIED: **Sendbird WebSocket `MESG` frame**, confirmed when the echo carries our `req_id`. hinge-rs found that Hinge's REST `POST /message/send` returns 400 for every body as of app 10.0.0 ([hinge-rs cffe397][hrs-fix]). Older references still use the REST route ([hinge-client][hc-chat]) or fall back to Sendbird REST ([hinge-ts 26b6399][hts-fallback]) | BLOCKED (auth). Was `POST /user/matches/{id}` ([fbessez README][fb-readme]) | BLOCKED (auth + signing). `SERVER_SEND_CHAT_MESSAGE` ([bumble-api][bb-api]) |
| profile_read | UNVERIFIED: `POST /user/v3/public` and `/content/v2/public` with a `viewToken` taken from the like or connection subject, since app 10.0.0 ([hinge-rs cffe397][hrs-fix]) | BLOCKED (auth). Was `GET /user/{id}` | BLOCKED. `SERVER_GET_USER` |
| swipe | UNVERIFIED in the references (`/rate/v2/initiate`, [hinge-client rating.py][hc-rating]). **Disabled by policy**: D-021 forbids automated swiping before a live test passes and the owner enables it. Reported as UNSUPPORTED in v1 | BLOCKED. Was `/like/{id}`, `/pass/{id}` | BLOCKED. Vote `message_type 80` ([BeeSpy content.js][beespy]) |
| detected reply action | n/a (API path) | n/a | n/a |
| evidence | hinge-rs commit `cffe397` (2026-10-08) states its fixes were checked against a live account. Three other clients were active from June to October 2026 | Newest working-flow evidence is about 2021. No maintained client | Newest commit 2025-02 (henrydatei). No evidence of a working login |
| confidence | **medium** that a current flow exists; **low** on how long it lasts (version gate, see §4) | low | low |
| ToS risk | **High**: §2c items 16, 22 and 23 of the terms, and the help center states a ban ([terms][hinge-terms], [help][hinge-automation]) | **High**, and the ban can cover Hinge too ([terms §4][tinder-terms]) | **High**: §3 forbids scripts and bots and lets Bumble terminate immediately ([terms][bumble-terms]) |

## 3. Terms of service, Play policy and legitimate data paths

### 3a. Terms on automation and third-party apps

| Platform | What the terms say (paraphrased) | Realistic consequence for a user automating their own account | Official API |
|---|---|---|---|
| Hinge (terms effective 2025-08-25) | §2c forbids robots, scrapers and other automated access (item 16); third-party apps that interact with the service, including AI/ML systems, without written consent (item 22); and using the API without written consent (item 23). §2d item 11 restricts content that is automatically generated or not written by the member ([terms][hinge-terms]). Hinge's help page names autoswipers, bots and scrapers ([help][hinge-automation]) | Using an unauthorized automation tool **may** get the account banned. Building or distributing one **will** ([help][hinge-automation]). The license ends automatically, and Hinge may suspend, terminate or ban without notice or refund (§2c, §9). §4 allows bans across affiliated services | None. The terms treat any API access as needing written consent |
| Tinder (terms effective 2026-03-05) | Same Match Group structure: §2c items 16, 22 (names AI/ML systems) and 23 (API needs written consent) ([terms][tinder-terms]). The community guidelines forbid third-party apps that unlock features or game the system ([guidelines][tinder-cg]) | Termination without notice (§9). **A ban can extend to affiliated services such as Hinge and OkCupid** (§4) | None |
| Bumble (terms updated 2026-06-17) | §3 forbids software, scripts, robots, crawlers and browser plugins used to scrape, and replicating the app other than through published interfaces. §1 forbids accessing non-public areas, and Bumble may block IPs ([terms][bumble-terms]) | Immediate loss of the right to use the app without notice (§3). Warnings, deletion, account closure, no refund (§16). An appeal is possible within 6 months (§1) | None |

The ticket asked whether any of the three has an official API. **None publishes a messaging,
profile or swipe API.** Hinge's and Tinder's terms require written consent for any use of their
API ([Hinge][hinge-terms], [Tinder][tinder-terms]). Bumble allows only "published interfaces" and
publishes none for members ([Bumble][bumble-terms]).

### 3b. Notification behavior

No official or reputable source documents the notification content, `MessagingStyle` use or
in-notification reply of Hinge, Tinder or Bumble on Android. All of it is **UNVERIFIED** and must
come from §8. Platform facts that are documented:

* A notification listener is a service the user enables in system settings and must be protected
  by `BIND_NOTIFICATION_LISTENER_SERVICE` ([Android][android-nls]).
* An app's reply action is a `Notification.Action` with a `RemoteInput`. The reply is delivered to
  the posting app's `PendingIntent` ([Android][android-reply], [RemoteInput][android-remoteinput]).
  A listener can fill it only if the app attached such an action.
* Android 15 can hide sensitive content such as OTPs from untrusted listeners
  ([Android Authority][aa-sensitive], beta reporting). This is another reason login codes are
  typed by the user and never read from notifications.

### 3c. Play policy

* **Notification access:** the sensitive-permissions policy does not single out notification
  listeners. Its general rules still apply: request only what core features need, make a
  prominent disclosure, and respect a refusal ([Play permissions policy][play-perms]).
* **AccessibilityService automation:** Play restricts this API. Autonomous actions that the app
  starts, plans or carries out by itself are prohibited outside verified accessibility tools
  ([Play accessibility policy][play-a11y]). **No-go** for reading or driving the dating apps' UI.
* **Device and Network Abuse:** apps may not access or use a service or API in a way that violates
  its terms ([Play DNA policy][play-dna]). The experimental API connectors are therefore
  **excluded from any Play build**.

### 3d. Legitimate data paths (no ToS risk)

| Platform | Route | Notes |
|---|---|---|
| Hinge | In the app: Settings, then Download My Data. Confirm by email. Ready within 30 days, and the export stays downloadable for 48 hours ([Hinge help][hinge-dyd]). Privacy requests use a separate article ([Hinge privacy][hinge-privacy]) | The help page does not say whether messages are included |
| Tinder | Manage My Account, then "Download My Information". The link arrives by email within a few days and expires after 24 hours. It may include messaging activity ([Tinder help][tinder-dyd]) | |
| Bumble | Website contact form, "Request my data". Account data or conversation records. Getting both sides of a chat needs a request from each person. Up to 30 days ([Bumble help][bumble-dyd]) | |

These exports suit a one-off **style-profile import** (WING-007): the user uploads their own export
and Wingman parses it on-device or with consent. They cannot serve real-time messaging. Any import
parser is a separate ticket, and exports must never be committed to the repo.

## 4. Repository evaluation

Method: shallow or blob-less clone into a fresh scratch directory, then reading the README,
license, source, git log and the public issue page. The GitHub REST API was not available to this
session, so stars, forks and issues come from the public repository pages on 2026-11-01. "Works
recently" means evidence in commits or issues, never our own run.

| Repo | License (reuse in a closed commercial app) | Last commit / activity | Stars / forks / open issues | Auth flow | Operations, endpoint or transport | Rate limits / bans reported | Works recently? |
|---|---|---|---|---|---|---|---|
| [`f0rr0/hinge-rs`][hrs] (Rust; upstream of hinge-ts and hinge-mcp) | **MIT OR Apache-2.0**: OK. Keep the copyright and license notice for any copied or translated portion | 2026-10-08, `cffe397` "restore app 10.0.0 chat and profile reads", 14 commits | 3 / 1 / 1 (#10, a status announcement naming auth drift and Sendbird breakage, [issue][hrs-10]) | `/identity/install`, then `/auth/sms/v2/initiate`, then `/auth/sms/v2` (OTP), with 412 leading to `/auth/device/validate` (email code), then `/message/authenticate`, which returns a Sendbird JWT. The WS `LOGI` frame returns the Session-Key. Device, install and session ids are client-generated. Headers present the **iOS** app; the app version must track the store version or every call gets **426** | Conversations and messages: Sendbird REST. **Send: Sendbird WS `MESG`** (REST `/message/send` returns 400 for every body). mark_read: WS `READ`. Profiles: `POST /user/v3/public` with a viewToken. Recs, likes and ratings also documented | 426 for retired app versions. Sendbird "Guest is not allowed" (900030) when the chat token is stale. No ban reports | **Yes, medium-high.** The commit message describes the endpoints as live-verified in October 2026 |
| [`Stupidoodle/hinge-client`][hc] (Python) | **MIT**: OK with notice | 2026-06-30 (CI and release). Code last changed 2026-06-27. 109 commits | 9 / 1 / 0 open (closed issues not visible) | Same flow as hinge-rs. Notes that the token refresh endpoint immediately invalidates the old token. Pins app version 9.82.0, which predates the version hinge-rs says is retired | Chat: REST `/message/send` (now reported dead) plus Sendbird REST reads. Profile, recs and rating | README warns that use can get the account banned | **Low.** Likely stale: older version pin, dead send route |
| [`wrsrsh/hinge-ts`][hts] (TypeScript, browser plus proxy) | **MIT**: OK with notice | 2026-10-22 (security and proxy fix only). Chat fixes 2026-09-28. 28 commits | 4 / 1 / not shown | Same flow. Browser-first: needs a server proxy because browsers cannot set the WS handshake headers. **That model conflicts with D-021**, which requires calls from the device | Send: REST `/message/send`, falling back to Sendbird REST `group_channels/{c}/messages` ([26b6399][hts-fallback]). Realtime over WS. Default app version 9.91.0 | Recs fetch: 1.5 s spacing, 3 retries with 4 s backoff on 429/503 | **Low-medium.** The default version is the one hinge-rs reports retired |
| [`Kuberwastaken/hinge-mcp`][hmcp] (TypeScript MCP server) | **MIT**: OK with notice | 2026-09-28, 33 commits | 4 / 0 / not shown | Same flow, through the bundled hinge-ts SDK | 23 MCP tools (read, like, skip, message). A read-only mode is available | Agent guide: stop and report on a rate limit. Users complete CAPTCHAs themselves | **Low-medium.** Pins 9.91.0. Not a model for us (desktop MCP) |
| [`fbessez/Tinder`][fb] (Python, docs) | **MIT** | 2020-06-15, 137 commits | 864 / 200 / 44 | Facebook token (dead), then `/v2/auth/sms/send`, `/validate`, `/login/sms`, giving an `X-Auth-Token` | `/v2/matches`, send `POST /user/matches/{id}`, `/like/{id}`, `/pass/{id}`, `/user/{id}` | Issues #156 "SMS Authentication API changed" (2023-09) and #157 token failure (2024-01). The PR #117 thread reports Arkose captcha, email OTP and temporary bans after failed OTP attempts | **No (low).** Documented auth is broken |
| [`rednit-team/tinder.py`][tp] (Python) | **Apache-2.0**: OK. Keep the NOTICE and license, and state changes | 2021-12-31, 51 commits | 22 / 5 / 0 open | **No login.** The user copies `X-Auth-Token` from the tinder.com browser devtools | Matches and messages `/v2/matches/{id}/messages`, send `POST /user/matches/{id}`, like/pass `GET /like|/pass/{id}` | Client-side limiter. Sleeps on 429 | **No (low).** Nearly 5 years stale. Copying a token by hand is a credential-handling pattern we will not adopt |
| [`henrydatei/bumble-api`][bb] (Python) | **GPL-3.0**: **not reusable** in a closed app. Translating the code would create a derivative work. Protocol facts may be reimplemented clean-room only | 2025-02-03, 17 commits | 13 / 2 / 0 open | **No login.** Uses a web `session` cookie from the browser, plus a reverse-engineered **`X-Pingback` request signature** | `/mwebapi.phtml?SERVER_*` with `badoo.bma.BadooMessage` JSON: get encounters, get user, open chat, send chat message | none | **Low.** No evidence it works now. The signature is an anti-tampering measure, so it is a no-go |
| [`Othmane-ElAlami/BeeSpy-Enhanced`][beespy-repo] (Chromium extension, fork of Seadox/BeeSpy) | **No license file**: all rights reserved, **not reusable**. Only the bundled MediaPipe is Apache-2.0 | 2026-08-04, 21 commits | 0 / 0 / not shown | Rides the user's logged-in Bumble Web tab. Wraps XHR in the page, with the `debugger` permission | Observes `/mwebapi.phtml` traffic (`badoo.bma.BadooMessage`). Vote is `message_type 80`. Includes a face-model **auto-swipe** | none | Active, but it is a **browser-extension interceptor, not a phone client**. Its auto-swipe is out of scope |
| [`hpats/bumapi`][bumapi] (Node) | README says ISC, `package.json` says MIT, and there is **no license file**: ambiguous, do not reuse | 2021-12-15, 5 commits | 19 / 5 / 1 (#3, feature request 2022) | Password login over `/mwebapi.phtml`. **The README says it no longer works** since Bumble moved to phone codes | Encounters, vote, match list | none | **No.** Its author says it is broken |

Attestation and anti-bot findings: a search of every Hinge reference for attestation, Play
Integrity, DeviceCheck, App Check or captcha found **none** required. Note, however, that every
Hinge reference **identifies itself as the iOS app** (platform header, iPhone model code, iOS
user agent). No reference documents Android header values. Tinder's current flow involves an
Arkose captcha. Bumble's documented client depends on a reverse-engineered request signature, and
its web login has a captcha.

## 5. Per-platform recommendation

**Hinge: follow `f0rr0/hinge-rs` (pinned at `cffe397`) as the protocol reference.** Use
`hinge-client` only to cross-check the auth state machine and the refresh semantics.

* **Port:** yes. A clean-room Kotlin reimplementation on-device, using OkHttp (already in
  `android/`) for REST and OkHttp WebSocket for Sendbird. Android can set custom WS handshake
  headers, so the proxy that hinge-ts needs is unnecessary. Do not use the official Sendbird
  Android SDK: it would need Sendbird to accept a third-party SDK identity for Hinge's application,
  which no reference documents.
* **License:** MIT/Apache-2.0. If any code is translated rather than written from the protocol
  description, add the MIT notice for hinge-rs (and for hinge-client if used) to the app's
  third-party notices. Do not copy constants or ids into the Wingman repo beyond what the module
  needs at runtime. Keep the Sendbird application id in build config for the experimental flavor
  only.
* **Fragility, in order of likelihood:**
  1. Version gate (426) when Hinge retires the claimed app version. The connector must stop, not
     auto-bump.
  2. Transport drift. Send moved from REST to the WS between June and October 2026.
  3. Chat token staleness: a stale token gives a silent guest session that can read but not write.
  4. Changed authorization on profile reads (viewToken).
  5. The iOS identity claim. Hinge could start enforcing platform consistency or attestation at any
     time, which would make the path BLOCKED.

  Budget for breakage several times a year.

**Tinder: no reference to follow.** All references are stale (2018 to 2021), and the newest auth
attempt meets an Arkose captcha. WING-027 stays notification-assisted. Re-open only if a
maintained, permissively licensed reference documents a current phone OTP login with no captcha
solving or attestation.

**Bumble: no reference to follow.** The only API client is GPL-3.0 and depends on a request
signature (a no-go). BeeSpy has no license and is the wrong form factor. bumapi is self-declared
broken. WING-028 stays notification-assisted. The API path is BLOCKED.

## 6. Hinge experimental connector design (WING-029)

Everything below is design. Nothing is built or verified. It applies D-021 conditions 1 to 8.

### 6a. Module boundaries (Android, `com.wingman.integrations.hinge`, experimental flavor only)

```
                        ┌──────────── HingeOptIn (per-user, per-platform; ban-risk warning; kill switch)
                        │
 user types OTP ──► HingeAuthFlow ──► HingeSessionStore (Keystore AES-GCM, noBackupFilesDir)
                        │                    ▲
                        ▼                    │ tokens (never logged, never uploaded)
                 HingeRestClient ───────────►│◄──────── SendbirdChatTransport (REST reads + WS send/events)
                   (path allowlist,           │                     │
                    header builder)           │                     │
                        ▲                     │                     │
                        └──── HingeRateLimiter + EnforcementMonitor (circuit breaker, persisted) ────┘
                                              │
 HingeSyncWorker (WorkManager) ─► HingeEventMapper ─► device envelope outbox ─► POST /v1/connections/{id}/events
 POST /v1/connections/{id}/outbox/claim ─► HingeOutboxExecutor ─► SendbirdChatTransport.send ─► send_acknowledged / send_failed
```

| Module | Responsibility | Key rules |
|---|---|---|
| `HingeOptIn` | Per-user, per-platform switch, off by default. Plain-language warning: unofficial interface, may violate Hinge's terms, may get the account restricted or banned, the user bears the risk, can be disabled any time. Records consent version and timestamp locally and on the backend connection (no secrets) | Turning it off immediately cancels workers, closes the WS and wipes the session store. "Disconnect" also unpairs |
| `HingeSessionStore` | Holds hinge token and expiry, identity id, device id, install id, session id, Sendbird JWT and expiry. The Sendbird Session-Key is **in memory only** | Encrypted with an Android Keystore AES-GCM key (non-exportable). File lives in `noBackupFilesDir`. The app already sets `allowBackup=false`. Writes are atomic. **Never** sent to the backend, logged, put in crash reports, fixtures or the repo. Wiped on opt-out, unpair, 401 after refresh, or app uninstall |
| `HingeAuthFlow` | State machine: `Idle → InstallRegistered → OtpRequested → EmailCodeRequired? → Authenticated → ChatReady`, plus `Stopped(reason)` | OTP and email code are **typed by the user** into Wingman's screen. Never read from SMS or notifications. Request at most 3 OTPs per day and never re-request automatically. Token refresh is single-flight, only when expiry is under 7 days, and the new token is persisted **before** any other call (refresh kills the old token, per [hinge-client][hc-core]) |
| `HingeRestClient` | OkHttp client for `prod-api.hingeaws.net` | **Allowlist** of exactly the paths v1 needs: install, the auth paths, refresh, `/message/authenticate`, and optionally `/user/v3/public` and `/content/v2/public`. Recs, rating, likes and profile-update endpoints are **not compiled in**. Header values come from one `HingeClientIdentity` config tied to a reference commit. On 426 it stops and tells the user the connector needs an update; it never auto-bumps |
| `SendbirdChatTransport` | Reads: `my_group_channels` and channel `messages` over Sendbird REST with the Session-Key. Send: WS `MESG`, waiting for the echo carrying our `req_id` | The WS opens **only** for a send or a foreground sync, and closes after an idle period. No persistent background socket and no foreground service. On a guest refusal (900030), re-authenticate once and retry once. Any other refusal is final. A send **timeout is ambiguous**: never resend; reconcile by re-reading the channel |
| `HingeRateLimiter` | Token buckets plus persisted daily counters (see §6d) | Shared by REST and Sendbird. Checked before every network call |
| `EnforcementMonitor` | Circuit breaker fed by every response (see §6e) | A persisted `Stopped(reason)` survives restarts. Only the user can re-enable, after seeing the reason |
| `HingeEventMapper` | Sendbird message to `connector_event.v1` `message.received`: platform `hinge`, `external_message_id` is the Sendbird message id, `external_thread_id` is the channel URL, `sender.external_contact_id` is the Hinge user id, `occurred_at` is the server timestamp | Skip the user's own messages, admin and system messages, and anything that is not a text `MESG`. Likes, comments and new matches are **not** `message.received` (WING-029 taxonomy). Dedup by `(platform, external_message_id)` with a deterministic `event_id`. Text never logged |
| `HingeOutboxExecutor` | Leases instructions via the existing outbox claim (same pattern as `SmsOutboxPoller`) | Before sending, checks opt-in, `Stopped`, rate limits, that the conversation already exists (no first messages and no match openers in v1) and that the lease is valid. Persists the key before the WS write (D-006, one attempt per key). Emits `message.send_acknowledged` with the echoed message id, or `message.send_failed` (`rejected_by_platform` / `timeout` / `not_connected` / `transport_error`, with `retryable=false` for timeout and refusals) |
| `HingeSyncWorker` | WorkManager periodic job: list channels, fetch new messages for changed channels, upload envelopes | Runs at the WorkManager minimum of 15 min or longer, only on an unmetered or any network as configured, and only while opted in and not stopped |
| `HingeCapabilities` | Claims `read` and `send` UNVERIFIED, `draft` per the existing pipeline, and `swipe` / `calendar_write` **UNSUPPORTED** | The server never stores VERIFIED from a client claim (ADR 0007). Autopilot stays unavailable |

Backend: no Hinge-specific code is needed beyond `platform: hinge` connections and the existing
ingest, dispatcher, approval, pause, takeover and outbox. The backend never sees tokens.

### 6b. Login UX (on the phone)

1. Settings > Connections > Hinge (experimental). Shown only in the experimental flavor. The
   ban-risk warning comes first, and the user must tick an acknowledgement and tap "I accept the
   risk".
2. The user enters their phone number. Wingman shows the exact consequence: "Hinge will text you a
   code".
3. The user types the 6-digit code into Wingman. If Hinge asks for an email code (412), the screen
   says so and the user types that code too.
4. Success screen: "Connected (experimental, approval-only). Wingman will check for new messages
   about every 15 minutes. Every reply needs your approval."
5. On any captcha, challenge page, unknown 4xx or "update the app" response: stop, show "Hinge
   blocked this experimental connector; use the notification-assisted mode", and offer that mode.
   Never retry silently.

Logging into Wingman may sign the user out of the official Hinge app on the same phone, or the
reverse. No reference documents this either way, so it is UNVERIFIED and must be recorded in the
live test.

### 6c. Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Already declared. REST and WS |
| `ACCESS_NETWORK_STATE` | WorkManager network constraints |
| `POST_NOTIFICATIONS` (Android 13+) | Tell the user when the connector stops on enforcement or needs re-login |
| *Not requested:* SMS read or receive, accessibility service, foreground service, notification listener | The OTP is typed by the user. No UI automation. No always-on socket. The listener belongs to the separate WING-017 path |

### 6d. Conservative default rate limits

The references publish no limits apart from hinge-ts' recommendations spacing (1.5 s, backoff on
429 and 503, [hinge-ts][hts-recs]). These defaults are deliberately far below human use.

| Operation | Default |
|---|---|
| Background sync (channel list) | Every 15 min or longer (WorkManager minimum), and at most 96 per day |
| Foreground refresh (user opens the inbox) | At most 1 per 60 s |
| Message history fetch | At most 1 per channel per 30 s, at most 20 channels per sync, and only channels whose `last_message` changed |
| All REST + Sendbird REST calls | At most 1 per 2 s with jitter, at most 120 per hour, at most 600 per day |
| Sends (approved replies) | At most 1 per 60 s globally, a random 20 to 90 s delay after approval, at most 5 per conversation per day, at most 20 per day |
| First messages to new matches, likes, comments, swipes | **0** (not implemented in v1) |
| Profile reads | Only for people with an existing conversation, at most 30 per day, cached 7 days |
| OTP requests | At most 3 per day, user-initiated only |
| Token refresh | Single-flight, only when expiry is under 7 days |

### 6e. Stop-on-enforcement signals

Any of these moves the connector to a persisted `Stopped(reason)`. When stopped, it makes **no
calls**, emits `connection.status_changed` (`error`, with a reason code but no secrets), notifies
the user and falls back to notification-assisted mode.

| Signal | Action |
|---|---|
| 401 or 403 after one refresh attempt | Stop. Wipe the session. Require re-login |
| 426 Upgrade Required | Stop: `client_version_retired`. Never auto-bump |
| 429, or any `Retry-After` | Stop for 24 h, then require the user to resume. No exponential retry loop |
| 412 outside the login flow, any captcha or challenge page, an HTML response where JSON was expected | Stop: `challenge_presented`. This is a no-go signal (see §7) |
| Any response or Sendbird error mentioning a ban, suspension, restriction or review | Stop indefinitely. Tell the user to check the official app |
| Sendbird refusal other than one guest re-auth (muted, frozen, banned) | Stop that send. Repeated refusals stop the connector |
| Two or more unexpected 4xx in 1 h, or 3 consecutive 5xx | Stop (5xx: back off 30 min, at most 3 times, then stop) |
| Response cannot be decoded (shape drift) | Stop: `protocol_drift` |
| The user is logged out of the official app, or reports a warning | User-driven stop from settings |

### 6f. What the owner's live test must show (gate from UNVERIFIED to VERIFIED)

Run by the owner or a tester with **their own account on their own phone**. Agents never enter
numbers or codes. Record this in a copy of `experiments/dating_platforms/results_template.md`,
redacted, with the observation JSON validated against
`experiments/dating_platforms/live_test_observation.schema.json`.

| Step | Must show | Fails if |
|---|---|---|
| L0 setup | Experimental build, opt-in warning shown and accepted. Record Hinge app version, Android version, device model, reference commit, date | |
| L1 login | OTP typed by the owner leads to `ChatReady`. Record whether an email code was needed | captcha, challenge, attestation prompt, or 426 (= **BLOCKED**, abort) |
| L2 list | Conversation count equals what the official app shows (count only) | mismatch |
| L3 read | The last 5 messages of one chosen existing conversation match the official app (compare lengths and order, not content in the record) | missing or out-of-order messages |
| L4 ingest | The backend shows `message.received` for them. Re-running sync creates no duplicates | duplicates |
| L5 draft and approval | A draft is generated and **approved in Wingman** | auto-sent without approval |
| L6 send | **One** approved test reply, to a person who agreed to take part, appears in the official Hinge app on both phones. `message.send_acknowledged` carries the platform id | not visible, or sent twice |
| L7 restart | Killing the app mid-flow and relaunching causes no second send | duplicate |
| L8 failure | Airplane mode during a send gives `send_failed`, no retry storm, and the instruction is not re-sent | |
| L9 kill switch | Opt-out wipes the session and makes 0 further Hinge network calls (redacted counter) | any call |
| L10 soak | 7 days of approval-only use within the §6d limits, with no enforcement signal and no warning in the official app | any §6e signal |

Only after L0 to L10 pass may an operator record `read` and `send` as VERIFIED for that app version
(ADR 0007). Autopilot is then a separate, explicit owner decision.

## 7. Order and no-go conditions

**Order:** (1) WING-017 notification-assisted path for all three apps, which ships regardless.
(2) WING-029 Hinge experimental connector, approval-only. (3) WING-027 Tinder API path only if
the Tinder condition below clears. (4) WING-028 Bumble API path only if a working current client
appears.

**No-go conditions.** Any one of them makes the path BLOCKED. Stop and report; do not work around.

1. Any captcha (Arkose or other), device attestation (Play Integrity, DeviceCheck, App Attest),
   request signing or anti-bot challenge in the flow. This is why Tinder (Arkose) and Bumble
   (`X-Pingback` signing, web captcha) are BLOCKED today.
2. Auth that requires the user to copy tokens or cookies out of a browser or app
   (tinder.py, bumble-api). We do not handle pasted credentials.
3. Any need to call the platform from the backend or store tokens off-device (D-021 §3).
4. A reference license that forbids reuse: GPL-3.0 (bumble-api), none (BeeSpy, bumapi). Only
   clean-room protocol facts may be used, and for Bumble even those are moot.
5. Endpoints not documented by a reference. No guessing or probing.
6. Shipping the experimental connector in a **Play Store** build (Device and Network Abuse policy).
   It must be a separate build flavor, sideload or internal testing only, excluded from release
   bundles by a build check.
7. AccessibilityService-driven UI automation of any dating app.
8. Automated swiping, likes, or first messages to new matches (D-021 §4) until separately approved.
9. A Hinge response mentioning a restriction or ban, or any warning in the official app during the
   live test or the soak: stop the program for that platform pending owner review.
10. Applies to the assisted path as well: if the owner's reading of Hinge's terms on
    automatically generated content (§2d item 11) makes AI drafts unacceptable, the Hinge draft
    feature itself needs a product decision.

**Tinder re-open condition:** a maintained, permissively licensed reference with commits within 90
days that documents a phone OTP login with no captcha solving or attestation, plus issues or
commits showing it works.

## 8. Android device test procedure: notification path

Purpose: turn the §2a UNVERIFIED rows into observations per app and version. This needs WING-017's
notification listener and its debug notification inspector (parallel PR; use whatever screen and
log it ships). It uses the tester's own phone and accounts, with matches who agreed to take part.

**Prerequisites**

* Android 13 or later physical phone, and the Wingman debug build containing WING-017.
* The official app from Play: Hinge `co.hinge.app`, Tinder `com.tinder`, Bumble `com.bumble.app`.
  Record the version from Settings > Apps.
* An existing match on each app with a cooperating second person or a second test account owned
  by the tester.
* App notifications on, and message previews on where the app offers it. Record the setting
  value.

**Steps (repeat per app)**

1. Grant notification access: Settings > Notifications > Device & app notifications, or open the
   screen from Wingman. Confirm the inspector shows "listener connected".
2. Lock the screen. Have the partner send `N1` (a neutral one-line text). Then unlock.
3. In the inspector, capture the redacted observation for each notification from the app's
   package. Fields are in `experiments/dating_platforms/notification_observation.schema.json`:
   package, channel id, category, template or style, whether `MessagingStyle` is used, number of
   messages, **lengths** of title and text (never their values), `extras` **key names**,
   group/summary flags, `shortcutId` or `conversation` presence, the list of actions with
   `RemoteInput` present (keys count, `allowFreeFormInput`, semantic action), and whether a
   `contentIntent` is present.
4. Repeat with `N2` sent 10 s later (update vs new notification), then a like, a comment on a
   prompt (Hinge), and a new match. Record each as a separate observation with `event_kind`.
5. Toggle the app's message-preview setting off, if it has one, and repeat `N1`. Record whether
   text disappears.
6. **Reply action test (only if step 3 found a free-form `RemoteInput` action):** from the WING-017
   debug screen, send the fixed text `wingman test 1` through that action **once**. Confirm it on
   both phones in the official app. Never use this to reach anyone who has not agreed.
7. Fire `contentIntent` from the inspector, if offered, and record whether it opens the exact
   conversation, the app home, or nothing.
8. Revoke notification access, and confirm the inspector reports "disconnected" and that capture
   stops.

**What never to capture:** message text, names, photos, phone numbers or ids in clear. Hash ids
with a per-run salt if correlation is needed. No screenshots containing chat content.

**Pass/fail per app**

| Row | PASS (becomes VERIFIED for that app version) | FAIL / outcome |
|---|---|---|
| read | Step 2 notification carries non-empty message text (length > 0) attributable to one conversation | text hidden or generic ("New message"): read = UNSUPPORTED with previews off; record the setting |
| detected reply action | Step 3 finds a free-form `RemoteInput` action on chat-message notifications | none: reply = UNSUPPORTED from notifications (assisted copy and open only) |
| reply | Step 6 reply appears in the official app on both phones, exactly once | not delivered or duplicated: UNSUPPORTED or bug |
| event taxonomy | Likes, comments and matches are distinguishable from chat messages by channel, category or other fields | not distinguishable: WING-029 must not draft for ambiguous events |
| open conversation | Step 7 opens the exact thread | opens home only: record it |

Record results in `experiments/dating_platforms/results_template.md` (one copy per app and run),
and commit only redacted JSON observations that validate against the schema.

## 9. Sources

Official terms and help:

* `hinge-terms`: https://hinge.co/terms (effective 2025-08-25; §2c items 16, 22 and 23; §2d item 11; §§4, 6, 9)
* `hinge-automation`: https://help.hinge.co/hc/en-us/articles/49657802257683-Note-on-Third-Party-Automated-Tools
* `hinge-dyd`: https://help.hinge.co/hc/en-us/articles/360011235813-How-do-I-request-a-copy-of-my-personal-data
* `hinge-privacy`: https://help.hinge.co/hc/en-us/articles/360004792234-How-can-I-make-a-Privacy-Request
* `tinder-terms`: https://policies.tinder.com/terms/intl/en/ (effective 2026-03-05; §2c items 16, 22 and 23; §§4, 9)
* `tinder-cg`: https://tinder.com/community-guidelines
* `tinder-dyd`: https://www.help.tinder.com/hc/articles/115005626726-How-do-I-request-a-copy-of-my-personal-data
* `bumble-terms`: https://bumble.com/en/terms (updated 2026-06-17; §§1, 3, 16)
* `bumble-login`: https://support.bumble.com/hc/en-us/articles/28533989291421-Signing-up-and-logging-in
* `bumble-dyd`: https://support.bumble.com/hc/en-us/articles/28783199493917-Requesting-your-data
* Play listings (package ids): https://play.google.com/store/apps/details?id=co.hinge.app , https://play.google.com/store/apps/details?id=com.tinder , https://play.google.com/store/apps/details?id=com.bumble.app

Android and Play:

* `android-nls`: https://developer.android.com/reference/android/service/notification/NotificationListenerService
* `android-reply`: https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action
* `android-remoteinput`: https://developer.android.com/reference/android/app/RemoteInput
* `android-pm`: https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)
* `android-queries`: https://developer.android.com/training/package-visibility
* `aa-sensitive`: https://www.androidauthority.com/android-15-sensitive-notifications-3416414
* `play-a11y`: https://support.google.com/googleplay/android-developer/answer/10964491
* `play-perms`: https://support.google.com/googleplay/android-developer/answer/9888170
* `play-dna`: https://support.google.com/googleplay/android-developer/answer/9888379

Reference repositories (read only, pinned):

* `hrs`: https://github.com/f0rr0/hinge-rs (main @ `f79097aa83`)
  * `hrs-fix`: https://github.com/f0rr0/hinge-rs/commit/cffe397bd5905072a619fb041d6fe779a543a2f3
  * `hrs-auth`: https://github.com/f0rr0/hinge-rs/blob/main/src/client/auth.rs
  * `hrs-10`: https://github.com/f0rr0/hinge-rs/issues/10
* `hc`: https://github.com/Stupidoodle/hinge-client (master @ `07f35211c3`)
  * `hc-core`: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/_core.py
  * `hc-chat`: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/resources/chat.py
  * `hc-rating`: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/resources/rating.py
* `hts`: https://github.com/wrsrsh/hinge-ts (main @ `15a3c0c92a`)
  * `hts-fallback`: https://github.com/wrsrsh/hinge-ts/commit/26b6399357375be6fdf0ea957e7f6b9eeb10327e
  * `hts-recs`: https://github.com/wrsrsh/hinge-ts/blob/main/docs/recommendations.md
* `hmcp`: https://github.com/Kuberwastaken/hinge-mcp (main @ `82975f01ff`)
* `fb`: https://github.com/fbessez/Tinder (master @ `bc8f81e509`)
  * `fb-readme`: https://github.com/fbessez/Tinder/blob/master/README.md
  * `fb-issues`: https://github.com/fbessez/Tinder/issues
  * `fb-pr117`: https://github.com/fbessez/Tinder/pull/117
* `tp`: https://github.com/rednit-team/tinder.py (master @ `ace14b3952`)
  * `tp-match`: https://github.com/rednit-team/tinder.py/blob/master/tinder/entities/match.py
* `bb`: https://github.com/henrydatei/bumble-api (main @ `7f282c64cd`)
  * `bb-api`: https://github.com/henrydatei/bumble-api/blob/main/api.py
* `beespy-repo`: https://github.com/Othmane-ElAlami/BeeSpy-Enhanced (master @ `4cfef58c5a`)
  * `beespy`: https://github.com/Othmane-ElAlami/BeeSpy-Enhanced/blob/master/content.js
* `bumapi`: https://github.com/hpats/bumapi (master @ `c26197cebe`)

[hinge-terms]: https://hinge.co/terms
[hinge-automation]: https://help.hinge.co/hc/en-us/articles/49657802257683-Note-on-Third-Party-Automated-Tools
[hinge-dyd]: https://help.hinge.co/hc/en-us/articles/360011235813-How-do-I-request-a-copy-of-my-personal-data
[hinge-privacy]: https://help.hinge.co/hc/en-us/articles/360004792234-How-can-I-make-a-Privacy-Request
[tinder-terms]: https://policies.tinder.com/terms/intl/en/
[tinder-cg]: https://tinder.com/community-guidelines
[tinder-dyd]: https://www.help.tinder.com/hc/articles/115005626726-How-do-I-request-a-copy-of-my-personal-data
[bumble-terms]: https://bumble.com/en/terms
[bumble-login]: https://support.bumble.com/hc/en-us/articles/28533989291421-Signing-up-and-logging-in
[bumble-dyd]: https://support.bumble.com/hc/en-us/articles/28783199493917-Requesting-your-data
[android-nls]: https://developer.android.com/reference/android/service/notification/NotificationListenerService
[android-reply]: https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action
[android-remoteinput]: https://developer.android.com/reference/android/app/RemoteInput
[android-pm]: https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)
[android-queries]: https://developer.android.com/training/package-visibility
[aa-sensitive]: https://www.androidauthority.com/android-15-sensitive-notifications-3416414
[play-a11y]: https://support.google.com/googleplay/android-developer/answer/10964491
[play-perms]: https://support.google.com/googleplay/android-developer/answer/9888170
[play-dna]: https://support.google.com/googleplay/android-developer/answer/9888379
[hrs]: https://github.com/f0rr0/hinge-rs
[hrs-fix]: https://github.com/f0rr0/hinge-rs/commit/cffe397bd5905072a619fb041d6fe779a543a2f3
[hrs-auth]: https://github.com/f0rr0/hinge-rs/blob/main/src/client/auth.rs
[hrs-10]: https://github.com/f0rr0/hinge-rs/issues/10
[hc]: https://github.com/Stupidoodle/hinge-client
[hc-core]: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/_core.py
[hc-chat]: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/resources/chat.py
[hc-rating]: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/resources/rating.py
[hts]: https://github.com/wrsrsh/hinge-ts
[hts-fallback]: https://github.com/wrsrsh/hinge-ts/commit/26b6399357375be6fdf0ea957e7f6b9eeb10327e
[hts-recs]: https://github.com/wrsrsh/hinge-ts/blob/main/docs/recommendations.md
[hmcp]: https://github.com/Kuberwastaken/hinge-mcp
[fb]: https://github.com/fbessez/Tinder
[fb-readme]: https://github.com/fbessez/Tinder/blob/master/README.md
[fb-issues]: https://github.com/fbessez/Tinder/issues
[fb-pr117]: https://github.com/fbessez/Tinder/pull/117
[tp]: https://github.com/rednit-team/tinder.py
[tp-match]: https://github.com/rednit-team/tinder.py/blob/master/tinder/entities/match.py
[bb]: https://github.com/henrydatei/bumble-api
[bb-api]: https://github.com/henrydatei/bumble-api/blob/main/api.py
[beespy-repo]: https://github.com/Othmane-ElAlami/BeeSpy-Enhanced
[beespy]: https://github.com/Othmane-ElAlami/BeeSpy-Enhanced/blob/master/content.js
[bumapi]: https://github.com/hpats/bumapi
