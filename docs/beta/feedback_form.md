# Beta feedback form (template)

Owner: WING-026. Copy one section per report into the operator's feedback channel (issue, form or
email). **Never paste real message text from other people.** Paraphrase, or replace names and
details with placeholders ("[contact]", "[place]"). Screenshots: crop or blur the other person's
messages and number.

Always include:

| Field | Value |
|---|---|
| Tester ID (your dev-token `--sub`, e.g. `beta-jd`) | |
| App version (Settings, or `adb shell dumpsys package com.wingman \| grep versionName`) | `0.1.0-beta.1` |
| Device and Android version, or "emulator API xx" | |
| Channel: SMS (phone) / mock (operator testing) | |
| Date and approximate time (with time zone) | |

---

## Incident: an unwanted or wrong send

**Report immediately**, before anything else, if a message went out that you did not approve, went
to the wrong person, went twice, or went out after you paused or took over.

1. **Stop it now.** In **Approvals > Controls**, turn on **Pause everything**. For one person,
   **I'm taking over**. If you're unsure, also **Settings > Connections > Phone SMS > Disconnect**
   and switch your default SMS app back ([`install.md` §5.4](install.md#54-restore-your-previous-sms-app)).
2. **Tell the operator** in the incident channel with:
   - [ ] What was sent (paraphrased), to which contact (placeholder name), when.
   - [ ] Had you approved it? Did you see it in **To approve**? Had you edited it?
   - [ ] What **Activity** shows for that contact (row titles and times; screenshot is fine, it
         contains no message text).
   - [ ] Was automation paused / taken over at the time?
   - [ ] Did the contact receive it once, twice, or not at all (if you know)?
3. Do **not** uninstall or clear the app's data: the operator may need the phone's state file.

The operator: pause the tester's automation if not done (`POST /v1/automation/pause` with their
token), pull the audit trail (`GET /v1/agent-actions?contact_id=...`: actor, `reason_code`,
`approval_id`, `message_id` per row) and the server log for that time, and classify:

| Class | Example | Severity |
|---|---|---|
| Sent without approval | `send_attempted` with no `approval_approved` before it, origin not `user_manual` | **S1: stop the beta for everyone until explained** |
| Duplicate send | two `send_acknowledged` for one message, or two messages for one approval | **S1** |
| Sent after pause / takeover | `send_attempted` after `automation_paused`/`automation_takeover` for an automated origin | **S1** |
| Wrong recipient | outbound thread differs from the conversation's | **S1** |
| Outcome unknown | `lease_expired` (phone never reported back) | S3: expected limitation (I-024); confirm on the phone |
| Approved text differs from sent text | `final_text` vs the phone's sent box | S2 |

S1 incidents are recorded in `docs/beta/` (a dated incident note) and block G5 work until fixed.

---

## Bug report

- **What happened:**
- **What you expected:**
- **Steps to reproduce:**
  1.
  2.
- **How often:** once / sometimes / every time
- **Screen and exact on-screen text (error messages):**
- **Logs (optional, technical testers):** `adb logcat -d | grep -i wingman > wingman.log`
  (check it for personal text before sending)
- **Severity (your view):** blocks me / annoying / cosmetic

---

## Draft quality (one per draft you want to rate)

Rate as many drafts as you like; one a day is plenty. With the operator's `[dev mock]` backend
every draft is a fixed placeholder; rate only drafts from a real model.

| Question | Answer |
|---|---|
| Intent and mode at the time | e.g. Casual dating, Assisted |
| Draft decision | send_candidate / ask_user ("Wingman needs your input") / no_send ("Wingman suggests not replying yet") |
| **Would you have sent this as is?** | Yes as is / Yes after small edits / Only after a rewrite / No |
| If edited: what did you change? | tone / length / facts / emoji / other: |
| Did it sound like you? (1 = not at all, 5 = exactly) | 1 2 3 4 5 |
| Was it relevant to what they said? (1 to 5) | 1 2 3 4 5 |
| **Did it state anything about you that isn't true or that you never told Wingman?** | No / Yes: (paraphrase) |
| Did it push things further than the conversation supported, or feel pressuring? | No / Yes: |
| Were the "Why Wingman asked you" reasons right? | Yes / No: |
| If ask_user or no_send: was holding back the right call? | Yes / No, it should have drafted |

An invented fact or pressuring wording is a **safety issue**: also file it as an incident (S2).

---

## Time saved (weekly, about 2 minutes)

The app does not measure time; this self-report is the time-saved metric's main input
([`metrics.md`](metrics.md#2-time-saved)).

| Question | Answer |
|---|---|
| Conversations you used Wingman for this week | |
| Replies you sent from an approved draft (approx.) | |
| Typical time to write such a reply yourself, without Wingman (minutes) | |
| Typical time to review/edit/approve a Wingman draft (minutes) | |
| Overall this week Wingman saved me / cost me about ___ minutes | |
| Anything that cost you time (sync waits, re-reading, fixing drafts)? | |

---

## Privacy and comfort

| Question | Answer |
|---|---|
| Did anything feel like it knew more than it should? | |
| Did you see someone else's data, or anything that looked like it? (**S1 incident if yes**) | |
| Are you comfortable with your messages being stored on the beta server? | Yes / Unsure / No |
| Did the default-SMS-app switch cause problems (missed MMS, missing history after switching back)? | |
| Would you want any data deleted now? (the operator deletes your whole account on request) | |
| Anything else that worried you? | |

---

## Overall (end of each beta week)

- What did you use Wingman for most?
- What would make you use it more?
- What should we stop doing?
- How likely are you to keep using it after the beta (0 to 10)?
- Would you pay for it **once it works on a verified real channel**? (Not offered now: there is no
  paid launch in this beta.)
