# Beta release checklist (operator)

Owner: WING-026. Work top to bottom. Every box needs evidence (a command output, a CI link, a
file). Record the filled-in copy in the release PR or the release issue, not in this file.

## 0. Go / no-go summary

| Decision | Status today (2026-11-01, `main` @ `266243a`) | Rule |
|---|---|---|
| **Limited private beta** (invited adult testers, unpaid, approval on every SMS) | **GO**, once sections 1 to 6 below are ticked for the build | All seven CI jobs green on the release commit; `scripts/release/check.sh` passes; config and privacy checks done |
| **SMS autopilot** for any tester | **NO-GO** | Impossible while SMS `send` is UNVERIFIED: the backend never stores a client-claimed VERIFIED (D-016) and the policy forces approval (`capability_unverified`). Requires G5 |
| **Paid launch** (any price, subscription, or in-app purchase) | **NO-GO** | **No paid launch without one reliable real message connector.** G5 is ⛔ (I-003). `scripts/release/check.sh --paid` refuses (exit 3) while `docs/STATUS.md` marks G5 blocked. Also needs a real IdP (I-014), real-LLM evaluation (I-009) and WING-024 |
| **Public release / Play Store** | **NO-GO** | Same as paid, plus Play's SMS-permission policy: a default SMS app that drops MMS is very unlikely to pass review (`docs/integrations/android_sms.md` §4) |

A "reliable real message connector" means, at minimum: the physical-device checklist in
`docs/integrations/android_sms.md` §7 run and recorded (`## Device run YYYY-MM-DD`) with D3, D4, B3
passing (the minimum for the operator `verify` CLI), and D5 to D7, B1 to B6, P1 to P8 passing too,
on at least two device models; then a beta period on those devices with no unwanted send and no
duplicate send.

## 1. Version and build

- [ ] `scripts/release/version.sh show` prints the intended version (first beta: `0.1.0-beta.1 2`).
      To move on: `scripts/release/version.sh bump beta` (or `patch` / `minor`), commit `version.env`.
- [ ] `git status` clean, on the commit to release, and that commit is on `main`.
- [ ] **All seven CI jobs green on that commit** (D-011, D-018): Backend, Migrations, Contracts,
      Backend image, Android, E2E backend, E2E Android emulator.
      `gh api repos/AndroidDev77/Wingman/commits/<sha>/check-runs --jq '.check_runs[]|"\(.name) \(.conclusion)"'`
- [ ] If `E2E backend` is red only in X1 with `DeadlockDetectedError` in the server log, that is
      the known intermittent ingest deadlock ([known_limitations.md](known_limitations.md#backend-and-operations)):
      re-run the job once and record both runs; any other failure, or a duplicate counter above 0,
      blocks the release.
- [ ] `scripts/release/check.sh` passes locally (backend checks, contracts, migrations, backend E2E;
      stops at the first failure). It prints `Release scope: LIMITED PRIVATE BETA only` while G5 is
      blocked. That is expected for this beta.
- [ ] `scripts/release/build.sh --api-base-url https://<your-beta-host>/` builds:
  - `build/release/<version>/wingman-<version>-debug.apk` (`versionName` = version,
    `versionCode` from `version.env`, set through Gradle properties; no Gradle file is edited),
  - `SHA256SUMS` and `BUILD_INFO`,
  - the backend image `wingman-backend:<version>-<short sha>`.
  For a tester on an emulator against a local backend, leave out `--api-base-url` (default
  `http://10.0.2.2:8000/`). Plain `http` is allowed only to `10.0.2.2`, `localhost`, `127.0.0.1`
  (debug network-security config); any other host must be `https`.
- [ ] Check the APK: `aapt2 dump badging <apk> | head -1` shows `versionCode`/`versionName`.
- [ ] Push the image to your registry under the same tag (never `latest`).

**Auth0 sign-in in the APK.** `build.sh` passes the committed beta tenant
(`config/beta/auth0.gradle.properties`: tenant `dev-zumw4z1t7m8hefst.us.auth0.com`, client
`XYOVrRYWqOQXj2JH5cEyrGTUg0ypq750`, `google-oauth2`) as `-Pwingman.oidc.*` flags, so the APK has
**Continue in browser** (Google via Auth0). Its dry run prints them (`sign-in: Auth0 ...`) and
`BUILD_INFO` records `auth0_issuer`/`auth0_client_id`. `-Pwingman.oidc.<key>=<value>` on the
`build.sh` command line overrides one value; `--no-auth0` builds a dev-token-only APK. The backend
must carry the matching `WINGMAN_AUTH_*` lines (`config/beta/auth0.env`, or
`scripts/configure-auth0.sh --write --beta`). Google sign-in is **UNVERIFIED** until someone has
signed in on a device or emulator (I-014; steps in [`../auth0.md`](../auth0.md#sign-in-on-an-emulator-with-the-beta-tenant)).

**Why a debug APK.** Auth0 sign-in is not yet verified (I-014), so the fallback sign-in is the
dev-token panel, which exists only in debug builds (`src/debug`; `docs/android_auth.md`). A release
build has no fallback. The debug build also carries the SMS
test harness (`SmsDemoActivity`, no launcher icon) and allows cleartext only to loopback hosts.
This is acceptable for a closed beta of known testers; it is a blocker for anything wider.

## 2. Backend configuration

The beta backend is one operator-run environment. Nothing has been deployed anywhere yet
(`docs/deployment.md`); the Azure sample is UNVERIFIED. Pick one:

| Option | Command | Notes |
|---|---|---|
| **A. Single process (recommended for this beta)** | `scripts/dev-run.sh` behind an HTTPS reverse proxy | API + worker in one process (I-016), so reply delays and retries work; refuses `WINGMAN_ENV=prod` |
| B. Compose | `docker compose --profile backend up --build` | Separate `migrate`, `backend`, `worker`. Mock sends with a delay stay queued (I-016); fine for SMS-only testers |
| C. Azure sample | `infra/azure/deploy.sh` | **UNVERIFIED**, never run. Image default `WINGMAN_ENV=prod`, which **refuses the dev stub**, so testers could not sign in. Not usable until an IdP exists |

Required environment (see `docs/deployment.md` for every variable):

- [ ] `WINGMAN_ENV=dev`. **Decision for this beta:** the dev auth stub is the sign-in, so the
      environment cannot be `prod` (the app refuses to start with the stub in prod, by design).
      Consequences: the unauthenticated WING-006 demo router `/dev/connectors/mock/*` is mounted
      (single fixed demo user, in-memory, touches no tester data); block `/dev/` at the reverse
      proxy. Mock connections can be paired.
- [ ] `WINGMAN_AUTH_DEV_STUB_ENABLED=true` and `WINGMAN_AUTH_DEV_SECRET` = a fresh random value
      (`python -c 'import secrets; print(secrets.token_urlsafe(48))'`), kept only on the server and
      the operator's machine. Anyone with it can mint a token for any user: treat it like a root
      password. Rotate it to log everyone out.
- [ ] `WINGMAN_DATABASE_URL` from a secret store, `postgresql+asyncpg://...` (add `?ssl=require` for
      a managed server). Postgres 16.
- [ ] **LLM provider.** Choose and record one:
  - `WINGMAN_LLM_PROVIDER=mock` with `scripts/dev-run.sh`: every draft is the deterministic,
    labeled `[dev mock]` text. Safe, useless for judging quality. Note: the plain API
    (`uvicorn app.main:app`, Compose `backend`) with `mock` has **no** dev handler, so every draft
    is `no_send` (`model_unavailable`); observed while writing this checklist.
  - `WINGMAN_LLM_PROVIDER=openai` with `WINGMAN_LLM_API_KEY` (secret), `WINGMAN_LLM_BASE_URL`,
    `WINGMAN_LLM_MODEL`, and the price variables so cost is recorded. **UNVERIFIED** (I-009): the
    provider has never called a real endpoint. Before testers see it, run the
    [smoke test](#llm-smoke-test) and read every draft yourself. `anthropic` is not implemented.
- [ ] HTTPS in front of port 8000 with a real certificate (release builds allow no cleartext; debug
      builds only to loopback).
- [ ] `WINGMAN_REDIS_URL` may be left at its default: Redis is not used by any code yet.

### LLM smoke test

With the real provider configured, a throwaway dev token, a `mock` connection and assisted mode,
inject five fictional messages ([`install.md` §3.3](install.md#33-optional-api-check-of-the-whole-flow-no-phone-needed)
shows the calls) covering: small talk; a question about a fact the user never stated (expect
`ask_user`, never an invented fact); a request to meet (expect approval reason
`date_or_commitment`); an explicit message (expect `no_send`); "please stop texting me" (expect
the stage to move to `paused`). Record the outcome; do not invite testers if any draft invents a
personal detail or escalates.

## 3. Database and migrations

- [ ] Fresh database, or a backup taken (`pg_dump`) before upgrading.
- [ ] `alembic upgrade head` (in `backend/`: `uv run alembic upgrade head`; Compose: the `migrate`
      service; image: `docker run ... wingman-backend:<tag> alembic upgrade head`).
- [ ] `uv run alembic current` shows the single head (`0001`, D-010: one initial migration until
      the first production deployment; after that, `0001` is frozen and new migrations are additive).
- [ ] `scripts/migration-check.sh` passed in CI on the release commit (up, down, up, no drift).

## 4. Worker

- [ ] The worker runs: with option A it is inside `scripts/dev-run.sh`; with option B, the
      `worker` service is `healthy` (`docker compose ps`).
- [ ] It handles approval expiry (pending approvals expire after 24 h), device lease expiry
      (120 s; outcome unknown, never retried, D-016) and due sends.
- [ ] Log level `INFO`; `DEBUG` only briefly (per-tick heartbeat lines).

## 5. Privacy and data handling

- [ ] Every tester accepted the [tester terms](README.md#tester-terms) (18+, own conversations only).
- [ ] Testers are told what the server stores: message text (inbound and outbound), contact display
      names and phone numbers (SMS), drafts, approvals, memory facts with their source messages, and
      the audit log (IDs, codes and counts only, no message text).
- [ ] Application logs carry no message text (`docs/architecture.md`, Data handling;
      `docs/deployment.md` worker logs). Spot-check: `grep` the server log for a word from a test
      message. Expect no hits.
- [ ] With a real LLM: the provider receives the bounded conversation context for each draft.
      Tell testers which provider, and check its data-retention terms before inviting them.
- [ ] Database access limited to the operator; backups encrypted; the dev-stub secret and LLM key
      in a secret store, never in the repo or the image.
- [ ] **Account deletion** (no API yet): every user-owned table cascades from `users.id`, so the
      operator deletes a tester's data with one statement, `DELETE FROM users WHERE id = '<id>';`
      (find the id with `GET /v1/me` using the tester's token, or by `auth_subject`). Verified on a
      scratch database while writing this checklist: rows in `agent_actions`, `approvals`,
      `connector_event_ingests`, `contacts`, `conversations`, `drafts`, `idempotency_keys`,
      `messages`, `platform_connections`, `style_profiles`, `user_preferences` all went to 0.
      Memory facts can also be deleted one by one in the API (`deleteMemory`).
- [ ] On the phone: unpairing stops all SMS work; uninstalling removes the app-private SMS state
      file (`files/wingman-sms-state.json`, which can contain message text while envelopes wait
      for upload; `allowBackup=false`).
- [ ] Data retention for the beta decided and told to testers (suggested: delete all tester data
      30 days after the beta ends).

## 6. Tester readiness

- [ ] [`install.md`](install.md) sent with the APK, its SHA-256, the backend URL and a dev token
      per tester (`--sub` unique per tester, e.g. `beta-<initials>`; `--ttl-hours` up to what you
      are comfortable with; tokens are not refreshed).
- [ ] [`known_limitations.md`](known_limitations.md) and [`release_notes.md`](release_notes.md)
      sent with it.
- [ ] A feedback channel and the [incident procedure](feedback_form.md#incident-an-unwanted-or-wrong-send)
      staffed: the operator can pause a tester's automation (`POST /v1/automation/pause` with their
      token) and revoke access (rotate the dev secret, or delete the user).
- [ ] Metrics baseline collected per [`metrics.md`](metrics.md) (the audit-log queries there).

## 7. After each beta week

- [ ] Triage feedback and incidents; update [`roadmap.md`](roadmap.md) and `known_limitations.md`.
- [ ] If anyone ran the physical-device SMS checklist, record it in
      `docs/integrations/android_sms.md` and update G5 in `docs/STATUS.md`. Only then may an
      operator run `python -m app.services.connections verify ... --evidence <link>`.
