# WING-004: Implement account authentication and user isolation

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 1  
**Dependencies:** WING-003  
**Ownership / expected edit paths:**
- `backend/app/api/auth.py`
- `backend/app/services/auth.py`
- `backend/app/security/`
- `backend/tests/auth/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Add authenticated API access with explicit user context and consistent authorization semantics.

### Required implementation
- OIDC-compatible JWT verification interface, issuer/audience checks, expiry validation
- Dev-only authentication stub isolated from production config
- GET /me and auth dependency for protected endpoints
- User-scoped repository access helpers and tests
- Docs explaining local development credentials and configuration

### Acceptance tests / definition of done
- Unauthenticated protected calls are rejected
- Cross-user access is denied for conversations and profiles
- Invalid or expired tokens fail
- No auth bypass available in production configuration

### Constraints and integration notes
Do not build a custom password database.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
