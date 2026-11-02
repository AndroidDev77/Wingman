# Profile discovery and swiping (WING-031)

Status: **review workflow delivered; no automatic swipe exists or was demonstrated.**

Decisions: D-021 (experimental connectors, no automated swiping before a passed live test and the
owner's approval), D-023 (Hinge experimental API only; Tinder and Bumble API paths BLOCKED),
D-025 (contract 1.3.1: ranked queue and `include_excluded`), D-026 (experimental build).
Contract: `contracts/openapi.yaml` 1.3.1, ADR 0008 §7 (profiles, evaluations, actions, the execute
gate). No contract change in WING-031.

## 1. Answer first: was an automatic swipe demonstrated?

**No.** Nothing in this repository has liked, passed, superliked or otherwise swiped on Tinder,
Bumble or Hinge, on a real account or device or in any other way. No connector advertises a
`VERIFIED` `swipe`; no executor exists that could carry out a queued action; and contract 1.3.1
has no route for a device to report that a swipe happened. Every test that reaches `queued` does
so on a connection whose `VERIFIED` swipe is a **test fixture** (an operator's evidence played by
the test), never a real platform.

So WING-031 delivers the **review workflow**: profiles the user brings in (pasted by hand, or the
debug fixture feed) are ranked and explained; the user records Like / Pass / Skip in Wingman; a
separate "Execute on <platform>" control stays disabled with the reason; auto-swipe stays off and
cannot be turned on anywhere.

## 2. Capability table

Per platform and path. `VERIFIED` = reproduced on a real account/device with operator evidence
(none). `UNVERIFIED` = a reference documents it, not run by us. `UNSUPPORTED` = not available on
that path, or excluded by policy. `BLOCKED` = a no-go condition of the matrix (§7) applies; in the
capability document a blocked capability is stored as `UNSUPPORTED` with the blocker in `notes`
(ADR 0008 §8). "Wingman" is what the shipped code does; "reference" is what an outside client
documents.

### 2a. Notification-assisted path (WING-017/027/028/029; every build)

| Capability | Tinder | Bumble | Hinge | Evidence |
|---|---|---|---|---|
| `profile_read` | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | Notifications carry at most a name and an avatar ([matrix §2a](dating_platform_matrix.md#2a-notification-assisted-path-wing-017-listener--assisted-copyopen-app-fallback)); `NotificationCapabilities` advertises `profile_read` UNSUPPORTED always |
| like | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | Not a notification action; AccessibilityService automation is a no-go ([matrix §3c](dating_platform_matrix.md#3c-play-policy), [Play accessibility policy][play-a11y]) |
| pass | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | Same |
| superlike / equivalent (Super Like, SuperSwipe, Rose) | UNSUPPORTED | UNSUPPORTED | UNSUPPORTED | Same |

### 2b. Experimental unofficial-API path (D-021; experimental build only, opt-in, approval-only)

| Capability | Tinder | Bumble | Hinge (WING-029) |
|---|---|---|---|
| `profile_read` | **BLOCKED** (auth: 2018-era SMS login reported broken, newer flow behind Arkose captcha; [fbessez README][fb-readme], [PR #117][fb-pr117]) | **BLOCKED** (pasted web cookie plus a reverse-engineered request signature; GPL-3.0 reference; [bumble-api][bb-api]) | Reference **UNVERIFIED** (`/user/v3/public`, `/content/v2/public`, [matrix §2b](dating_platform_matrix.md#2b-experimental-unofficial-api-path-d-021-on-device-opt-in-approval-only)). Wingman **UNSUPPORTED**: the profile routes are outside the connector's 9-endpoint allowlist and are refused before the network (D-026, `HingeNetwork`) |
| like | **BLOCKED** (auth; was `/like/{id}`, [fbessez README][fb-readme]) | **BLOCKED** (auth + signing; vote `message_type 80`, [BeeSpy content.js][beespy], no license) | Reference **UNVERIFIED** (`/rate/v2/initiate`, [hinge-client rating.py][hc-rating]). Wingman **UNSUPPORTED**: rating and like endpoints excluded by policy (D-021, D-023) and not in the allowlist |
| pass | **BLOCKED** (auth; was `/pass/{id}`) | **BLOCKED** (same as like) | Same as like: reference UNVERIFIED, Wingman **UNSUPPORTED** |
| superlike / equivalent | **BLOCKED** (auth; Super Like not evaluated further) | **BLOCKED** (SuperSwipe; same blockers) | Rose: Wingman **UNSUPPORTED** (rating endpoints excluded); not evaluated further |

Confidence that any automatic swipe works through Wingman today: **none** (nothing implemented,
nothing run). The ticket's handoff matrix (read / draft / send / profile / swipe) per platform is in
the PR description; the swipe and profile columns are this table.

## 3. Platform blockers, precisely

1. **All three, policy:** no official profile or swipe API exists; Hinge and Tinder require
   written consent for any API use, Bumble allows only published interfaces and publishes none
   ([matrix §3](dating_platform_matrix.md#3-terms-of-service-play-policy-and-legitimate-data-paths)).
   Hinge's help center says building or distributing an automation tool will get the account
   banned ([Hinge help][hinge-automation]); Tinder ([terms][tinder-terms]) and Bumble
   ([terms][bumble-terms]) forbid bots and scripts.
2. **All three, D-021 / matrix §7 no-go 8:** automated swiping, likes and first messages are not
   allowed until a live test passes and the owner separately approves it. No such approval exists.
3. **All three, Play:** AccessibilityService-driven UI automation is a no-go ([Play policy][play-a11y]);
   unofficial-API code is excluded from any Play build ([Play DNA policy][play-dna]); WING-031
   moved it into a source set only the experimental build compiles.
4. **Tinder, technical:** no maintained reference; the documented SMS login is from 2018 and
   reported broken (issues #156, #157), and the newer protobuf attempt hits an Arkose captcha,
   email OTP and temporary bans (no-go 1). Re-open condition in matrix §7. Owner confirmation
   pending (I-032).
5. **Bumble, technical:** only a GPL-3.0 client (no-go 4) that needs a pasted browser cookie (no-go
   2) and a reverse-engineered request signature (no-go 1); the only auto-swipe reference is an
   unlicensed browser extension. Owner confirmation pending (I-032).
6. **Hinge, technical and policy:** a current reference documents rating (`/rate/v2/initiate`) and
   profile reads, but WING-029 deliberately excluded every rating, like and profile route from the
   allowlist (D-021, D-023, D-026); the connector is approval-only and every capability is
   UNVERIFIED until the owner's live test L0 to L10 and `verify-experimental` (not run). Hinge's
   terms on automatically generated content (I-030) and the experimental build's distribution
   (I-031) are still open owner decisions.
7. **Contract:** 1.3.1 has no device-reported outcome route for swipes (ADR 0008 Consequences). A
   real executor would need an additive contract change (a `profile_action.outcome` event or
   route, with `executed` / `failed` / `unknown` and the D-006 one-attempt-per-key rule).

To ever enable automatic swiping on one platform, all of these must change: a permitted path
(written consent or an official API), the owner's approval under D-021, an executor on the device,
the outcome contract (7), a reproduced real-account test recorded by the operator as `swipe:
VERIFIED` evidence, and then the user turning auto-swipe on for that connection.

## 4. What the backend does (`backend/app/discovery/`)

The profile routes use `DiscoveryService` (a `ProfileService`). Import, ranking and evaluation are
WING-019's (`app/services/profiles.py`), unchanged.

- **Decisions** (`recordProfileDecision`): recorded only, `execution: not_executable`; one
  `ProfileAction` per `Idempotency-Key` (a repeat replays it with `Idempotent-Replayed: true`, a
  different body is `422 idempotency_key_mismatch`). Audit `profile_decision_recorded` (IDs and the
  decision only). Recording is the user's own act, so a global pause does not stop it.
- **Decision rate limits:** 30 per rolling minute and 1000 per rolling day per user, counted from
  stored rows. Over the limit: `429 rate_limited` with `Retry-After` (covered by the operation's
  `default` error response). Checked only for a new key: a replay is never limited, and a limited
  request is not stored, so the same key works after the window.
- **Execute gate** (`requestProfileActionExecution`): every ADR 0008 check (not skip; a named,
  paired and connected connection; `swipe` VERIFIED; auto-swipe on; not experimental-unverified;
  not paused; same platform; not stale; no other live action), then two WING-031 checks, both
  within the contract's `422 swipe_execution_disabled`:
  - **superseded decision:** the profile was imported again after the decision (a provider update
    or a new manual import replaced its content), so the user must decide again;
  - **execution limits per connection:** at most 25 actions accepted per rolling day and at least
    30 s between two (every action that passed the gate counts, whatever happened next).
  Every refusal is audited as `policy_blocked` with `reason_code: swipe_execution_disabled` and
  `details` = profile and action IDs, the decision and the failed check (never profile content).
  The refusal is stored for idempotent replay and committed with the audit row (ADR 0004); a
  replay adds no second audit row.
- **Invalidation:** `DiscoveryService.invalidate_profile` is the provider update path: a source
  that reports a profile changed or gone sets `profiles.invalidated_at` (idempotent). The profile
  shows `stale: true` (the Android stale badge) and is never executed. Re-importing it clears the
  flag (fresh content) and supersedes earlier decisions as above. No connector calls it today
  because no connector has `profile_read`.
- **Preference changes** never touch recorded decisions or earlier evaluations: WING-019 appends a
  new evaluation tagged with the new `preferences_version` for undecided profiles only (tested).
- **Lifecycle after `queued`: none, by design.** No executor exists and no outcome route exists,
  so nothing moves an action to `executed`, `failed` or `unknown`; `queued` is reachable only after
  an operator records a VERIFIED swipe for a connection and the user turns auto-swipe on, which
  has never happened. A missing acknowledgment can therefore never be shown as done.

## 5. What the Android app does (`features/discovery/`)

- **Discovery** tab (`Destination.Discovery`): the ranked queue with the score (or "Not scored"),
  the reasons and the profile fields each cites ("from: smoking"), what is not stated, the
  confidence, the stale badge, and "Show excluded" (`include_excluded=true`) with the dealbreaker.
- **Like / Pass / Skip** record the decision with one idempotency key per tap (a second tap while
  one runs is dropped; a retry after a network error reuses the key). Status reads "Decision
  recorded (Like) · Not executable on Hinge". No label ever says something was liked, passed or
  swiped on a platform (tested over every decision, execution state and platform).
- **Execute on <platform>** is a separate control, disabled with the reason unless the server's
  stored state could pass the gate (no connection, swipe not VERIFIED, experimental, auto-swipe
  off, stale, skip, demo profile). Even when enabled, success reads "Queued for Hinge, not done
  yet". A gate refusal reads "Not executable on Hinge: <the server's check>".
- **Add a profile:** manual import (platform, name, bio, `Prompt | Answer` lines, `name: value`
  lines); only what the user typed is sent, nothing is inferred and photos are never read. One key
  and one observed time per submitted form, so a retry replays. **Add demo profiles (debug)**
  imports the six fictional `mock` fixtures through the same route (debug builds only).
- **Settings > Auto-swipe:** one toggle per paired dating connection, disabled and explained unless
  the server stores `swipe: VERIFIED` (none does); turning it off always works.

## 6. Experimental source set (D-023 / D-026 follow-up)

`integrations/hinge/**` except `notifications/` now lives in `android/app/src/experimental/java`,
its tests in `src/testExperimental/java`, and the activity declaration in
`src/experimental/AndroidManifest-entries.xml`; `build.gradle.kts` adds them only with
`-Pwingman.experimental=true`, and otherwise adds `src/standard/java` (a no-op
`ExperimentalModules`). Proof that a default build contains none of it: the Gradle task
`checkNoExperimentalClasses` (runs before every non-experimental `testDebugUnitTest`) and the JVM
test `ExperimentalSourceSetTest`. Details in [hinge.md §3](hinge.md#3-experimental-build-integrator).

## 7. Tests

- Backend `backend/tests/discovery/` (real PostgreSQL): queue from the fixture feed (ranked,
  explained, citations only of stated fields), show-excluded, preference change without
  contamination, one action per key, two likes never both queued, unsupported swipe `422` with one
  audit row and a replay, experimental-unverified refusal, device offline / auto-swipe turned off /
  unpaired, no outcome route (`queued` is the end), pause, skip, invalidation and superseded
  decisions, minute and day decision limits, execution cap and spacing, private fields and audit
  redaction, cross-user isolation (including per-user limits). Plus the existing WING-019 and
  ADR 0008 suites, unchanged.
- Android JVM: `DiscoveryStateTest`, `AutoSwipeStateTest`, `ProfileEndpointsTest`
  (MockWebServer), `ProfileContractExamplesTest`, `ExperimentalSourceSetTest`.

No test used a real account, device or platform.

[play-a11y]: https://support.google.com/googleplay/android-developer/answer/10964491
[play-dna]: https://support.google.com/googleplay/android-developer/answer/9888379
[hinge-automation]: https://help.hinge.co/hc/en-us/articles/49657802257683-Note-on-Third-Party-Automated-Tools
[tinder-terms]: https://policies.tinder.com/terms/intl/en/
[bumble-terms]: https://bumble.com/en/terms
[hc-rating]: https://github.com/Stupidoodle/hinge-client/blob/master/src/hinge/client/resources/rating.py
[fb-readme]: https://github.com/fbessez/Tinder/blob/master/README.md
[fb-pr117]: https://github.com/fbessez/Tinder/pull/117
[bb-api]: https://github.com/henrydatei/bumble-api/blob/main/api.py
[beespy]: https://github.com/Othmane-ElAlami/BeeSpy-Enhanced/blob/master/content.js
