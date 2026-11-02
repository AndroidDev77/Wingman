# WING-027: Implement and verify Tinder messaging connector

**Project:** Wingman AI  
**Phase:** 7, platform integrations  
**Recommended branch:** `wing-027-tinder-connector`  
**Dependencies:** WING-006 (connector protocol), WING-011 (automation controller), WING-017 (notification prototype), WING-021 (dating-app feasibility). WING-014/015 needed for final UI integration.  
**Expected ownership:** `android/app/src/main/java/com/wingman/integrations/tinder/`, `backend/tests/connectors/tinder/`, `docs/integrations/tinder.md` (adapt paths to the actual repository).  
**Do not modify:** shared `contracts/`, dispatcher, or generic Android notification listener without a coordinated change request.

## Copy/paste task for coding agent

You are a senior engineer assigned to **one isolated ticket** in the existing Wingman AI repository. **The project has already started.** Inspect the current code and existing contracts before editing. Do not recreate the monorepo, replace working modules, or launch a separate architecture. Follow `AGENT_RULES.md`, the existing `contracts/`, and the connector and automation interfaces as implemented. Work in a separate branch/worktree and produce **one pull request for this ticket**. If a dependency is still in progress, implement non-conflicting tests/fixtures/adapter scaffolding and give the lead agent a precise integration handoff rather than inventing competing interfaces.

Keep read, draft, send, profile-read, and swipe as independent capabilities. A notification that can be read is not proof that it can be replied to. All outgoing messages must pass through the existing automation controller and respect global and per-conversation pause/approval controls. Never claim a real platform capability without a reproducible test on that platform. Mark each outcome `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED`.


### Objective

Build a **Tinder-specific conversation connector** that handles the supported subset of: recognizing incoming messages, associating them with a conversation, importing available context, and sending replies automatically **only when the operating system or an authorized integration provides a validated send path**. Provide an immediately usable *assisted-reply fallback* when auto-send is unavailable. Do not attempt to implement Tinder web APIs by guessing private endpoints.

### Execution steps

1. Inspect connector interfaces, the automation controller, Android notification spike, and the existing feasibility matrix. State which capabilities have actually been observed on a test device and version.
2. Define a Tinder adapter and a capability descriptor. Implement a detector for Tinder package/notification sources; avoid treating lookalike notifications as authenticated Tinder events.
3. Parse supported notification shapes into normalized events (`external_conversation_id` when available, external message ID if available, sender label, body, timestamp, source). Model missing/ambiguous identifiers explicitly; do not accidentally merge different matches with the same display name.
4. If actual Tinder notifications expose `RemoteInput` or another verified reply action, implement a **Tinder-specific** send adapter using it. Confirm observable result, recording **submitted/pending** separately from **delivered**; do not equate an Android action result with message delivery.
5. If sending is unavailable, feature-gate `send=false` and offer a manual copy/share/open-app flow with an AI-generated draft. Do not silently send via simulated taps, hidden endpoints, or unverified mechanisms.
6. Add wiring to the existing message pipeline: user opt-in -> context retrieval -> AI draft -> approval/autopilot policy -> supported send or draft queue -> audit event. Preserve manual takeover.
7. Implement capability probing per app version/device/notification type. Handle duplicate notifications, truncated content, notifications without replies, deleted matches, changed display names, device lock, permissions revoked, and network failures.
8. Write `docs/integrations/tinder.md` documenting tested Android version, Tinder version, test procedure, observed notification actions, supported features, and exact limitations.

### Acceptance criteria

- Deterministic tests parse notification fixtures into connector events and correctly reject non-Tinder/ambiguous events.
- Replaying the same notification or outbound idempotency key never sends two messages.
- The connector advertises `send=false` until a real, tested reply mechanism is available.
- If a send mechanism exists, an explicit end-to-end test shows **one** inbound notification -> AI-generated draft -> permitted send on a test conversation; separately document whether delivery was actually verified.
- Without send capability, the same message is routed to a visible approval/draft workflow with no false send confirmation.
- Revoking notification access or disabling autopilot stops new automatic actions immediately.
- No production credentials or personal chat text are committed to source control or test fixtures.

### Test scenarios

Unit: capability detection, normalizer, sender matching, duplicate events, missing RemoteInput, automation paused.  
Integration: connector-to-dispatcher mock, correct acknowledgments, failure retries.  
Device: record reproducible steps with test account, Android/Tinder versions, screenshots or **redacted** logs; mark unexecuted cases `UNVERIFIED`.

### Required handoff

At completion, report:

1. Branch name and suggested PR title.
2. Exact files created/modified and any shared contracts that need approval.
3. Commands/tests run, their results, and whether a real account/device was tested.
4. Capability matrix with `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED` for **read / draft / send / profile / swipe**.
5. Known limitations, integration/permission blockers, and follow-up tasks.
6. Merge checklist for the coordinating agent.

**Do not mark the real integration as delivered if it only works with fixtures or mocks.**

