# iMessage companion: real-Mac run record (template)

Copy this file into a new section at the end of `docs/integrations/imessage.md`
(`## Mac run YYYY-MM-DD`). Fill in every field. Write `n/a` instead of leaving a
field blank. **Never paste message text, phone numbers, e-mail addresses or full GUIDs.**
The probe prints hashes (`guid#`, `chat#`). Use those.

## Environment

| Field | Value |
|---|---|
| Date / time (UTC) | |
| Tester | |
| Repo commit (`git rev-parse HEAD`) | |
| Mac model / chip (`machine` from `mac_probe.py env`) | |
| macOS `ProductVersion` / `BuildVersion` | |
| Messages signed in with Apple Account (yes/no), iMessage enabled (yes/no) | |
| Text Message Forwarding from an iPhone enabled (yes/no) | |
| Messages in iCloud enabled (yes/no) | |
| Peer device and transport it uses (iPhone iMessage / Android SMS / ...) | |
| Host process that ran the probe (Terminal.app, iTerm2, ...) | |

## Permissions

| Permission | Before the run | Prompt shown? (exact wording) | After the run |
|---|---|---|---|
| Full Disk Access for the host process | | n/a: there is no prompt; it is granted in System Settings | |
| Automation: host process -> Messages | | | |
| Contacts (should NOT be requested) | | | |

## Results

| # | Step | PASS / FAIL / SKIPPED | Observation (counts, timings, error numbers only) |
|---|---|---|---|
| M1 | `env` without Full Disk Access | | |
| M2 | `env` with Full Disk Access | | |
| M3 | `read --limit 200` | | |
| M4 | `watch`, peer sends a 1:1 iMessage | | |
| M5 | `watch`, peer sends a group message | | |
| M6 | `watch`, peer reacts / edits / unsends | | |
| M7 | `watch`, SMS via Text Message Forwarding | | |
| M8 | `send`, Automation consent denied | | |
| M9 | `send`, consent granted, 1:1 iMessage | | |
| M10 | `send` into a group chat | | |
| M11 | `send` into an SMS chat (forwarding) | | |
| M12 | `send` while the Mac is locked / asleep / Messages quit | | |
| M13 | Revoke Full Disk Access and Automation; rerun `env` and `send` | | |
| M14 | Restart the probe in the middle of `watch`; no message seen twice by cursor | | |

## Conclusions

* Capabilities that may move to VERIFIED, with this section as `evidence`:
* Capabilities that stay UNVERIFIED or become UNSUPPORTED, with reasons:
* Surprises (schema columns, `text_source` distribution, `chat.style` values, error numbers):
