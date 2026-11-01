# Wingman AI: Agent Working Agreement

## Mission
Create an Android-first, multi-connector AI dating assistant that can generate personalized, contextual replies and, on verified and user-enabled messaging connectors, send them automatically. Support serious relationships, casual dating, mutually interested adult casual physical relationships, open-to-anything, and custom goals. Keep the LLM engine separate from transport-specific adapters.

## Required engineering conventions
- Read `TASK_INDEX.md`, `contracts/`, and the assigned ticket before editing code. If the referenced contract isn't merged, request it instead of inventing a competing one.
- Use Python 3.12+, FastAPI, SQLAlchemy 2, Alembic, PostgreSQL, Kotlin, Jetpack Compose, and Gradle. The initial backend can run without live model keys through mock providers.
- **One task, one branch/worktree, one pull request.** Use branch name `wing-###-short-name`. Avoid editing files owned by other active tickets.
- Treat `contracts/` as the source of truth. Propose contract changes separately and coordinate with the integrator.
- Separate `read`, `draft`, `send`, `swipe`, and `calendar_write` as independently advertised capabilities. Do not infer that access to notifications means direct send or swiping is possible.
- Never claim a real integration succeeded without a reproducible device test. Label results **VERIFIED**, **UNVERIFIED**, or **UNSUPPORTED**.
- Use deterministic mocks for AI responses, messaging, and calendar tests. Never require production API keys for the basic test suite.
- Outbound messages must pass through one automation controller, which checks connection capability, user opt-in, contact pause state, and required approvals. Treat LLM output as suggestions, not authorization.
- Use per-user isolation, idempotency keys, redacted logging, and controlled retries. Record actual delivery acknowledgments and don't claim delivery on a queued send.
- Do not fabricate personal details in messages or propose a confirmed date without confirmation.
- Implement the narrowest working slice; avoid adding new technologies or sweeping refactors unless necessary.

## Mandatory agent handoff
At the end of the task, report:
1. Files created or modified.
2. Design/contract choices and interface names.
3. Commands executed and tests **actually passed**.
4. Unverified behavior and blockers.
5. Any integration work the parent/integrator must perform.
6. Pull request title and a short merge checklist.
