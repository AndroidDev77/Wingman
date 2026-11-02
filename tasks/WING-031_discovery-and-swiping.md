# WING-031: Implement profile discovery and verified swiping workflows

**Project:** Wingman AI  
**Phase:** 7, discovery and actions  
**Recommended branch:** `wing-031-profile-actions`  
**Dependencies:** WING-006 (connector capabilities), WING-007 (intent/preference profiles), WING-011 (automation controller), WING-019 (compatibility scoring), WING-021 (integration feasibility). WING-027–029 provide platform-specific evidence but need not all be finished to build manual discovery.  
**Expected ownership:** `backend/app/discovery/`, `backend/tests/discovery/`, `android/app/src/main/java/com/wingman/discovery/`, `docs/integrations/swiping.md` (align with repository structure).

## Copy/paste task for coding agent

You are a senior engineer assigned to **one isolated ticket** in the existing Wingman AI repository. **The project has already started.** Inspect the current code and existing contracts before editing. Do not recreate the monorepo, replace working modules, or launch a separate architecture. Follow `AGENT_RULES.md`, the existing `contracts/`, and the connector and automation interfaces as implemented. Work in a separate branch/worktree and produce **one pull request for this ticket**. If a dependency is still in progress, implement non-conflicting tests/fixtures/adapter scaffolding and give the lead agent a precise integration handoff rather than inventing competing interfaces.

Keep read, draft, send, profile-read, and swipe as independent capabilities. A notification that can be read is not proof that it can be replied to. All outgoing messages must pass through the existing automation controller and respect global and per-conversation pause/approval controls. Never claim a real platform capability without a reproducible test on that platform. Mark each outcome `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED`.


### Objective

Build the **profile evaluation and swipe-action pipeline** across supported connectors, starting with user-reviewed choices and moving to automatic swiping **only** if a platform offers a tested action interface. Do not confuse a mock swipe or AI recommendation with a Tinder/Bumble/Hinge swipe.

### Execution steps

1. Inspect the established `profile_read` and `swipe` capability contracts, user dating modes, matching preferences, and any WING-027/028/029 integration findings. Propose narrowly scoped contract additions only if necessary.
2. Define a normalized profile record for provider, external profile ID, visible biography/prompts, available attributes, source timestamp, and provenance. Do not manufacture missing information or infer that an image alone reveals private attributes.
3. Create a profile import/source interface. Support a mock profile feed and a manual user-shared profile flow for the MVP. Enable live provider reads only when a connector has a verified permitted capability.
4. Integrate WING-019 compatibility scoring; return a score **with reasons and known unknowns**, user-selected relationship intention, configurable dealbreakers, and confidence metadata. Keep scoring separate from execution.
5. Implement a review queue with actions `like`, `pass`, and `skip` and an **independent** execute gate. If the connector cannot swipe, allow the user to record their decision but never falsely show the platform action as completed.
6. Provide explicit per-platform auto-swipe enablement only for verified `swipe=true` connectors. Add daily/action rate limits, pause, duplicate protection, device/API failure recovery, and actionable audit events.
7. Add deterministic simulated profile fixtures and tests for scoring, capability denial, duplicate action keys, preference changes, stale profiles, and action acknowledgments.
8. Produce a capability table covering Tinder/Bumble/Hinge: `profile_read`, `like`, `pass`, `superlike`/equivalent if applicable, and confidence of actual integration. Separate `UNVERIFIED` from `UNSUPPORTED`.

### Acceptance criteria

- Mock/manual profile flow displays ranked profiles, explanations, and an approval queue.
- `swipe` never executes when advertised capability is false, the user disabled it, or the profile is stale/ambiguous.
- Each user action has a stable idempotency key, request status, and audit record.
- Per-platform automatic swiping is feature-gated until a **real account/device test** establishes working execution; never mark simulated results as verified.
- Users can change intentions and preferences without contaminating prior match records.
- Unit and integration tests run without production dating-app accounts.
- The final report identifies whether any actual automatic swipe was demonstrated; if none, deliver the review workflow and list platform integration blockers precisely.

### Test scenarios

Review queue from mock profiles; preference changes; unsupported swipe; repeated like; device offline; auto-swipe disabled; action canceled; request succeeds but acknowledgment missing; private profile fields absent; provider updates that invalidate a profile.

### Required handoff

At completion, report:

1. Branch name and suggested PR title.
2. Exact files created/modified and any shared contracts that need approval.
3. Commands/tests run, their results, and whether a real account/device was tested.
4. Capability matrix with `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED` for **read / draft / send / profile / swipe**.
5. Known limitations, integration/permission blockers, and follow-up tasks.
6. Merge checklist for the coordinating agent.

**Do not mark the real integration as delivered if it only works with fixtures or mocks.**

