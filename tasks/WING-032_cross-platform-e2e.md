# WING-032: Cross-platform connector integration and real-device acceptance suite

**Project:** Wingman AI  
**Phase:** 8, integration and release validation  
**Recommended branch:** `wing-032-platform-e2e`  
**Dependencies:** WING-011, WING-015, WING-025, and deliverables or explicit blocker reports from WING-027, WING-028, WING-029, WING-030, WING-031; WING-016/017 for Android transport.  
**Expected ownership:** `tests/e2e/platforms/`, `scripts/e2e/platforms/`, `docs/integrations/cross_platform_test_matrix.md`, `docs/integrations/release_gates.md`. Coordinate any shared production wiring with integrator.  
**Environments:** Android test phone(s), Mac with Messages for iMessage testing, test accounts for dating apps when available, and backend staging environment.

## Copy/paste task for coding agent

You are a senior engineer assigned to **one isolated ticket** in the existing Wingman AI repository. **The project has already started.** Inspect the current code and existing contracts before editing. Do not recreate the monorepo, replace working modules, or launch a separate architecture. Follow `AGENT_RULES.md`, the existing `contracts/`, and the connector and automation interfaces as implemented. Work in a separate branch/worktree and produce **one pull request for this ticket**. If a dependency is still in progress, implement non-conflicting tests/fixtures/adapter scaffolding and give the lead agent a precise integration handoff rather than inventing competing interfaces.

Keep read, draft, send, profile-read, and swipe as independent capabilities. A notification that can be read is not proof that it can be replied to. All outgoing messages must pass through the existing automation controller and respect global and per-conversation pause/approval controls. Never claim a real platform capability without a reproducible test on that platform. Mark each outcome `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED`.


### Objective

Build and execute an **evidence-based end-to-end suite** that establishes which integrations actually work on which platforms. Validate the complete chain from inbound message/profile to AI suggestion, approved automatic action where available, status reporting, audit, and user takeover. Do not turn unavailable integrations into green checks.

### Execution steps

1. Inventory actual merged connector implementations and their versioned capability descriptors. Collect evidence and outstanding blockers from WING-016/017 and WING-027 through WING-031.
2. Define a reproducible test matrix with rows for Android SMS/MMS, Tinder, Bumble, Hinge, iMessage Mac companion, and profile/swipe actions. For each record: OS/device/app version; read; conversation identity; AI draft; auto-send; real delivery; profile-read; swipe; approval mode; status; evidence link; date tested.
3. Build deterministic E2E tests using mocks for all shared workflows: incoming message -> normalize/dedupe -> memory -> agent -> approval/autopilot policy -> send or unavailable fallback -> acknowledgment -> audit -> inbox display.
4. Add permission/failure scenarios: notifications disabled, SMS role revoked, Mac offline, automation denied, expired connection, app upgrade, rate limits, duplicate events, retry after timeout, manual takeover mid-flight, and user switching a conversation from serious to casual intent.
5. Create manual test scripts for each real app. Specify test setup, controlled message input, expected behavior, user action, exact artifact to capture, and pass/fail criteria. Use **redacted** screenshots/logs.
6. Execute supported tests on real devices when available. Distinguish `PASSED ON DEVICE`, `PASSED WITH MOCK`, `FAILED`, `BLOCKED`, `NOT RUN`. Record what was executed; never infer a real send from passing backend tests.
7. Enforce feature gates in the release configuration. Any feature without verified real integration must be disabled or clearly labeled **assisted-only / experimental**, not advertised as fully autonomous.
8. Generate a concise release verdict per platform and a prioritized list of blockers for the lead agent. Provide repeatable commands and a CI test entry point.

### Mandatory E2E cases

- Incoming SMS -> contextual AI reply -> configured auto-send -> outgoing record and device-observed outcome.
- Tinder, Bumble, Hinge: notification -> correct source/type and thread handling -> reply if verified, otherwise assisted draft; notification without reply action must never produce a send claim.
- iMessage: Mac companion online -> receive/read -> approved send if verified -> status; and Mac offline -> recover or safely queue.
- Profile discovery -> compatibility assessment -> approved like/pass; unsupported swipe must not execute.
- Per-conversation automation override, global kill switch, permissions revoked, retries, idempotency, content/identity ambiguity, and interaction with calendar/date suggestions.
- Cross-platform continuity where the **user explicitly links** a dating-app match and phone contact; never auto-merge based solely on names or inferred identity.

### Acceptance criteria

- Mock E2E suite is deterministic and runnable in CI with no paid account dependencies.
- Each real connector has a test result and a verifiable evidence trail, **including `BLOCKED` or `NOT RUN` where appropriate**.
- A failure of any permission/capability check cannot result in an outgoing send.
- No duplicate auto-send after retries, notification updates, or process restarts.
- UI and release configuration match actual per-platform capabilities; unsupported integrations are not advertised as functional.
- Final report explicitly answers: *Which apps can we read? Which can we actually reply to? Which support real swiping? Which combinations were tested?*
- Publish a go/no-go recommendation for releasing each automation feature, with remaining blockers prioritized.

### Deliverables

1. Runnable CI/mocked E2E suite and repeatable local commands.
2. Real-device test scripts and evidence log.
3. Platform capability and acceptance matrix.
4. Release feature gates and rollout notes.
5. Integrator handoff with exact blocked vs ready scope.

### Required handoff

At completion, report:

1. Branch name and suggested PR title.
2. Exact files created/modified and any shared contracts that need approval.
3. Commands/tests run, their results, and whether a real account/device was tested.
4. Capability matrix with `VERIFIED`, `UNVERIFIED`, `UNSUPPORTED`, or `BLOCKED` for **read / draft / send / profile / swipe**.
5. Known limitations, integration/permission blockers, and follow-up tasks.
6. Merge checklist for the coordinating agent.

**Do not mark the real integration as delivered if it only works with fixtures or mocks.**

