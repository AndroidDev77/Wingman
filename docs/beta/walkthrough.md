# Onboarding walkthrough and sample conversations

Owner: WING-026. Screen names and button labels below are the app's own text (v0.1.0-beta.1,
`android/app/src/main/java/com/wingman/features/`). Steps the CI emulator drove are marked
**(CI)**; the rest are compiled and JVM-tested but have not been driven on a device.

## 1. Sign in

**Sign in to Wingman** says sign-in is not available in this build (no identity provider yet).
Under **Developer sign-in (debug build only)**, paste your dev token and tap **Use dev token**
([`install.md` §3](install.md#3-sign-in-dev-token)). **(CI)**

## 2. Onboarding (opens by itself the first time)

Four steps; **Next** and **Back** move between them.

### Step 1: What are you looking for?

Pick one of the five intents. This shapes the drafts' tone and what Wingman treats as appropriate
escalation; it never makes Wingman send anything.

| Option | App text |
|---|---|
| **Serious relationship** | Looking for a long-term relationship. **(CI)** |
| **Casual dating** | Dating without pressure to commit. |
| **Casual physical** | Something physical between consenting adults who both want it. |
| **Open to anything** | Seeing where things go. |
| **Custom goal** | Describe your own goal in your words (**Describe your goal (required)**). |

Even with **Casual physical**, Wingman escalates only when the contact's own cited message shows
matching interest, and never writes explicit or coercive text (such a draft becomes `no_send`).
You can set a different intent per contact later (**Settings**, contact overrides).

### Step 2: How do you text? (optional, **Skip** available) **(CI: skipped)**

**Punctuation** (Minimal ... Expressive!), **Emoji** (Occasional, Frequent, ...), **Message
length** (Very short ...), **Humor**, **Tone** (Playful, Relaxed, Reserved, ...), **Reply pace**,
and **Example messages you wrote (optional)**. Anything left as "Not set" stays neutral.

### Step 3: How much should Wingman do?

| Mode | App text | In the beta |
|---|---|---|
| **Manual (off)**, the default | "Wingman never sends anything for you. It suggests replies only when you ask." | Nothing is drafted until you tap **Suggest a reply** in a conversation |
| **Assisted** | "Wingman drafts replies; you approve every send." Confirm dialog **Turn on Assisted?** | **Recommended for the beta.** **(CI)** |
| **Autopilot** | "Wingman may send on VERIFIED channels within your limits." Confirm dialog **Turn on Autopilot?** with your limits listed | Has no effect on SMS: the dialog warns "No connected channel has VERIFIED sending, so nothing will be sent automatically yet. Drafts will wait for your approval." |

Automation stays Manual unless you pick another mode **and** confirm the dialog. Under Assisted or
Autopilot you can also set **Only send during active hours**, **Wait a random delay before
automatic replies**, a **Daily automatic send limit**, and **Always ask me before sending** for
kinds of message (first message to someone, dates and commitments, sensitive topics, replies
Wingman is unsure about).

### Step 4: Review and save

Check the summary, tap **Save and finish**. You land on the **Inbox**. **(CI)**
If your settings changed elsewhere in the meantime, Wingman shows the current values and overwrites
nothing (`412`).

## 3. The app

Bottom bar: **Home**, **Inbox**, **Approvals**, **Discovery**, **Settings**.

- **Home**: your name, pending approvals, whether automation is paused, the backend version.
- **Inbox**: one row per contact with the latest message preview. **(CI)** Unread counts only grow
  (I-022). Tap a row to open the conversation: messages with their delivery state ("Queued, not
  sent yet", "Sent (delivery not confirmed)", "Delivered") and **Suggest a reply** (optional
  guidance). A suggestion says "Suggestion only. Nothing has been sent."
- **Discovery**: placeholder screen. Swiping and discovery are UNSUPPORTED.
- **Settings**: your preferences, per-contact overrides, **Connections** (Phone SMS, §5) and
  **Sign out**.

## 4. Approvals tab

Three tabs: **To approve**, **Controls**, **Activity**.

### To approve **(CI)**

Each card shows the contact, the draft, "Why Wingman asked you:" with reasons (e.g. "You asked to
approve replies to this person before they go out (assisted mode).", "It would be the first message
to this person.", "Sending on this platform hasn't been verified on a real device yet...") and
"Nothing is sent until you approve." Buttons: **Approve**, **Edit** (then **Your reply (this exact
text is sent)** and **Approve edited**, or **Undo edit**), **Reject**, **Open chat**.

After **Approve**: "Approved. It shows as sent only after the messaging app confirms it." The
status moves through "Queued, not sent yet" to "Sent (delivery not confirmed)". Wingman never shows
"sent" before the messaging app confirms.

### Controls **(CI: takeover)**

- **Pause everything**: "stops automatic replies to everyone. Drafts you approve and messages you
  write still send."
- Per contact: **Paused** switch, and **I'm taking over**.

**Pause versus takeover (D-014):**

| | Pause (global or one contact) | Take over (one contact) |
|---|---|---|
| Automatic (autopilot) replies already queued | Canceled | Canceled |
| New drafts | Still written, but go to your approval queue (reason "Automation is paused") | **None** until you resume |
| Drafts waiting for your approval | Kept | **Canceled** |
| Replies you approved that haven't gone out yet | Still sent | **Canceled** |
| Messages you write yourself | Sent | Sent |
| A reply the phone already started sending | Cannot be recalled | Cannot be recalled |

Takeover asks first (**Take over with <name>?**), because it cancels queued work. Afterwards:
"You've taken over." and "You're in control: Wingman is off for this person". Resume by switching
it off.

### Activity **(CI)**

The audit timeline from the server, newest first: "Message received", "Draft proposed for your
approval", "Draft approved (not sent yet)", "Queued, not sent yet", "Handed to the messaging app,
not confirmed yet", "Sent (delivery not confirmed)", "Automation paused", "You took over",
"Phone didn't confirm; outcome unknown", ... Filter by contact with **Activity for <name>**;
**Show all** clears it. It never contains message text.

## 5. Connections: pair and unpair SMS

**Settings > Connections > Phone SMS**: **Make Wingman the default SMS app**, **Allow SMS access**,
**Pair this phone's SMS**; afterwards `Status: Connected`, `Send replies: UNVERIFIED (not yet tested
on a device)`, `Autopilot: not available for SMS`. **(CI, with the role and permissions granted by
`adb`.)** **Disconnect** asks first ("Disconnect this phone's SMS?" / **Keep connected**), then
cancels queued replies and pending approvals for this phone. Details, MMS and restoring your SMS
app: [`install.md` §5](install.md#5-sms-pairing-and-the-default-sms-app-role).

## 6. Sample conversations

> **All people, names, numbers and messages below are fictional**, written for this guide. Drafts
> are illustrations of the behavior the code enforces (decisions, approval reasons, audit rows), not
> recorded model output: with the dev mock, every draft reads `[dev mock] ...`, and real-LLM quality
> is UNVERIFIED (I-009).

### Sample A: assisted approval with an edit (intent: Serious relationship, mode: Assisted)

> **Riley (fictional):** Hey! Still thinking about that taco place you mentioned. Did you ever try
> the one on 5th?

Wingman drafts, grounded in Riley's message and nothing else:

> **Draft:** Not yet! It's been on my list for weeks. Have you been?

**To approve** card. Why Wingman asked you: assisted mode; first message to this person; sending
on this platform hasn't been verified on a real device yet (SMS).
You tap **Edit**, change it to "Not yet, it's been on my list forever. Worth it?" and tap **Approve
edited**. Exactly your text is sent.

Activity: Message received, Draft proposed for your approval, Draft approved (not sent yet),
Queued, not sent yet, Handed to the messaging app, not confirmed yet, Sent (delivery not confirmed).
(The approve-with-edit path is E2E-tested: F4, `final_text`, audit `edited: true`.)

### Sample B: Wingman won't invent a fact (`ask_user`) or suggests not replying (`no_send`)

Intent: Open to anything, mode: Assisted.

> **Sam (fictional):** Wait, which part of town did you grow up in?

You never told Wingman where you grew up, and nothing in the conversation says so. Wingman does not
make it up. The draft decision is **ask_user**: "Wingman needs your input", with a question such as
"Where did you grow up? I won't guess." Nothing goes to the approval queue and nothing is sent.
Write the answer yourself, or tap **Suggest a reply** with guidance ("I grew up near the lake").

Later:

> **Sam (fictional):** Honestly I'm swamped with work this month, sorry if I'm slow.

The stage does not move forward on "busy"; Wingman may suggest giving space. If Sam had written
"Please stop texting me", the stage moves to **paused** and the next action is **stop**: no draft
is proposed ("Wingman suggests not replying yet"). An explicit or pressuring draft would also be
`no_send` (`unsupported_escalation` when the contact never showed matching interest).

(Guardrail behavior: unit-tested in `backend/tests/ai/replies` and `backend/tests/ai/analysis`;
not E2E-testable with the dev mock, I-026.)

### Sample C: takeover (intent: Casual dating, mode: Assisted)

> **Jordan (fictional):** Are we still on for Thursday at 7? Want to pick the place?

Wingman drafts a reply. The card's reasons include "It suggests a date or makes a commitment."
because it touches the plan. Meanwhile you'd rather handle this one yourself.

In **Approvals > Controls**, switch **I'm taking over** for Jordan and confirm **Take over with
Jordan?**. Result: "You've taken over. Canceled 1 draft waiting for approval." The pending draft is
canceled, no new drafts are written for Jordan, and your own messages still send. The date is not
"confirmed" anywhere just because a time was proposed: the stage reaches `confirmed` only when both
of you explicitly agree on one specific plan in the messages.

Activity: Message received, Draft proposed for your approval, You took over, Draft canceled
(nothing sent). (Takeover is E2E-tested: F7 and app step A6.)
