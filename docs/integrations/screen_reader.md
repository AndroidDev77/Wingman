# Screen reader for match profiles (D-032, EXPERIMENT)

**Overall status: UNVERIFIED.** Nothing in this document has run against Hinge, Tinder or Bumble,
or on any phone. The pure-Kotlin core is unit-tested with **synthetic** node trees on the JVM; the
Android adapters (the accessibility service, the node reader, the screen) are compiled in CI only.
Whether Hinge exposes profile and chat text to an accessibility reader at all is **unknown** until
the owner runs [S1 to S8](#6-owners-test-s1-to-s8).

Branch `exp-screen-reader` (draft PR, **do not merge**). Decision: [D-032](../DECISIONS.md).
Contract: v1.3.2. Related: [`hinge.md`](hinge.md) §3 (the experimental build),
[`notification_matrix.md`](notification_matrix.md) (the message source), ADR 0008 (identity rules).

Contents:
1. [What it does and does not do](#1-what-it-does-and-does-not-do)
2. [How it works](#2-how-it-works)
3. [Build and sideload the experimental APK](#3-build-and-sideload-the-experimental-apk)
4. [Turn it on](#4-turn-it-on)
5. [Capability table](#5-capability-table)
6. [Owner's test S1 to S8](#6-owners-test-s1-to-s8)
7. [After the inspector run: filling in real view ids](#7-after-the-inspector-run-filling-in-real-view-ids)
8. [Tests and evidence](#8-tests-and-evidence)
9. [Limits and open questions](#9-limits-and-open-questions)

---

## 1. What it does and does not do

Why: a reply should fit the match's profile, and a notification carries only a name, an avatar and
message text. Replies still go out through the notification Reply button (WING-017, unchanged and
not part of this branch).

**Does**
- While you look at a match's profile in Hinge, copies the **text** on screen: the display name,
  prompts with their answers, and basics shown as text. A scrolling profile is read in pieces and
  merged; the capture is finished when you leave the profile.
- Reads the open chat screen as **context only** (which match is open, which side wrote which
  visible line), to tell matches apart.
- Saves the profile on the phone, shows it under **Captured profiles**, uploads it to your Wingman
  account (`importProfile`, `source: device`, `provider: hinge`) and lets you link it to the
  match's conversation. Reply drafts for that conversation can then cite the profile's fields.
- Offers a **screen inspector** that records the screen's *structure* (view classes, view ids,
  positions, flags, and the **length** of each text) and a verdict per screen.

**Does not**
- Tap, type, scroll, swipe, like, send or press anything in another app. It has no code that can
  (a test scans the package for every such call).
- Read any app other than the three allowlisted ones, or anything at all before you switch it on.
- Read photos or photo descriptions. An image's description is never copied, only measured.
- Guess. A field exists only if the screen showed it as text. Basics whose meaning Wingman cannot
  tell (see [§7](#7-after-the-inspector-run-filling-in-real-view-ids)) stay on the phone, labeled
  "seen but not uploaded".
- Create messages, notices or draft requests from the chat screen. Notifications stay the message
  source.
- Link a profile to a conversation by name. Either a stable platform ID matches (none is known for
  Hinge today), or you pick the match.
- Log screen text. Logs carry counts and kinds only.
- Ship in the default build, the beta build or any Play build.

## 2. How it works

Everything is under `android/app/src/experimental/` and is compiled only with
`-Pwingman.experimental=true`.

| Piece | File(s) | Notes |
|---|---|---|
| Snapshot model, allowlist, toggles, gate, explainer text | `integrations/screenreader/ScreenModel.kt` | pure Kotlin; `ScreenReaderGate.mode(package)` is asked before any window content is touched |
| Redacted inspector | `ScreenInspector.kt` | lengths only; keeps the last 20 snapshots; export format `wingman.screen_inspector.v1` |
| Per-app definition and screen classifier | `ScreenClassifier.kt` (`ScreenProfile.HINGE`) | profile / chat / match list / other; view-id hints first, structure otherwise |
| Profile extractor and merge | `ProfileExtractor.kt` | prompts, name, basics, each with its provenance (`ID_HINT`, `KNOWN_PROMPT`, `STRUCTURE`, `SHAPE`) |
| Match key and link decision | `MatchIdentity.kt` | `STABLE_ID`, `NAME_AND_CHAT`, `NAME_ONLY`, `NONE`; `ambiguous` flag; automatic link only for a stable ID |
| Chat screen reader | `ChatScreenReader.kt` | context snapshot: header name, visible lines with side and order; held in memory only |
| Captured-profile store | `CapturedProfiles.kt` | app-private state file; never merges two profiles by name alone |
| Orchestrator | `ScreenReaderCore.kt` | gate, inspector, classify, merge, finish on leaving / idle / flush |
| Upload, link, delete | `ProfileSync.kt` | the only file that calls the backend: `importProfile`, `listConversations`, `putConversationProfileLink`, `deleteConversationProfileLink`, `deleteProfile` |
| Accessibility service | `android/WingmanScreenReaderService.kt`, `android/AccessibilityNodeReader.kt` | read-only; configuration in `src/experimental/res/xml/wingman_screen_reader.xml` |
| Screen | `android/ScreenReaderActivity.kt` | explainer, toggles, inspector, captured profiles; `FLAG_SECURE` |
| Wiring | `android/ScreenReaderRuntime.kt`, `android/ScreenReaderModule.kt`, `experimental/ExperimentalModules.kt` | Settings row "Screen reader (experimental)" |

**Read-only configuration.** The service asks for `canRetrieveWindowContent`, the event types
`typeWindowStateChanged|typeWindowContentChanged`, `flagReportViewIds|flagIncludeNotImportantViews`
and `packageNames="co.hinge.app,com.tinder,com.bumble.app"`. It does not ask for gestures, touch
exploration, key events or screenshots.

**Allowlist.** `co.hinge.app`: profile capture and inspector. `com.tinder`, `com.bumble.app`:
inspector only (no screen definition, no capture, and no text is copied for them at all). Android
delivers no event from any other package, and the code checks the same list again.

**Recognizing a Hinge profile without knowing its view ids.** A profile is a screen with
prompt/answer pairs: two texts that are the only texts of a plain (not tappable) container, the
upper one short. A pair whose upper text is one of Hinge's public prompt titles is a sure sign; two
or more such pairs with unknown titles also count. A screen with a text input is a chat; three or
more tappable two-line rows are the match list. These are **best guesses** written without seeing
the app.

**Basics.** Without view ids Wingman cannot know whether "Example Falls" is a hometown or a
workplace, so it does not say. Only a height is labeled from its form (`5' 7"`, `170 cm`); other
short loose texts are kept on the phone as unlabeled and are **not uploaded**. With view ids filled
in (§7) they become `job_title`, `school`, `hometown`, `location_label`. Age, height and other
protected attributes are stored as stated but the backend never gives them to the model.

**Match key and linking** (ADR 0008 §4: a stable ID is an identity, a label is not).

| Key | When | Ambiguous | Linking |
|---|---|---|---|
| `STABLE_ID` | the tree exposes a stable platform ID (none known for Hinge) | no | automatic if it equals exactly one Hinge conversation's `external_thread_id`; sent as `origin: device_match`, which the server checks again |
| `NAME_AND_CHAT` | display name, and the chat screen of the same viewing shows the same name | only if two matches could collide (the name twice in the match list or among your conversations, or another captured profile with that name and different content) | you pick: "Which match is this?" (same-name conversations listed first) |
| `NAME_ONLY` | display name without chat context | always | you pick |
| `NONE` | no name seen | always | you pick |

**Backend (contract v1.3.2).** `PUT/GET/DELETE /v1/conversations/{id}/profile-link`,
`DELETE /v1/profiles/{id}`. The reply generator gives the model the linked profile's stated text
fields as `p1`, `p2`, ... and a draft lists what it cited in `grounding.profile_fields`
(`prompts/1`, `attributes/job_title`, `bio`). Guardrails: a cited field must exist (else `no_send`);
a profile field can never support a statement about **you**; protected or sensitive attributes and
anything image-like never reach the model; a draft that states such a withheld value is refused.
Unlinking or deleting stops the use on the next draft.

**Capability.** `importProfile` with `source: device` needs a paired Hinge connection whose
`profile_read` is not `UNSUPPORTED`. That connection is the **Hinge notification** connection
(Settings > Dating apps (notifications) > Hinge). While profile reading is on, its capability
document carries `profile_read: UNVERIFIED`; switching reading off reports the downgrade.

## 3. Build and sideload the experimental APK

The build mechanics are those of [`hinge.md` §3](hinge.md#3-experimental-build-integrator): the flag
`-Pwingman.experimental=true` adds `src/experimental`. **The 14 `wingman.hinge.*` properties of
`hinge.md` §3b are NOT needed** for the screen reader: they belong to the unofficial-API connector.
Without them that connector stays inert and its Settings row is hidden, while "Screen reader
(experimental)" is always shown in an experimental build.

On the Mac, with the phone connected by USB (Developer options > USB debugging on):

```bash
git fetch origin && git checkout exp-screen-reader

# A) Inspector and capture only (S1 to S3): no backend, built-in mock data.
cd android
./gradlew :app:assembleDebug -Pwingman.experimental=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For upload, linking and drafts (S4 to S8) the app needs the local backend:

```bash
# Terminal 1 (repo root). This branch changed the one migration (D-010), so an existing local
# database must be recreated once:
#   Docker:    docker compose down -v
#   Homebrew:  "$(brew --prefix)/opt/postgresql@16/bin/dropdb" -h localhost -p 5433 -U wingman wingman
bash scripts/local/start-mac.sh          # prints a DEV token; leave it running

# Terminal 2: let the phone reach the Mac's port 8000 over USB, then build against it.
adb reverse tcp:8000 tcp:8000
cd android
./gradlew --init-script ../scripts/release/version.init.gradle :app:assembleDebug \
  -Pwingman.experimental=true -Pwingman.useMockApi=false \
  -Pwingman.apiBaseUrl=http://127.0.0.1:8000/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Sign in with **Developer sign-in (debug build only)** and the token from terminal 1
([`docs/android_auth.md`](../android_auth.md)). `adb reverse` must be repeated after the cable is
replugged. `bundleDebug` / `bundleRelease` with the flag fail on purpose (never a Play build). Do
not pass the APK on (I-031).

## 4. Turn it on

1. Wingman > **Settings** > **Screen reader (experimental)** > **Open**.
2. Read the explainer and tick **I understand what is read**.
3. Tap **Open Android Accessibility settings**, find **Wingman screen reader** (under "Downloaded
   apps" or "Installed apps") and switch it on. Android shows its standard warning that the service
   can observe the screen.
4. **Android 13 and later, sideloaded apps:** the switch may be greyed out with "Restricted
   setting". Then: Android Settings > Apps > Wingman > the three-dot menu (top right) > **Allow
   restricted settings**, confirm with your PIN or fingerprint, and repeat step 3. (The menu
   appears only after Android has shown the "Restricted setting" message once. Wingman's screen has
   an **Open Wingman's app info** button for this.) Exact wording differs per phone maker:
   UNVERIFIED.
5. Back in Wingman, tick what you want: **Read match profiles in Hinge**, and/or **Inspect Hinge**.

To stop: untick the app in Wingman (reading stops at once and the profile being read is thrown
away), or switch the service off in Android's settings.

## 5. Capability table

Every row is **UNVERIFIED**: implemented, tested only on synthetic data, never run on a device.

| Capability | Path | Label | Notes |
|---|---|---|---|
| `profile_read` (Hinge) | read-only accessibility service, experimental build | **UNVERIFIED** | reported on the Hinge notification connection while reading is on; never `VERIFIED` from a client (D-016) |
| `read` (messages) | notifications (WING-017) | UNVERIFIED (unchanged) | the screen reader is not a message source |
| `send` | notification Reply button (WING-017) | unchanged, not part of this branch | |
| `draft` | backend reply generator with the linked profile | UNVERIFIED on a device; unit and API tested | follows the conversation's own gate (assisted or approval) |
| `swipe`, `calendar_write` | none | UNSUPPORTED | the service cannot act |
| Tinder, Bumble | inspector only | no capability | structure export, no capture |

Release gate: `hinge.screen_reader_profile` in `config/release/feature_gates.json`: mode
`experimental`, channel `experimental_build_only`, not autonomous.

## 6. Owner's test S1 to S8

Use your own account. Write down the phone model, Android version and Hinge app version. If a step
fails, stop there and send back what you saw; the inspector export from S2 is the most useful thing.

| Step | Do | Expect |
|---|---|---|
| **S1** | Build and install (§3 A), turn the service on (§4). Tick **Inspect Hinge** only. | Status shows ON. Nothing is listed yet. |
| **S2** | In Hinge open one match's **profile**, scroll to the bottom, then open the **chat** with that match. Return to Wingman's screen-reader screen. Tap **Save export as a JSON file** (or **Share export**). | Snapshots are listed with a verdict each: "text is readable" or "no readable text (blocked or custom-drawn)", and Wingman's guess (`profile`, `chat`, ...). Open the JSON: it holds classes, view ids, positions and `text(N chars)`, **never** any text. Send the file back. If every Hinge screen says "no readable text", the approach does not work on this app version: stop. |
| **S3** | Tick **Read match profiles in Hinge**. In Hinge open one match's chat, then their profile, scroll through all of it, go back to the chat. Return to Wingman. | The match appears under **Captured profiles** with name, field count and time. **Show fields** lists the prompts and answers exactly as in Hinge, nothing from photos, and basics either labeled or under "seen but not uploaded". Nothing is invented. |
| **S4** | Install the backend build (§3 B), sign in, turn on **Hinge** under Settings > Dating apps (notifications), then repeat S3. | The profile says "Uploaded to your Wingman account". Without the Hinge notification connection it says why it is waiting. |
| **S5** | Tap **Which match is this?** and pick the conversation. (A Hinge conversation exists in Wingman only after a message from that match arrived as a notification.) | "Linked to <name> (by you)". Wingman did **not** link by itself. |
| **S6** | Open that conversation in Wingman and ask for a reply suggestion. | The draft refers to the profile. With the local mock model the text names the first prompt's question and is labeled `[dev mock]`; with a real model it should use what the profile says and nothing else. The draft's `grounding.profile_fields` lists the fields used. |
| **S7** | Untick **Read match profiles in Hinge**. Open a different match's profile in Hinge, scroll, return. | No new captured profile; the earlier one is unchanged. Untick **Inspect Hinge** too: no new snapshots. |
| **S8** | Tap **Delete** on the captured profile. Ask for another reply suggestion in the linked conversation. | "Deleted from this phone and from your account." The new draft no longer refers to the profile. |

Also worth noting while testing: whether Hinge shows any warning with the service on, whether the
phone slows down while scrolling a profile, and whether any text other than the profile's ended up
in **Show fields**.

Recording a result: nothing here may be labeled VERIFIED by an agent. After a successful run an
operator records evidence the usual way (`python -m app.services.connections verify --capability
profile_read --evidence REF`, ADR 0007) and the gate file's `E-HINGE-SR-DEVICE` record is updated.

## 7. After the inspector run: filling in real view ids

`ScreenProfile.HINGE` (in `ScreenClassifier.kt`) has slots for the ids the export reveals. They are
matched as a fragment of the id's entry name (after `:id/`), case-insensitively:

| Slot | Effect |
|---|---|
| `profileScreenIds`, `chatScreenIds`, `matchListIds` | classify a screen by a container id instead of by structure |
| `nameIds`, `chatHeaderNameIds` | the display name on a profile / in the chat header |
| `promptQuestionIds`, `promptAnswerIds` | prompts by id (works for flat layouts too) |
| `basicsIds` (fragment -> attribute name) | turn unlabeled basics into `job_title`, `school`, `hometown`, ... |
| `messageTextIds` | chat lines by id |
| `stableIdIds` | a node whose text is a stable platform ID, if the app has one (enables automatic linking) |
| `knownPromptQuestions`, `chromeTexts` | prompt titles and the app's own labels |

If Hinge is built with Compose or draws its own views, there may be no view ids at all; the
structural rules then have to carry the weight, and the export shows whether they can.

## 8. Tests and evidence

Evidence records: **E-HINGE-SR-MOCK** (`PASSED WITH MOCK`: the tests below) and
**E-HINGE-SR-DEVICE** (`NOT RUN`: S1 to S8).

Android JVM tests, experimental build only (`src/testExperimental/.../screenreader/`), synthetic
trees and sentinel texts:

| Test class | Covers |
|---|---|
| `ScreenReaderGateTest` | off by default, explainer first, allowlist drop before anything is recorded, switching off stops and discards |
| `ScreenClassifierTest` | profile / chat / match list / other; view-id hints win |
| `ProfileExtractorTest` | prompts, name, basics, provenance; no photo text or description; merge across scroll snapshots; finishing on leaving, idle, flush |
| `MatchIdentityTest` | stable ID vs name; ambiguity; no merge by name alone; automatic link only for a stable ID |
| `ChatScreenReaderTest` | sides and order; no stored message, no upload; static: the chat reader and core cannot reach any message or draft route |
| `ScreenInspectorTest` | redaction (no text in the export), verdicts, capacity, works without text |
| `ScreenReaderPrivacyTest` | no captured text in logs or any `toString`; only profile keys hold text |
| `ScreenReaderReadOnlyTest` | static: no acting API anywhere in the package; exact node getters; service configuration; manifest entry; no Hinge identity needed |
| `ProfileSyncTest` | device import body, manual and stable-ID link, waiting / failed / retried uploads, delete on phone and account |

In every build: `ExperimentalSourceSetTest` and the Gradle task `checkNoExperimentalClasses` prove
the default build contains no `com.wingman.integrations.screenreader` class, no accessibility
service entry and no such resource; `ProfileLinkContractTest` covers the 1.3.2 client side.

Backend: `backend/tests/integrations/test_profile_links.py` (a draft with a linked profile cites a
real field; a citation of a non-existent field is rejected; unlinking and deleting stop the use;
sensitive and image attributes never reach the model; `device_match` needs the same stable ID;
cross-user isolation; contract validation of every body). Contracts: `contracts/validate.sh`.

Not tested anywhere: the service on a device or emulator (no instrumented test was added: a fixture
activity read through a real accessibility service needs the service enabled through secure
settings on the CI emulator, which this branch does not attempt).

## 9. Limits and open questions

- **Unknown until S2:** whether Hinge exposes text to accessibility readers, its view ids, whether
  profile and chat are separate windows, and whether it reacts to an enabled accessibility service.
- **Terms.** Reading another app's screen with an accessibility service may breach that app's
  terms. Accepted by the owner for this experiment only (D-032, as D-031).
- **Play.** An accessibility service used for anything but accessibility is not allowed on Play.
  This code can never be in a Play build.
- **Structural guesses can be wrong.** A two-text card that is not a prompt could be captured as
  one (provenance `STRUCTURE`); check **Show fields** in S3.
- **Basics are mostly unlabeled** until §7 is done, so drafts rely on prompts.
- **A capture that starts mid-scroll** has no name and becomes its own entry "(name not seen)".
- **Linking needs a Hinge conversation**, which exists only after a notification message from that
  match was read as a linked chat message (UNVERIFIED itself, `notification_matrix.md`).
- **Uploaded captured profiles** that are not linked also show up in Discovery's review queue
  (they are ordinary profiles); linked ones are left out.
- **Service-only process.** If Android starts Wingman just for the service, captures are saved on
  the phone and uploaded the next time Wingman is opened.
- The unofficial-API Hinge connector is untouched; whether it is removed is a later decision.
