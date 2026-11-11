# 0008: Platform integrations, experimental connectors, assisted delivery and swipe gating

- Status: Accepted (contract v1.3.0, 2026-11-02). Extends ADR 0005 (capabilities), ADR 0006 (device outbox) and ADR 0007 (pairing and capability trust). Implements owner decision D-021 for the contract and the backend; records the D-017 claim note. Resolves I-022 (mark-read).

## Context
The phase 7 tickets connect Tinder, Bumble and Hinge (WING-027 to 029), an iMessage Mac companion (WING-030), profile review and swiping (WING-019, WING-031) and a cross-platform acceptance suite (WING-032). Contract 1.2.0 could not express any of it:

- the dating platforms and `imessage` were not pairable;
- a notification can be a match, a like, a Hinge prompt comment or an expiry warning rather than a chat message, and often carries only a display name ("Alex"), which may belong to two different matches;
- most of these apps have no tested send path, so a draft can only be delivered by the user, by hand;
- D-021 allows **experimental** connectors built on each app's private API, under binding conditions: off by default, an explicit per-user ban-risk opt-in, tokens only on the device, calls only from the device, approval-only until the owner's live test;
- profiles, decisions and swipe execution had no shapes.

The product risk is the same as in ADR 0005, with more ways to get it wrong: replying to a like as if a chat existed, merging two people with the same first name, showing "sent" for something Wingman never transmitted, swiping on an account that can be banned for it, or letting a session token reach the backend.

## Decision

### 1. Platforms, transports and the experimental flag
- `createConnection` accepts `tinder`, `bumble`, `hinge` and `imessage`. All are **device-side**: they need `device_install_id` and a claimed capability document, and ADR 0007 trust applies unchanged (a client never stores `VERIFIED`).
- A connection has a `transport`: `simulator` (mock), `sms_provider` (SMS), `notification` (the Android notification listener), `companion` (the Mac, iMessage only) or `unofficial_api` (D-021, the three dating apps only). Single-transport platforms default it; the dating apps must say which.
- `experimental` is true **exactly** for `unofficial_api` (schema `if/then` and a DB CHECK).
- One install has at most one paired connection per platform (ADR 0007). Pairing the same platform again with a different transport is `422` at `/transport`, so one platform is never ingested twice through two paths.

### 2. Experimental consent
- Pairing an `unofficial_api` connection needs `experimental_consent`: platform, consent text version, the user's acceptance time, and `ban_risk_acknowledged: true`. Without it the server answers `422 experimental_consent_required` at `/experimental_consent`. Consent for another platform is `422 validation_error`.
- The server stores the consent on the connection (`consent_text_version`, `consent_accepted_at`, `consent_recorded_at`), audits `experimental_consent_recorded`, and returns it as `Connection.experimental_consent`. A DB CHECK guarantees that a paired experimental connection always has an unrevoked consent.
- `revokeExperimentalConsent` (`POST /v1/connections/{id}/experimental-consent/revoke`) runs exactly the unpair transaction of ADR 0007. The connection is disconnected with every capability `UNSUPPORTED`. Unclaimed instructions, queued sends, pending approvals and pending assisted deliveries are canceled with reason `connection_unavailable`. A leased send cannot be recalled. On top of that, the consent gets `revoked_at`, `auto_swipe_enabled` becomes false, and `experimental_consent_revoked` is audited. Plain unpair also revokes the consent. To use the connector again the user pairs again with a new consent.
- **Experimental-unverified is approval-only.** Until an operator records the owner's live test (`experimental_verified_at`, set by `python -m app.services.connections verify-experimental --evidence REF`, no HTTP route, D-016), the policy:
  - adds `capability_unverified` to every draft, so no autopilot, whatever the capability document says;
  - has the dispatcher and the claim-time re-check cancel any `autopilot` send;
  - closes the swipe gate.

  Lifting this gate does not make any capability `VERIFIED`. Each capability still needs `verify --capability ... --evidence`.

### 3. Credentials never reach the backend
- No request schema has a field for a platform password, one-time code, session token, cookie or API key. Every request model is `additionalProperties: false` / `extra="forbid"`, so such a field is a `422`.
- `contracts/validate.py` check [7] fails the contract if **any** property name in `openapi.yaml` components or in `schemas/*.json` matches a credential pattern (`passw`, `secret`, `token`, `cookie`, `session`, `credential`, `otp`, `api_key`, `auth_code`, `login`, `jwt`, ...). The backend runs the same scan as a test.
- Negative examples show a `session_token` on pairing, an `auth_token` in an event, a `cookie` on a profile import and a `password` in settings, each rejected.
- Device-side code (WING-027 to 030) keeps platform sessions in the Android Keystore or macOS Keychain, sends nothing but `connector_event.v1` envelopes and outbox outcomes, and deletes the session when consent is revoked.

### 4. Inbound kinds and identity: who may be replied to
`connector_event.v1` inbound payloads gain optional fields. When they are absent, the payload has the 1.2.0 meaning: a linked, complete chat message.

| Field | Values |
|---|---|
| `kind` | `chat_message`, `match`, `like_comment`, `expiry`, `system`, `unknown` |
| `identity` | `linked` (a stable platform ID ties it to one contact and thread), `unlinked` (a label only), `ambiguous` (the label fits more than one) |
| `truncated`, `content_hidden` | the source cut or hid the text; hidden means `text` is empty (schema) |
| `sender_label`, `external_conversation_id` | as displayed by the source / when exposed |
| `source` | must equal the connection's `transport` (`422` at `/payload/source`) |

**Only a `chat_message` with `identity: linked` that is neither truncated nor hidden becomes a `Message`** and enters the pipeline (memory, analysis, draft, policy). Everything else becomes an `InboundNotice`:
- it is stored, deduplicated on `(user, platform, external_message_id)` and audited `notice_received`;
- it is shown by `listInboundNotices`;
- it never creates a contact or conversation, so an unlinked or ambiguous sender is **never merged** into a person;
- it never produces a draft, approval, assisted delivery or send.

A DB CHECK forbids storing a linked, complete chat message as a notice. A device without a platform message ID sends a stable synthetic one, for example a hash of the notification key and post time.

### 5. User-made identity links (WING-032)
`createIdentityLink`, `listContactIdentityLinks` and `deleteIdentityLink` let the **user** say that two of their contacts are the same person, for example a Hinge match and a phone contact.
- Links are unordered: one row per pair, `contact_a_id < contact_b_id`, enforced by CHECK.
- `origin` is always `user` (CHECK and schema `const`).
- Both contacts must be the caller's (`404` otherwise) and different (`422`).
- A link is audited and changes nothing else by itself.

There is no code path that creates a link from names, numbers, photos or model output, and the request has no confidence or score field.

### 6. Assisted (draft-only) delivery
When the policy would hand a draft to a connection whose `send` is `UNSUPPORTED`, on a platform with a hand-delivery path (`tinder`, `bumble`, `hinge`, `imessage`), it does not stop with `policy_blocked` as before. It returns `PolicyOutcome.ASSISTED`, and the **dispatcher** (the only creator) queues an `AssistedDelivery` holding the draft text, the thread to open, and the draft's content reasons for the user to review.
- The order of stops is unchanged: manual mode, takeover and a `block_send` rule still stop first.
- Nothing is ever transmitted. The app or device shows the draft, copies it or opens the platform app.
- `confirmAssistedDelivery` records the user's **report**: one outbound message with `origin: assisted_draft` and `delivery_state: user_reported_sent`. It has no `sent_at` or `delivered_at`, it is terminal, and it is never shown as `sent` or `delivered`. The schema and a DB CHECK make `assisted_draft` and `user_reported_sent` imply each other. The user may report edited text.
- `dismissAssistedDelivery` drops the draft. A newer inbound message (`superseded`), takeover and unpair or consent revocation cancel pending ones. A non-pending one is `409 assisted_delivery_not_pending`.
- There is at most one pending assisted delivery per conversation.
- For a connection whose `send` is `UNVERIFIED` (for example an experimental Hinge connector before the live test), nothing changes from 1.2.0: the draft goes to the approval queue with `capability_unverified`, and an approved send goes through the device outbox.

### 7. Profiles, evaluations and the swipe execution gate (WING-019/031)
- **`Profile`**: provider, external ID, bio, prompts, and only the attributes the source provided, under the source's names (flat values, no nested objects). It also carries the source timestamp and provenance (`manual`, `fixture` or `device`). `stale` is true after 14 days or when the source invalidated it. `importProfile` with `source: device` needs a paired connection on that platform whose new `profile_read` capability is not `UNSUPPORTED`.
- **`Evaluation`**: score 0 to 100 (or null), reasons with evidence pointers, `known_unknowns`, the intent mode used, dealbreakers hit, confidence and evaluator version. Advisory only.
- **`ProfileAction`**: the user's `decision` (`like`, `pass`, `skip`, `superlike`) is separate from `execution` (`not_executable`, `queued`, `executed`, `failed`, `unknown`), and carries the request's `Idempotency-Key`. `skip` is never executed (CHECK). At most one `queued` or `executed` action per profile (partial unique index).
- **Execute gate**: `requestProfileActionExecution` moves `not_executable` to `queued` only if every check holds, and otherwise answers `422 swipe_execution_disabled` naming the failed check:
  - the decision is not `skip`;
  - a connection is named, paired and connected;
  - its `swipe` is `VERIFIED`;
  - `auto_swipe_enabled` is on, which `putConnectionSettings` accepts only while the connection-level gate passes;
  - the connection is not experimental-unverified;
  - global automation is not paused;
  - the profile is on that platform and not stale;
  - no other live action exists for the profile.

  `queued` means accepted for execution, **not** executed.

### 8. Capability `profile_read`, and no `BLOCKED` label
- `profile_read` is an optional sixth capability in `connector_capabilities.v1`. When absent it is `UNSUPPORTED`. It is independent of `read` (messages) and of `swipe`. It is stored and trust-filtered like the others; the backend's `profile_read_capability` column defaults to `UNSUPPORTED` and a CHECK keeps it equal to the document.
- The tickets' handoff matrices use `BLOCKED`, but it is **not** a capability label. A blocked capability is `UNVERIFIED` (implemented, not reproduced) or `UNSUPPORTED` (not available), with the blocker in `notes`. A new label would let clients mistake an unknown state for a usable one, and the 1.x enum must stay closed. A negative example rejects `BLOCKED`. The handoff documents may keep using `BLOCKED` as a test-result word.

### 9. Mark-read and the claim note
- `markConversationRead` (`POST /v1/conversations/{id}/read`) sets `unread_count` to 0 and returns the `ConversationDetail`. It is idempotent on state and changes nothing else (I-022).
- `claimOutboxInstructions` now documents D-017: a claim from any install other than the paired one, on an unpaired connection, or on a never-paired device-side connection is `422 validation_error` at `/device_install_id`. The check runs before anything is dispatched, canceled, leased or recovered. This closes ADR 0007's "Not covered" note about the binding.

## Consequences
- **WING-017** (notifications): pair as `android_notification` (`notification`), or per app as `tinder`/`bumble`/`hinge` with `transport: notification`. Report `kind`, `identity`, `truncated`, `content_hidden`, `sender_label` and `source: notification`, with a stable synthetic `external_message_id`. Send only through claimed outbox instructions, and only where a RemoteInput reply was tested.
- **WING-027 to 029**: experimental connectors pair with `unofficial_api`, `experimental: true` and the consent captured in-app, and report `source: unofficial_api` with `identity: linked` only for an exact platform match ID. Revoking consent deletes the on-device session. Until an operator runs `verify-experimental`, everything is approval-only and swipes are refused.
- **WING-030**: `imessage` pairs with `transport: companion`. The Mac is the device install.
- **WING-019**: write evaluations through `ProfileEvaluationRepository.create`. The review queue may rank by evaluation without changing its shape.
- **WING-031**: device-reported execution outcomes (`executed`, `failed`, `unknown`) need a new event type or route in a later additive contract. Rate limits, daily caps and the device-side executor are WING-031's. Nothing in 1.3.0 executes a swipe.
- **WING-032**: identity links exist only through the three user routes; acceptance tests can assert that no other path creates one.
- Android: the enums gain `USER_REPORTED_SENT`, `ASSISTED_DRAFT` and the new audit types, and the labels never upgrade states (user-reported is not sent, a queued swipe is not a swipe).
- `0001` is amended (D-010): new enum types and values, the `platform_connections` columns and CHECKs, a `messages` CHECK, and six tables.
- **Not covered**: no device-side outcome route for swipes (WING-031); server-side ranking landed in contract 1.3.1 (WING-019, D-025: `listProfileReviewQueue` ranks by evaluation and leaves dealbreaker hits out unless `include_excluded=true`); notices are not linked to later conversations. The user can create an identity link, but a notice is never retroactively turned into a message.

## Addendum: contract 1.3.2, profile links (D-032, experimental branch)
- A profile can be attached to one conversation (`putConversationProfileLink`, `getConversationProfileLink`, `deleteConversationProfileLink`) and deleted (`deleteProfile`). The reply generator uses the linked profile's stated text as grounded context and reports the cited fields in `Draft.grounding.profile_fields`.
- The identity rules of section 4 and 5 apply unchanged: a link is the user's choice (`origin: user`) or a stable platform ID match the server re-checks (`origin: device_match`: same platform, and the profile's `external_profile_id` equals the conversation's `external_thread_id` or the contact's platform ID). There is no path, field or score that links by display name, photo or model output.
- `profile_read` of a notification connection may be reported `UNVERIFIED` by the device while the experimental read-only screen reader is on (`docs/integrations/screen_reader.md`); section 8's trust rules are unchanged.
