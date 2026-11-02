# Results: <platform> / <run_id>

Copy this file per app and run. Fill in only redacted facts. Attach the JSON observations that
validate against the schemas in this folder.

| Field | Value |
|---|---|
| Platform / package | <hinge co.hinge.app \| tinder com.tinder \| bumble com.bumble.app> |
| Official app version | |
| Android version (SDK int) / device model | |
| Wingman build (flavor, commit) | |
| Path tested | <notification-assisted (§8) \| experimental API (§6f)> |
| Reference commit (API path only) | <e.g. f0rr0/hinge-rs@cffe397> |
| Tester | <owner or tester initials; never the account phone or email> |
| Date (UTC) | |

## Notification path (matrix §8)

| Row | Result (VERIFIED / UNSUPPORTED / not run) | Observation file(s) | Notes |
|---|---|---|---|
| read | | | preview setting: |
| detected reply action | | | |
| reply (one fixed test text, once) | | | |
| event taxonomy (like / comment / match vs chat) | | | |
| open conversation (contentIntent) | | | |
| access revoked stops capture | | | |

## Experimental API path (matrix §6f)

| Step | Outcome (pass / fail / blocked / skipped) | Observation file | Notes |
|---|---|---|---|
| L0 setup | | | |
| L1 login (any challenge => blocked) | | | email code needed? official app signed out? |
| L2 list | | | |
| L3 read | | | |
| L4 ingest, no duplicates | | | |
| L5 approval gate | | | |
| L6 one approved send visible in official app | | | |
| L7 restart, no duplicate | | | |
| L8 failure, no retry storm | | | |
| L9 kill switch, 0 calls after opt-out | | | |
| L10 7-day soak, no enforcement | | | |

## Conclusion

* Capability statuses to record (operator path, ADR 0007):
* Enforcement signals seen:
* Blockers / follow-ups:
