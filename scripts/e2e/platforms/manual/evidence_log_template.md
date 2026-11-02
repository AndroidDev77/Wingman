# Evidence log entry (copy once per real-device / real-account run)

One template for every platform (SMS phone, Tinder / Bumble / Hinge notifications, Hinge
experimental, iMessage Mac companion). Copy this block to the end of
`docs/integrations/cross_platform_test_matrix.md` §7 ("Evidence log"), fill it, and keep the
per-platform checklist results in the platform's own document (links below). **Redact
everything**: no message text, names, phone numbers, e-mail addresses, codes, tokens, chat
GUIDs, match or channel IDs. Run `redaction_check.py` on every attached file first.

```markdown
### R-<PLATFORM>-<YYYY-MM-DD>[-n]

| Field | Value |
|---|---|
| Run id | R-<PLATFORM>-<YYYY-MM-DD> (also the --run-id of collect_backend_evidence.py) |
| Platform / path | sms · tinder (notification) · bumble (notification) · hinge (notification) · hinge (experimental) · imessage (companion) |
| Tester | role only (owner / invited tester), never a name |
| Date and time (UTC) | |
| Wingman commit | `git rev-parse --short=12 HEAD` of the build |
| Build | debug APK `-Pwingman.useMockApi=false` / experimental APK `-Pwingman.experimental=true` / companion `.app` (signed? notarized?) |
| Device | model, Android SDK int / macOS version, SIM or Messages account setup (no numbers) |
| Platform app version | Tinder / Bumble / Hinge `versionName`, Messages version |
| Backend | URL host only, `WINGMAN_ENV`, commit |
| Checklist run | e.g. android_sms.md §7 D1-D12, B1-B6, P1-P8 / notification_matrix.md §4 N1-N13 + tinder.md T1-T8 / hinge.md §9 L0-L10 / imessage_companion.md §6 M1-M14, A1-A17 |

| Step | Controlled input (redacted) | Expected | Observed by the app / backend | Observed on the peer device or official app | Artifact | PASS / FAIL / BLOCKED / NOT RUN |
|---|---|---|---|---|---|---|
| | e.g. "WING032 probe 1" (fixed probe text, not a real conversation) | | | | file name | |

**Artifacts attached** (each passed `redaction_check.py --forbid <probe words> --forbid <match label>`):
- `evidence-backend.json` from `collect_backend_evidence.py --run-id <run id>`
- redacted log excerpt (`adb logcat -s WingmanNotif:I WingmanSms:I WingmanHinge:I`, or Console.app subsystem `ai.wingman.companion`)
- screenshots with names, avatars and text blurred

**Result per capability**: read ___ · draft ___ · send ___ · profile_read ___ · swipe ___
(`PASSED ON DEVICE` / `FAILED` / `BLOCKED` / `NOT RUN`; a send counts only when the peer
device or official app showed the message exactly once).

**Operator follow-up** (only for `PASSED ON DEVICE`): the exact
`python -m app.services.connections verify --capability <c> --evidence "cross_platform_test_matrix.md R-..."`
(or `verify-experimental`) command run, and the new `evidence_records` entry in
`config/release/feature_gates.json` (`result: PASSED ON DEVICE`, `verification: VERIFIED`,
`evidence_ref` = this file, naming this run id). A gate may move to `autonomous` only in a
separate, reviewed change after that.
```

Per-platform checklists this entry summarizes (do not copy their steps here):

| Platform | Checklist |
|---|---|
| Android SMS | `docs/integrations/android_sms.md` §7 (D1-D12, B1-B6, P1-P8) |
| Tinder / Bumble / Hinge notification path | `docs/integrations/notification_matrix.md` §4 (N1-N13), `tinder.md` §4 (T1-T8), `bumble.md` §3 (B1-B2), `hinge.md` §8 |
| Hinge experimental | `docs/integrations/hinge.md` §9 (L0-L10), observations per `experiments/dating_platforms/live_test_observation.schema.json` |
| iMessage companion | `docs/integrations/imessage_companion.md` §6 (M1-M14, A1-A17) |
| Discovery / swipe | `docs/integrations/swiping.md` §1 (nothing to run: no swipe path exists) |
