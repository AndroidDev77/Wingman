# WING-003: Implement PostgreSQL domain persistence

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 1  
**Dependencies:** WING-002  
**Ownership / expected edit paths:**
- `backend/app/db/`
- `backend/alembic/`
- `backend/tests/db/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Build durable data storage and migrations for conversations, messages, preferences, agent decisions, dates, and subscription entitlements.

### Required implementation
- SQLAlchemy 2 async models and repositories with foreign keys and unique constraints
- Alembic initial migration
- Tables: users, user_preferences, style_profiles, platform_connections, contacts, conversations, messages, conversation_memories, agent_actions, automation_rules, date_proposals, subscriptions, usage_events
- Per-user tenant-scoping conventions, indexes, and retention-ready timestamps
- Database fixture and migration tests

### Acceptance tests / definition of done
- Clean database can migrate up/down in test environment
- Duplicate external message IDs do not create duplicate records
- Rows from one user cannot be returned using another user scope
- All nullable vs required fields match contracts

### Constraints and integration notes
Own all models and migrations. Other agents must request schema changes rather than editing migrations.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
