# WING-018: Add date proposal and calendar workflows

**Project:** Wingman AI, Android-first AI dating assistant  
**Phase:** 4  
**Dependencies:** WING-003, WING-011  
**Ownership / expected edit paths:**
- `backend/app/services/dates.py`
- `backend/app/integrations/calendar/`
- `backend/app/api/dates.py`
- `backend/tests/dates/`

## Copyable agent task prompt

You are a senior engineer implementing one isolated, production-quality task in the Wingman AI monorepo. The application is an Android-first AI dating assistant with user-selected dating intentions, personalized messaging, and automated sends only through verified, user-enabled connector capabilities.

Read `AGENT_RULES.md`, `TASK_INDEX.md`, and relevant files in `contracts/` before coding. If dependencies are not present, report them and create only non-conflicting preparatory code. Do not rewrite unrelated modules. Work in a dedicated git branch/worktree and provide a pull request-style handoff.

### Your specific mission
Move willing conversations from interest to date proposals without making unauthorized calendar commitments.

### Required implementation
- DateProposal lifecycle: suggested, offered, accepted, confirmed, canceled
- Calendar provider interface and Google Calendar adapter when credentials available
- Available-slot computation, user-defined venue/area preferences, reminders
- Approval requirement for final calendar event creation unless explicitly opted in
- Mock-calendar tests and API endpoints

### Acceptance tests / definition of done
- A proposed time is not treated as confirmed
- Confirmed events are created once despite retries
- No invitation is sent without matching authorization policy
- Missing calendar access produces reviewable proposal instead of failure cascade

### Constraints and integration notes
Keep calendar model provider-independent; Google credentials must be user-supplied.

### Required work sequence
1. Inspect the repository state and read applicable contracts.
2. Identify exactly which files you own and list any dependency or integration blocker.
3. Implement the smallest functional solution, not only documentation or pseudocode (except for explicitly designated feasibility spikes).
4. Add tests for core behavior, including negative/error paths.
5. Run tests, lint, and build steps that your environment supports. Clearly mark unrun tests.
6. Update relevant documentation and provide a concise handoff report with files changed, commands/results, limitations, and next integration steps.

**Do not claim a dating-platform send, swipe, or device integration was verified without actually performing the test.**
