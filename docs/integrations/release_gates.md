# Release feature gates (WING-032)

Owner: WING-032. One committed file decides how every automation feature may be released:
[`config/release/feature_gates.json`](../../config/release/feature_gates.json). Nothing in it is
autonomous today, because nothing has been verified on a real device (see
[`cross_platform_test_matrix.md`](cross_platform_test_matrix.md) §1 and §3).

## 1. Modes

| Mode | UI / release-notes tag | Meaning |
|---|---|---|
| `disabled` | **Not available** | Not offered. The backend refuses it whatever the user selects. |
| `assisted_only` | **Assisted only** | Wingman drafts; the user approves every send (SMS) or sends by hand (notification platforms, discovery decisions). |
| `experimental` | **Experimental** | Opt-in, approval on every send, not in the standard release channel (sideloaded experimental build, or the unsigned Mac companion). |
| `autonomous` | **Autonomous** | Wingman may act without per-item approval. Allowed only with VERIFIED device evidence (§3). |

## 2. Current gates

| Feature | Mode | Backend behaviour checked by P7 | Main blocker |
|---|---|---|---|
| `sms.approved_reply` | assisted_only | autopilot selected -> approval (`capability_unverified`) | I-003 (G5) |
| `sms.autopilot` | disabled | approval, never a send | I-003, I-024, D-019 |
| `tinder.notification_assisted_reply` | assisted_only | assisted delivery, never a send | N1-N13/T1-T8 not run; I-032 |
| `bumble.notification_assisted_reply` | assisted_only | assisted delivery | as Tinder; B1-B2 |
| `hinge.notification_assisted_reply` | assisted_only | assisted delivery | N1-N13 not run (I-030 closed by D-031: drafts allowed, UNVERIFIED, experimental) |
| `notification.direct_reply` | disabled | no instruction ever exists (send UNSUPPORTED) | N9/N13 not run; I-033 |
| `dating.autopilot` | disabled | assisted delivery | Wingman cannot send in dating apps |
| `hinge.experimental_api` | experimental (experimental build only) | approval, autopilot refused | L0-L10 not run; I-031; never a Play build (drafts allowed per D-031, approval-only) |
| `imessage.companion_reply` | experimental (unsigned companion) | approval | I-035, I-034 |
| `imessage.autopilot` | disabled | approval | I-035 |
| `discovery.review` | assisted_only | execute `422 swipe_execution_disabled` | no `profile_read` anywhere |
| `swipe.execute` | disabled | execute `422` | D-027 (no executor, no outcome route) |
| `swipe.auto_swipe` | disabled | settings `422` | D-027 |
| `calendar.date_proposals` | disabled | no calendar or date route served | WING-018 not built |

## 3. Enforcement

1. **`scripts/release/check_feature_gates.py`** (stdlib): fails when a gate's `ui_label` does not
   carry its mode's tag, when a feature is `autonomous` without an evidence record with
   `result: PASSED ON DEVICE` and `verification: VERIFIED` for **every** capability in its
   `requires_verified` (with a date and an `evidence_ref` file naming the record id), when its
   matrix row does not say `PASSED ON DEVICE`, when a record claims `VERIFIED` without a device
   pass, or when a cited record does not exist. Unit tests: `tests/release/test_feature_gates.py`.
2. **`scripts/release/check.sh`** runs it first (also with `--dry-run`) and stops with exit 4
   before any other check; it now also runs `scripts/e2e/platforms/run.sh` as check 5/5.
3. **`scripts/release/build.sh`** refuses to plan or build (exit 4) on a violation and writes
   `feature_gates_sha256` and the non-disabled `feature_gates` into `BUILD_INFO`. It never passes
   `-Pwingman.experimental=true`, so the release APK contains no unofficial-API code
   (`checkNoExperimentalClasses`, D-027).
4. **`tests/e2e/platforms/test_p7_release_gates.py`** cross-checks the file against the running
   backend: for each gate it pairs the platform with its own device claim, selects autopilot, and
   asserts the observed outcome equals `backend_check.expect`; a non-autonomous gate that sees an
   autonomous send, or an autonomous gate whose backend still asks for approval, fails CI.

The app needs no new flag: its labels come from the capabilities the server stores
(Settings > Connections, Send by hand, Discovery), and the server never stores a client's
`VERIFIED` (D-016). The gate file is the release-side statement of the same facts; P7 keeps the
two from drifting. No backend or app file reads the gate file (no production code changed).

## 4. Promoting a feature

1. Run the platform's manual script (`cross_platform_test_matrix.md` §5) on a real device or
   account, record the evidence log entry (§7) with redacted artifacts.
2. The operator runs `python -m app.services.connections verify ... --evidence
   "cross_platform_test_matrix.md R-<run id>"` (or `verify-experimental`) for that connection.
3. A reviewed PR adds the `evidence_records` entry (`PASSED ON DEVICE`, `VERIFIED`, date,
   `evidence_ref` naming the run id), updates the matrix row in §3a, and only then changes the
   feature's `mode`. `check_feature_gates.py` and P7 must both pass.
4. Autonomy stays a separate owner decision even after verification (D-019, D-021).

## 5. Rollout notes

- **Private beta (v0.1.0-beta.x):** SMS with approval on every reply; Tinder, Bumble and Hinge
  notification-assisted drafts behind the per-app reading opt-in, labeled UNVERIFIED (Hinge also
  labeled experimental: the owner accepted its terms risk, D-031); discovery review only. No autopilot anywhere except the dev `mock` simulator.
- **Not in the beta build:** the Hinge experimental connector (experimental build only, owner
  live test first), the iMessage companion (until signed and run on a Mac), direct reply from
  notifications, swipe execution, calendar.
- **Hinge drafts on any path:** **GO** since 2026-11-03 (D-031, closes I-030). The owner accepts
  Hinge's terms on automatically generated content as a risk for this experimental, pre-product
  stage. Notification path: assisted only (you send in Hinge), UNVERIFIED. Experimental connector:
  approval on every reply, still NO-GO for any release (I-031, sideloaded build only, never Play).
  No autopilot, no swipes, off by default; every Hinge capability UNVERIFIED until the owner's
  live tests.
- **Paid launch:** still refused by `check.sh --paid` while G5 is ⛔.

## 6. Proposed edits to `docs/beta/known_limitations.md` (owned by WING-026; for the integrator)

The supported-platform table there predates WING-027 to 031 and says "Not built" for platforms
that are now built and UNVERIFIED. Proposed replacement rows:

| Platform / feature | Read | Send | Beta status | Notes and evidence |
|---|---|---|---|---|
| Tinder (notifications) | UNVERIFIED | UNSUPPORTED (assisted only: you send by hand) | **Assisted only**, opt-in per app | WING-027; mock E2E P2 (`cross_platform_test_matrix.md`); never run on a phone |
| Bumble (notifications) | UNVERIFIED | UNSUPPORTED (assisted only) | **Assisted only**, opt-in per app | WING-028; as Tinder |
| Hinge (notifications) | UNVERIFIED | UNSUPPORTED (assisted only) | **Assisted only, experimental**, opt-in per app (D-031) | WING-029; as Tinder; Hinge terms risk accepted by the owner |
| Hinge (experimental API) | UNVERIFIED | UNVERIFIED, approval only | **Experimental**, not in the beta build | WING-029; owner live test L0-L10 not run |
| iMessage (Mac companion) | UNVERIFIED | UNVERIFIED, approval only | **Experimental**, not distributed (unsigned) | WING-030; I-034, I-035 |
| Android notification direct reply | n/a | Not available | Disabled | No RemoteInput reply verified |
| Discovery, compatibility scores | Manual import only | n/a | **Assisted only**: decisions recorded in Wingman | WING-019/031 |
| Swipe / auto-swipe | n/a | Not available | Disabled | No swipe path (D-027) |
| Calendar, date proposals | UNSUPPORTED | | Not built | WING-018; date suggestions always need approval |

And in "Known limitations": a line pointing to `config/release/feature_gates.json` as the
source of what is enabled, and "Approved replies wait until the app is opened (I-024)" kept.
