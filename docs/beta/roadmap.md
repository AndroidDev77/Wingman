# Follow-up roadmap after the private beta

Owner: WING-026. Prioritized by what blocks a trustworthy product, based on the problems observed
in WING-025 and while preparing this beta (`docs/e2e_results.md`, `docs/DECISIONS.md` open issues,
[`known_limitations.md`](known_limitations.md)). Update it from beta feedback and incidents each
week ([`release_checklist.md` §7](release_checklist.md#7-after-each-beta-week)).

Rule for every item: nothing is called done, and no label moves to VERIFIED, without a
reproducible run on the real target (`AGENT_RULES.md`).

## P0: required before anything beyond this limited beta

### 1. G5: verify SMS on physical devices (I-003)

- **Why first:** it is the only real channel. Without it the product cannot send a real reply
  automatically, and **no paid launch is allowed** ([go/no-go](release_checklist.md#0-go--no-go-summary)).
- **Work:** a human with an Android phone (SIM), a second "peer" phone and `adb` runs
  `docs/integrations/android_sms.md` §7: D1 to D12, B1 to B6, P1 to P8, recorded as
  `## Device run YYYY-MM-DD`. Minimum for the operator `verify` CLI: D3, D4, B3. Repeat on at least
  two manufacturers (e.g. a Pixel and a Samsung) and one Android 13+ device (restricted settings).
- **Then:** an operator runs `python -m app.services.connections verify ... --evidence <link>` for
  `read` and `send`; update G5 in `docs/STATUS.md`. `scripts/release/check.sh --paid` stops refusing
  only when that row is no longer blocked.
- **Go/no-go:** go for SMS autopilot opt-in for beta testers only after the device runs pass and
  two beta weeks with zero S1 incidents ([incident classes](feedback_form.md#incident-an-unwanted-or-wrong-send)).
  No-go if any duplicate or unapproved send is seen on a device.

### 2. Periodic Android sync (I-024)

- **Why:** today an approved SMS reply waits until the app is opened; a claim that is not completed
  within 120 s becomes "outcome unknown". On real phones this will be the most visible beta problem
  and makes autopilot reply delays meaningless.
- **Work:** WorkManager periodic worker (15 min minimum, network constraint) plus an expedited
  one-off sync after an approval; the dependency is already in the build. Include it in the device
  checklist (B-series rerun).
- **Go/no-go:** must ship before SMS autopilot is offered to anyone.

### 3. Choose an identity provider (I-014)

- **Why:** the dev-stub token is a shared-secret, debug-build-only sign-in; it cannot leave a closed
  beta, and it forces the backend to run with `WINGMAN_ENV=dev`.
- **Work:** pick an OIDC IdP; set `WINGMAN_AUTH_ISSUER/AUDIENCE/JWKS_URL`; configure the Android
  `wingman.oidc.*` properties; run the device checklist in `docs/android_auth.md`; then ship a
  **release** APK and run the backend with `WINGMAN_ENV=prod` (no demo router).
- **Go/no-go:** required for any public or paid release; also the trigger to turn off the dev stub.

### 4. Real-LLM evaluation (I-009, I-026)

- **Why:** every draft observed so far came from the deterministic `[dev mock]`. Draft quality,
  memory extraction and stage classification with a real model are UNVERIFIED, and the content
  guardrails are only unit-tested.
- **Work:** call a real OpenAI-compatible endpoint once (smoke); build an offline evaluation set of
  fictional conversations per intent mode (including adversarial ones: unknown user facts,
  explicit requests, "stop texting me", one-sided escalation); score groundedness, invented facts,
  `no_send`/`ask_user` correctness, and `confirmed`-stage precision; add a scripted adversarial mock
  handler so `tests/e2e` exercises the guardrails (I-026). Decide on implementing the Anthropic
  provider or not.
- **Go/no-go:** no tester gets real-model drafts until the evaluation shows zero invented personal
  facts and zero unsupported escalations on the adversarial set; `confirmed` must have no false
  positives (it drives the date metric).

### 5. Analytics and cost (WING-022)

- **Why:** the beta metrics are only partly measurable from the audit log
  ([`metrics.md`](metrics.md#what-exists-versus-what-needs-wing-022)); LLM cost is computed but not
  stored; there are no alerts.
- **Work:** WING-022 as ticketed: usage events (draft requests vs automatic drafts, approvals,
  sends, connector failures, dates proposed/confirmed), persisted LLM usage into `usage_events`,
  dashboard API that separates proposed from confirmed dates, redacted structured logs.
- **Go/no-go:** required before a paid launch (cost per user must be known).

## P1: product gaps testers will hit

| # | Item | Issue | Notes |
|---|---|---|---|
| 5b | Deadlock under concurrent duplicate ingest | (new, found on PR #36 CI) | X1 failed twice with Postgres `DeadlockDetectedError` in `get_or_create_for_thread` (HTTP `500`, nothing stored, retried by the device); passed on the third run. Retry the ingest transaction on serialization/deadlock errors or lock per thread; add a repeated-run stress job. Fix before SMS autopilot ([known_limitations.md](known_limitations.md#backend-and-operations)) |
| 6 | Mark-read | I-022 | Additive contract route; `ConversationRepository.mark_read` exists. Unread counts only grow today |
| 7 | Self-service account deletion and export | (new, this beta) | Today the operator deletes by SQL ([checklist §5](release_checklist.md#5-privacy-and-data-handling)). Needs an additive contract operation and an audit trail |
| 8 | Hosted deployment | WING-023 follow-up | Run the Azure sample (or another host) for real and update `docs/deployment.md`'s verification table; worker app included. Blocked on the IdP for `prod` |
| 9 | Audited capability verification | I-019 | The `verify` CLI writes no audit row; make verification an audited admin action |
| 10 | Connection capability in conversation detail | I-023 | Accepted for now; revisit if the app needs extra calls |
| 11 | MMS | (limit) | Required for any public default-SMS app (Play policy); large. Until then, SMS stays a test-device feature |
| 12 | Dual-SIM reply routing | (limit) | Reply from the SIM the message came in on; needs a dispatcher field |
| 13 | Compose UI test harness | I-027 | Only if Compose-rule tests of the shell are wanted |
| 14 | `mypy tests/db` | I-012 | Low |

## P2: post-MVP tickets, with go/no-go notes

| Ticket | What | Go/no-go note |
|---|---|---|
| **WING-017** Notification direct-reply spike | Which Android apps expose notification text and a RemoteInput reply action | Feasibility only. Start after G5. Must never claim a send where no RemoteInput action exists; Tinder/Bumble/Hinge stay UNVERIFIED until device-tested. No accessibility-based tapping |
| **WING-018** Date proposals and calendar | `DateProposal` lifecycle, calendar adapter, approval before event creation | Go after real-LLM evaluation (#4), because it depends on `confirmed` being trustworthy. A proposed time is never confirmed; no invitation without matching authorization |
| **WING-019** Compatibility scoring and discovery | Evidence-linked scores on imported or fixture profiles; the Discovery tab | Low priority. No scraping, no automated swiping unless a connector advertises `swipe` (none does) |
| **WING-020** macOS iMessage companion | Feasibility of a local Mac companion | Research spike, off the critical path. Must report tested macOS versions or mark UNVERIFIED; no private APIs presented as official |
| **WING-021** Tinder / Bumble / Hinge | **Research only**: a capability matrix with evidence | Do not build integrations. A "no support" conclusion is a valid outcome. No credential harvesting or simulated success. Until it concludes otherwise, these platforms stay UNSUPPORTED in all docs and marketing |
| **WING-024** Subscriptions and entitlements | Plans, server-enforced entitlements, billing stub | Build after G5 and WING-022. **Paid autopilot cannot be sold while no connector has VERIFIED send.** No live payment flow until a provider is tested |

## Sequencing summary

```
now ──► beta (limited, SMS approval-only, dev token, mock or evaluated LLM)
         │
         ├─ P0.1 G5 device runs ──► P0.2 periodic sync ──► SMS autopilot opt-in (beta only)
         ├─ P0.3 IdP ──► release APK + prod backend
         ├─ P0.4 real-LLM eval ──► real drafts for testers ──► WING-018
         └─ P0.5 WING-022 ──► WING-024 ──► paid launch (only if G5 met: one reliable real connector)
```
