# Wingman private beta (v0.1.0-beta.1)

Owner: WING-026. This folder is everything a beta operator and a beta tester need.

> **This is a limited, unpaid, private beta.** Gate G5 (one real messaging connector verified
> on a physical device) is **not met** (I-003). SMS sending is **UNVERIFIED**: it has run end to end
> on an Android **emulator** in CI, never on a real phone with a SIM. Until G5 is met:
> - every outgoing SMS needs the tester's approval (autopilot is impossible for SMS while `send` is
>   UNVERIFIED, D-016);
> - there is **no paid launch** and no public release (see the go/no-go in
>   [`release_checklist.md`](release_checklist.md));
> - Tinder, Bumble, Hinge, iMessage and notification reply are **UNSUPPORTED** (not built).

## Who the beta is for

- **Consenting adults (18+)** who agreed to the [tester terms](#tester-terms) below.
- Testing with **their own conversations only**: their own phone number, their own contacts' messages
  to them. Never someone else's phone or account.
- Comfortable sideloading a debug APK and (for SMS) making Wingman the default SMS app on a
  **spare or secondary** Android phone for the duration of a test session.

## What works, in one table

Labels follow `AGENT_RULES.md`. "Evidence" points at what proved it; nothing here is inferred.

| Area | Status | Evidence |
|---|---|---|
| Onboarding: intent (5 modes), style, autonomy (manual by default) | Works on a CI emulator against the real backend | `e2e_results.md` A2; F1 |
| Inbox, approve / edit / reject drafts, audit timeline | Works on a CI emulator against the real backend | A3 to A5; F4 |
| Pause and takeover (D-014) | Works over HTTP; takeover also in the app on a CI emulator | F7; A6 |
| Unpair cancels queued work | Works over HTTP | F8 |
| Zero duplicate sends under duplicates, crashes, retries, lease expiry | Proven over HTTP with a simulated device | X1 to X5, run-wide invariant |
| **SMS** read and send | **UNVERIFIED**: emulator SMS stack exercised only | `e2e_results.md` "Real channel: SMS on the CI emulator" |
| Draft quality with a real LLM | **UNVERIFIED** (I-009): only the labeled `[dev mock]` has run | |
| Sign-in | Debug-build dev token only (no IdP, I-014) | A1 |
| Tinder / Bumble / Hinge / iMessage / notification reply | **UNSUPPORTED** (not built) | `e2e_results.md` connector matrix |

Full table: [`known_limitations.md`](known_limitations.md).

## Documents

| Doc | For | What it covers |
|---|---|---|
| [`release_checklist.md`](release_checklist.md) | Operator | Version, build, config, migrations, worker, privacy checks, **go/no-go** |
| [`install.md`](install.md) | Tester and operator | APK sideload, backend options, dev-token sign-in, SMS pairing and the default-SMS-app role, device matrix. Includes what was actually validated |
| [`known_limitations.md`](known_limitations.md) | Everyone | Supported-platform table (VERIFIED / UNVERIFIED / UNSUPPORTED) and every known gap |
| [`walkthrough.md`](walkthrough.md) | Tester | The screens in order, plus fictional sample conversations |
| [`feedback_form.md`](feedback_form.md) | Tester | Bug report, draft quality, time saved, privacy; **how to report an unwanted send** |
| [`metrics.md`](metrics.md) | Operator | WAU, time saved, assisted-to-date conversion, retention: definitions and what is measurable today |
| [`roadmap.md`](roadmap.md) | Owner | Prioritized follow-ups with go/no-go notes |
| [`release_notes.md`](release_notes.md) | Everyone | v0.1.0-beta.1: only tested functionality, each line with its evidence |

Scripts: [`scripts/release/`](../../scripts/release/) (`check.sh` pre-release gate, `build.sh`
versioned APK and image, `version.sh` bump). Tests: `python3 -m unittest discover -s tests/release`.

## Tester terms

Every tester confirms, before receiving the APK:

1. I am at least 18 years old.
2. I will use Wingman only with my own phone, my own number and conversations I am a party to.
3. I understand Wingman drafts replies with AI; drafts can be wrong, and **I am responsible for every
   message I approve**. In this beta nothing is sent by SMS without my approval.
4. I understand my messages, the names my contacts use, and Wingman's drafts are stored on the
   operator's beta server (see [privacy](release_checklist.md#5-privacy-and-data-handling)), and
   that I can ask for my account to be deleted at any time.
5. I will not use Wingman to mislead anyone about who is writing, to pressure anyone, or for
   explicit content. Wingman's guardrails refuse explicit or coercive wording (`no_send`), but they
   are not a substitute for my judgment.
6. While Wingman is my default SMS app, **picture and group messages (MMS) are not received or
   sent**; I will switch back to my usual SMS app after each session ([install.md](install.md#5-sms-pairing-and-the-default-sms-app-role)).
7. I will report any message that went out without my approval immediately
   ([incident reporting](feedback_form.md#incident-an-unwanted-or-wrong-send)).
