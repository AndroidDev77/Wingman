# Wingman AI: Task Index

This package contains 32 independently assignable engineering tickets (WING-027..032 added 2026-11-01). Each ticket has precise dependencies, file ownership, deliverables, and acceptance criteria. Ticket IDs are stable; use their IDs in git branches and pull requests.

**Priority:** Complete the real message receive -> AI draft -> approval/auto-send -> audit workflow before chasing unsupported dating-app swipes.

Live status lives in [`docs/STATUS.md`](docs/STATUS.md).

| Ticket | Task | Depends on | Phase |
|---|---|---|---|
| [WING-001](tasks/WING-001_bootstrap-the-monorepo.md) | Bootstrap the monorepo | none | 1 |
| [WING-002](tasks/WING-002_define-canonical-api-contracts-and-architecture.md) | Define canonical API contracts and architecture | WING-001 | 1 |
| [WING-003](tasks/WING-003_implement-postgresql-domain-persistence.md) | Implement PostgreSQL domain persistence | WING-002 | 1 |
| [WING-004](tasks/WING-004_implement-account-authentication-and-user-isolation.md) | Implement account authentication and user isolation | WING-003 | 1 |
| [WING-005](tasks/WING-005_create-provider-agnostic-llm-gateway.md) | Create provider-agnostic LLM gateway | WING-002 | 2 |
| [WING-006](tasks/WING-006_implement-messaging-connector-sdk-and-simulator.md) | Implement messaging connector SDK and simulator | WING-002 | 2 |
| [WING-007](tasks/WING-007_implement-intent-and-writing-style-profiles.md) | Implement intent and writing-style profiles | WING-003 | 2 |
| [WING-008](tasks/WING-008_implement-conversation-memory-extraction-and-retrieval.md) | Implement conversation memory extraction and retrieval | WING-003, WING-005 | 2 |
| [WING-009](tasks/WING-009_implement-conversation-analysis-and-stage-tracking.md) | Implement conversation analysis and stage tracking | WING-005, WING-008 | 2 |
| [WING-010](tasks/WING-010_generate-personalized--grounded-replies.md) | Generate personalized, grounded replies | WING-005, WING-007, WING-008, WING-009 | 2 |
| [WING-011](tasks/WING-011_implement-automation-controller-and-message-dispatch.md) | Implement automation controller and message dispatch | WING-004, WING-006, WING-010 | 2 |
| [WING-012](tasks/WING-012_build-android-shell-and-typed-api-client.md) | Build Android shell and typed API client | WING-002 | 3 |
| [WING-013](tasks/WING-013_create-onboarding--relationship-mode-and-style-settings-ui.md) | Create onboarding, relationship-mode and style settings UI | WING-007, WING-012 | 3 |
| [WING-014](tasks/WING-014_create-unified-inbox-and-conversation-views.md) | Create unified inbox and conversation views | WING-006, WING-012 | 3 |
| [WING-015](tasks/WING-015_build-approval-queue--audit-timeline--and-takeover.md) | Build approval queue, audit timeline, and takeover | WING-011, WING-012, WING-014 | 3 |
| [WING-016](tasks/WING-016_validate-and-implement-android-sms-connector.md) | Validate and implement Android SMS connector | WING-006, WING-012 | 3 |
| [WING-017](tasks/WING-017_prototype-android-notification-direct-reply-support.md) | Prototype Android notification direct-reply support | WING-006, WING-012 | 3 |
| [WING-018](tasks/WING-018_add-date-proposal-and-calendar-workflows.md) | Add date proposal and calendar workflows | WING-003, WING-011 | 4 |
| [WING-019](tasks/WING-019_build-compatibility-scoring-and-discovery-prototype.md) | Build compatibility scoring and discovery prototype | WING-005, WING-007 | 4 |
| [WING-020](tasks/WING-020_explore-macos-imessage-companion-feasibility.md) | Explore macOS iMessage companion feasibility | WING-006 | 4 |
| [WING-021](tasks/WING-021_validate-tinder--bumble-and-hinge-integration-paths.md) | Validate Tinder, Bumble and Hinge integration paths | WING-006 | 4 |
| [WING-022](tasks/WING-022_instrument-analytics--cost-and-operational-logs.md) | Instrument analytics, cost and operational logs | WING-003, WING-011 | 5 |
| [WING-023](tasks/WING-023_prepare-ci--developer-environments-and-deploy-path.md) | Prepare CI, developer environments and deploy path | WING-001, WING-003 | 5 |
| [WING-024](tasks/WING-024_add-subscription-entitlements-and-billing-stub.md) | Add subscription entitlements and billing stub | WING-004, WING-022 | 5 |
| [WING-025](tasks/WING-025_integrate-and-run-end-to-end-acceptance-tests.md) | Integrate and run end-to-end acceptance tests | WING-011, WING-013, WING-014, WING-015, WING-016, WING-023 | 6 |
| [WING-026](tasks/WING-026_prepare-private-beta-release.md) | Prepare private beta release | WING-025 | 6 |

## Where to start

1. Give Agent A `WING-001`.
2. After Agent A merges the scaffold, give Agent A `WING-002`.
3. After contracts merge, run `WING-003`, `WING-005`, `WING-006`, and `WING-012` in separate worktrees.
4. Keep a dedicated integration owner to merge contract and application wiring changes.

Read `EXECUTION_PLAN.md` for concurrency waves and MVP cutline.

## Phase 7–8: platform integrations (added 2026-11-01)

Order: prerequisites WING-017, WING-019, WING-020, WING-021 and contract v1.3 first; then WING-027/028/029 (after 017 + 021), WING-030 (after 020), WING-031 (after 019); WING-032 last. A tested mock is not evidence of a working commercial-app integration; every capability carries VERIFIED / UNVERIFIED / UNSUPPORTED / BLOCKED.

**Status (2026-11-02):** WING-017, 019, 020, 021, 027, 028, 029, 030 (PR #54) and 031 (PR #55) and contracts v1.3.0/v1.3.1 are merged; **WING-032 is next**. Per D-023, Tinder and Bumble are notification-assisted only; Hinge's unofficial-API connector is experimental and compiled only into the flag-on build (D-026, D-027). WING-031 is a review workflow: no platform has a working swipe and nothing sets `executed` (D-027). WING-030's companion is UNVERIFIED on a real Mac (D-028, I-034, I-035). `BLOCKED` is a test-result word in handoffs, not a capability label (ADR 0008 §8). Live status: [`docs/STATUS.md`](docs/STATUS.md).

| Ticket | Task | Depends on | Phase |
|---|---|---|---|
| [WING-027](tasks/WING-027_tinder-message-connector.md) | Tinder message connector (notification-assisted only, D-023) | WING-006, 011, 017, 021 | 7 |
| [WING-028](tasks/WING-028_bumble-message-connector.md) | Bumble message connector (notification-assisted only, D-023) | WING-006, 011, 017, 021 | 7 |
| [WING-029](tasks/WING-029_hinge-message-connector.md) | Hinge message connector (next; experimental API per D-023) | WING-006, 011, 017, 021 | 7 |
| [WING-030](tasks/WING-030_imessage-macos-companion.md) | iMessage macOS companion | WING-006, 011, 020 | 7 |
| [WING-031](tasks/WING-031_discovery-and-swiping.md) | Profile discovery and swiping | WING-006, 007, 011, 019, 021 | 7 |
| [WING-032](tasks/WING-032_cross-platform-e2e.md) | Cross-platform E2E and release gates | WING-011, 015, 025, 027–031 | 8 |
