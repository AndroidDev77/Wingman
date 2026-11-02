# WING-030: Build iMessage macOS companion and backend connector

**Project:** Wingman AI  
**Phase:** 7, platform integrations  
**Recommended branch:** `wing-030-imessage-companion`  
**Dependencies:** WING-006 (connector SDK), WING-011 (automation), WING-020 (Mac feasibility spike); WING-014/015 for inbox and takeover.  
**Expected ownership:** `macos/wingman-companion/`, `backend/tests/connectors/imessage/`, `docs/integrations/imessage_companion.md`.  
**Environment requirement:** A test Mac with Messages configured is needed to verify actual iMessage read/send.

## Copy/paste task for coding agent

You are a senior engineer assigned to **one isolated ticket** in the existing Wingman AI repository. **The project has already started.** Inspect the current code and existing contracts before editing. Do not recreate the monorepo, replace working modules, or launch a separate architecture. Follow `AGENT_RULES.md`, the existing `contracts/`, and the connector and automation interfaces as implemented. Work in a separate branch/worktree and produce **one pull request for this ticket**. If a dependency is still in progress, implement non-conflicting tests/fixtures/adapter scaffolding and give the lead agent a precise integration handoff rather than inventing competing interfaces.

Keep read, draft, send, profile-read, and swipe as independent capabilities. A notification that can be read is not proof that it can be replied to. All outgoing messages must pass through the existing automation controller and respect global and per-conversation pause/approval controls. Never claim a real platform capability without a reproducible test on that platform. Mark each outcome `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED`.


### Objective

Build a **user-run macOS companion application** that can, subject to actual macOS permissions and supported behavior, observe selected Apple Messages conversations and submit user-authorized outgoing replies through the Mac. Pair it securely with the Wingman backend. This is **not** a claim that Apple exposes an iPhone-wide or cloud iMessage API.

### Execution steps

1. Review WING-020 results; identify tested macOS versions, Messages database availability, AppleScript/Automation capability, and permission requirements. Choose a documented, narrow implementation path. Do not silently fall back to undocumented remote services.
2. Create a lightweight Mac app or daemon with a visible permission/onboarding interface, connection status, enable/pause switch, and clear explanation that the Mac must be available for automation.
3. Build an **incremental read** adapter using only local data the user grants access to. Where reading a local Messages database is feasible, use read-only snapshots/queries with correct handling for SQLite WAL updates, checkpoints, edit/reaction events, and group chats. Avoid writing to Messages databases.
4. Implement send via user-authorized macOS automation **only if tested**. Surface OS permission denial and distinguish submitted, script success, and verified delivery. If AppleScript proves unreliable, expose draft-only mode rather than pretending sends work.
5. Implement a local-to-backend paired connection with device registration, scoped authentication, protected tokens, revocation, retry/backoff, idempotency, and a bounded event queue. Avoid exposing the Mac to unsolicited inbound internet traffic by default.
6. Map chats/messages to stable IDs. Treat iMessage vs SMS/RCS routed through Messages as separate transport attributes when identifiable. Do not claim RCS, group messages, attachments, read receipts, or reactions unless tested.
7. Connect all generated replies to backend approval/autopilot policies before invoking the companion send endpoint. Enforce per-contact pause and emergency global kill switch even after queued events.
8. Add fixture-backed tests runnable on non-Mac CI, plus a **separate** manual real-Mac test script. Document required macOS versions, Full Disk Access/Automation consent if applicable, and recovery after permissions are revoked.

### Acceptance criteria

- A test fixture can yield an incoming message through the connector and appear in the unified inbox.
- Pairing/authentication and revocation tests pass; the companion never exposes unrestricted unprotected send endpoints.
- Identity and message deduplication survive restart and retry.
- Manual takeover and global pause prevent pending automatic sends.
- On an actual Mac, demonstrate a send only if granted permissions and actually tested. Record macOS version, Messages account setup, action result, and delivery observation separately.
- With no test Mac, deliver the fixture-backed implementation but explicitly mark real iMessage send `UNVERIFIED` (not completed).
- Support an explicit disconnect, local state cleanup, and privacy-conscious logs.

### Failure cases to test

Mac offline/asleep; backend unreachable; Mac permissions rejected/revoked; multiple accounts; group conversation; duplicate messages; reactions/edits; recent history unavailable; locked keychain; conversation deleted; AppleScript unsupported; companion restarts during send.

### Required handoff

At completion, report:

1. Branch name and suggested PR title.
2. Exact files created/modified and any shared contracts that need approval.
3. Commands/tests run, their results, and whether a real account/device was tested.
4. Capability matrix with `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED` for **read / draft / send / profile / swipe**.
5. Known limitations, integration/permission blockers, and follow-up tasks.
6. Merge checklist for the coordinating agent.

**Do not mark the real integration as delivered if it only works with fixtures or mocks.**

