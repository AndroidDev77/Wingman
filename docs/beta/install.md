# Install and set up the beta

Owner: WING-026. For testers and for the operator who supports them. Read
[`known_limitations.md`](known_limitations.md) first: this is a limited beta.

Labels: **VERIFIED** (a run proved it), **UNVERIFIED** (built, not proven where it matters),
**UNSUPPORTED** (not built). Each step below says whether it was actually run, and where.

## 0. What you need

| You are | You need |
|---|---|
| **Tester, emulator** (no SMS on a real phone) | A computer with Android Studio (emulator) or the Android SDK `platform-tools` (`adb`), the APK from the operator, a Google account the operator has allowed (or a dev token from the operator), and a backend: the operator's, or your own local one (§2) |
| **Tester, physical phone with SMS** | A **spare or secondary** Android phone (Android 8.0 / API 26 or newer; Android 10+ preferred) with a SIM, USB debugging, `adb`, the APK, a dev token, the operator's HTTPS backend URL baked into the APK. Accept that Wingman becomes the default SMS app while you test and MMS stops working (§5). **This path is UNVERIFIED end to end**: you are among the first to run it |
| **Operator** | This repository, Python 3.12, `uv`, Postgres 16 (or Docker), JDK 17 and the Android SDK 35 to build the APK |

## 1. Get the APK

The operator builds it and sends you the file, its SHA-256 and the backend it talks to:

```bash
scripts/release/check.sh                                   # refuses to continue on any failure
scripts/release/build.sh --api-base-url https://<beta-host>/   # or no flag: emulator + local backend
# -> build/release/0.1.0-beta.1/wingman-0.1.0-beta.1-debug.apk, SHA256SUMS, BUILD_INFO
```

The APK is a **debug** build (versionName `0.1.0-beta.1`, versionCode `2`). It has **Auth0 sign-in
with Google** for the beta tenant (`build.sh` passes `config/beta/auth0.gradle.properties` as
`-Pwingman.oidc.*` flags; §3) and keeps the developer dev-token panel as a fallback, because Google
sign-in has not yet been verified on a device (I-014, [`../auth0.md`](../auth0.md)). It talks to the
real API (`-Pwingman.useMockApi=false`). The backend URL is fixed in the APK at build time:

| Backend | Build with | Works on |
|---|---|---|
| Local backend on your computer, app on the emulator | default (`http://10.0.2.2:8000/`) | Emulator only (10.0.2.2 is the emulator's name for your computer) |
| Local backend, app on a USB-connected phone | `--api-base-url http://127.0.0.1:8000/`, then `adb reverse tcp:8000 tcp:8000` | Physical phone over USB (**UNVERIFIED**: not run) |
| Operator's hosted backend | `--api-base-url https://<beta-host>/` | Emulator or phone. Must be HTTPS: debug builds allow plain HTTP only to 10.0.2.2/localhost/127.0.0.1 |

Check the file, then install it:

```bash
sha256sum -c SHA256SUMS                 # must print: wingman-0.1.0-beta.1-debug.apk: OK
adb install -r wingman-0.1.0-beta.1-debug.apk
```

Without a computer you can also copy the APK to the phone and open it; Android asks you to allow
installs from that app ("Install unknown apps"). On Android 13+, a sideloaded app's SMS permissions
may be marked "restricted settings"; granting the default-SMS-app role through the system dialog
(§5) is the supported path (UNVERIFIED per OEM).

To remove the app later: `adb uninstall com.wingman` (or long-press the icon, Uninstall). Restore
your SMS app first (§5.4).

## 2. Backend

### 2.1 Operator-hosted (most testers)

Use the HTTPS URL the operator gives you. Nothing to install. The operator follows
[`release_checklist.md`](release_checklist.md) §2 to §4.

### 2.2 Your own local backend (technical testers)

Single-process dev mode: the API and the background worker in one process, the dev sign-in stub,
the labeled `[dev mock]` drafts (no LLM key needed), Postgres 16.

```bash
git clone https://github.com/AndroidDev77/Wingman.git && cd Wingman
cp .env.example .env
docker compose up -d postgres              # or any empty Postgres 16 you own
export WINGMAN_ENV=dev
export WINGMAN_DATABASE_URL=postgresql+asyncpg://wingman:wingman@localhost:5432/wingman
export WINGMAN_AUTH_DEV_STUB_ENABLED=true
export WINGMAN_AUTH_DEV_SECRET="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
(cd backend && uv sync && uv run alembic upgrade head)
scripts/dev-run.sh                         # http://127.0.0.1:8000, Ctrl-C to stop
curl http://127.0.0.1:8000/health          # {"status":"ok","version":"0.1.0","env":"dev",...}
```

Keep the same `WINGMAN_AUTH_DEV_SECRET` in the shell where you mint tokens (§3); a new secret
invalidates every token.

The Compose alternative (`docker compose --profile backend up --build`) runs `migrate`, `backend`
and `worker` as separate containers. With the default `mock` LLM provider the plain API has no dev
draft handler, so **every draft comes back `no_send`** there (observed while writing this guide);
use `scripts/dev-run.sh` unless you configure a real provider.

## 3. Sign in

Two ways in. **Google via Auth0** (§3.0) is the real one and is configured for the beta tenant,
but it is **UNVERIFIED**: nobody has signed in with it on a device or emulator yet (I-014). The
**dev-stub token** (§3.1, §3.2) still works in the beta's debug APK and is the fallback.

### 3.0 Google sign-in (Auth0 beta tenant)

The beta tenant's values are public identifiers (no secrets), committed in `config/beta/`:

| | |
|---|---|
| Tenant | `dev-zumw4z1t7m8hefst.us.auth0.com` |
| Issuer | `https://dev-zumw4z1t7m8hefst.us.auth0.com/` (trailing slash required) |
| JWKS | `https://dev-zumw4z1t7m8hefst.us.auth0.com/.well-known/jwks.json` |
| Native app Client ID | `XYOVrRYWqOQXj2JH5cEyrGTUg0ypq750` |
| API audience | `https://api.wingman.app` |
| Connection | `google-oauth2` |
| Redirect | `com.wingman.auth:/oauth2redirect` |

**Operator, backend.** Add the four Auth0 lines to `backend/.env` (or your deployment's env store),
next to the §2.2 settings, then restart the backend:

```bash
scripts/configure-auth0.sh --write --beta     # writes these four lines into backend/.env
```
```
WINGMAN_AUTH_ISSUER=https://dev-zumw4z1t7m8hefst.us.auth0.com/
WINGMAN_AUTH_AUDIENCE=https://api.wingman.app
WINGMAN_AUTH_JWKS_URL=https://dev-zumw4z1t7m8hefst.us.auth0.com/.well-known/jwks.json
WINGMAN_AUTH_ALGORITHMS=RS256
```

The dev stub can stay enabled alongside (tokens are routed by issuer). Run the backend with
`scripts/dev-run.sh` (§2.2).

**Operator, APK.** `scripts/release/build.sh` (§1) already includes the Auth0 flags. To build and
install straight onto a running emulator instead:

```bash
cd android && ./gradlew :app:installDebug -Pwingman.useMockApi=false \
  -Pwingman.oidc.issuer=https://dev-zumw4z1t7m8hefst.us.auth0.com/ \
  -Pwingman.oidc.clientId=XYOVrRYWqOQXj2JH5cEyrGTUg0ypq750 \
  -Pwingman.oidc.redirectUri=com.wingman.auth:/oauth2redirect \
  -Pwingman.oidc.audience=https://api.wingman.app \
  -Pwingman.oidc.connection=google-oauth2
```

**Tester.** Your Google account must be allowed by the operator (a test user while the Google
consent screen is in *Testing*). On an emulator use a Google APIs or Google Play image with
internet access (it needs a browser).

1. Open Wingman and tap **Continue in browser**.
2. The browser opens Google's account chooser. Pick your account and accept the consent screen.
3. The browser closes and Wingman opens; the first sign-in starts onboarding
   ([`walkthrough.md`](walkthrough.md)).

**Success:** you are in the app; the backend log shows `GET /v1/me` `200` with no `auth rejected`
lines; after killing and relaunching the app you are still signed in. Report it to the operator,
who records it under I-014 in `docs/DECISIONS.md`.

**If it fails:**

| Symptom | Fix |
|---|---|
| No **Continue in browser**, only *Sign-in is not available in this build* | The APK was built without the Auth0 flags (plain `./gradlew`, `scripts/android-build.sh`, or `build.sh --no-auth0`). Rebuild as above |
| *Could not reach the sign-in service* | No internet on the device/emulator. In its browser, `https://dev-zumw4z1t7m8hefst.us.auth0.com/.well-known/openid-configuration` must show JSON |
| *Callback URL mismatch* in the browser | Operator: add `com.wingman.auth:/oauth2redirect` to the Auth0 Native app's Allowed Callback URLs |
| Google *Access blocked* | Operator: add your Google account as a test user on the Google consent screen |
| Back at the sign-in screen right after the browser; backend log `auth rejected: unknown issuer` | Operator: `WINGMAN_AUTH_ISSUER` missing or without its trailing slash; fix `backend/.env` and restart |
| Backend log `auth rejected: malformed token` | The APK lacks `-Pwingman.oidc.audience=https://api.wingman.app`; rebuild |

More causes: [`../auth0.md`](../auth0.md#7-troubleshooting). Until a Google sign-in has worked, use
the dev token below.

The dev-stub token is a short-lived HS256 token signed with the backend's secret.

- **Why it's beta-only.** Anyone holding the secret can mint a token for any user, and tokens are
  never refreshed. The backend refuses to start with the stub when `WINGMAN_ENV=prod`, and release
  APKs have no panel to paste a token into. It is acceptable only for a closed group of known
  testers, with the secret held by the operator.

### 3.1 Get a dev token

The operator mints one per tester (each tester a different `--sub`, which is your user identity):

```bash
cd backend
uv run python -m app.security.dev_stub --sub beta-jd --name "J.D." --ttl-hours 72
```

It prints one line: the token. When it expires, the app returns to the sign-in screen; ask for a
new one (same `--sub`, same account).

### 3.2 Paste it into the app

1. Open Wingman. Below **Continue in browser** the screen shows **Developer sign-in (debug build
   only)** (a `--no-auth0` build shows only that panel).
2. Paste the token into **Dev token** (on an emulator: `adb shell input text "<token>"` with the
   field focused) and tap **Use dev token**.
3. First sign-in opens onboarding ([`walkthrough.md`](walkthrough.md)).

**Observed:** on the CI emulator (API 34), sign-in with a minted token, then onboarding opened on
its own (`e2e_results.md` A1, A2). Not observed on a physical phone.

### 3.3 Optional: API check of the whole flow (no phone needed)

For operators and technical testers: the same flow the app drives, over HTTP, against a `mock`
(simulated) connection. Fictional contact. Requires `scripts/dev-run.sh` (§2.2).

```bash
API=http://127.0.0.1:8000
TOKEN=$(cd backend && uv run python -m app.security.dev_stub --sub beta-check --name "Beta Check" | tail -n1)
H="Authorization: Bearer $TOKEN"; key() { python3 -c 'import uuid; print(uuid.uuid4())'; }

curl -s $API/v1/me -H "$H"                                   # your user
curl -s $API/v1/preferences -H "$H"                          # version 0, automation "manual"
curl -s -X PUT $API/v1/preferences -H "$H" -H 'Content-Type: application/json' -H 'If-Match: "0"' \
  -d '{"intent":{"mode":"serious","custom_description":null},"dealbreakers":[],"style":{},
       "automation":{"mode":"assisted","active_hours":null,"reply_delay_seconds":null,
                     "daily_send_limit":null,"approval_required_for":[]}}'
CID=$(curl -s -X POST $API/v1/connections -H "$H" -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' \
  -d '{"platform":"mock","display_name":"Beta simulator"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
USER_ID=$(curl -s $API/v1/me -H "$H" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
EV=$(key); NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST $API/v1/connections/$CID/events -H "$H" -H "Idempotency-Key: $EV" -H 'Content-Type: application/json' -d "{
  \"schema_version\":\"connector_event.v1\",\"event_id\":\"$EV\",\"type\":\"mock.inbound_message_injected\",
  \"platform\":\"mock\",\"connection_id\":\"$CID\",\"user_id\":\"$USER_ID\",
  \"external_message_id\":\"beta-in-1\",\"external_thread_id\":\"thread-alex\",\"occurred_at\":\"$NOW\",\"observed_at\":\"$NOW\",
  \"payload\":{\"sender\":{\"external_contact_id\":\"alex\",\"display_name\":\"Alex (fictional)\"},
               \"text\":\"Hey! How was the hike on Saturday?\",\"mock\":{\"scenario_id\":\"beta_install\",\"sequence\":1}}}"
curl -s "$API/v1/approvals?status=pending" -H "$H"            # one approval, draft "[dev mock] ..."
AP=$(curl -s "$API/v1/approvals?status=pending" -H "$H" | python3 -c 'import sys,json;print(json.load(sys.stdin)["items"][0]["id"])')
curl -s -X POST $API/v1/approvals/$AP/approve -H "$H" -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' \
  -d '{"edited_text":"It was great, thanks! Clear skies the whole way up. How was your weekend?"}'
curl -s "$API/v1/agent-actions?limit=20" -H "$H"             # the audit timeline, newest first
```

## 4. Onboarding and daily use

See [`walkthrough.md`](walkthrough.md): intent, style, autonomy (stays Manual unless you opt in),
the Approvals tab (To approve / Controls / Activity), pause versus takeover, and unpairing.

## 5. SMS pairing and the default-SMS-app role

**Status: UNVERIFIED on a physical phone.** The whole SMS path ran on the CI emulator (receive,
approve, send through `SmsManager`, server `sent`), but an emulator has no SIM, radio or carrier
(`e2e_results.md`, "emulator SMS stack exercised"). On a real phone, nobody has run it yet (I-003).
Every SMS reply needs your approval; autopilot is not available for SMS.

### 5.1 What becoming the default SMS app means

Android lets only the default SMS app receive texts as they arrive and keep them in your message
history. So, while paired:

- **Wingman is your SMS app.** Your usual SMS app becomes read-only until you switch back.
- **MMS is not handled: picture messages, group chats, and long texts your carrier converts to MMS
  are not received or sent while Wingman holds the role.** Use a secondary phone, or switch back
  after each session.
- Wingman writes received and sent texts into the system SMS store (best effort, UNVERIFIED per OEM),
  so they should still appear in your usual app after you switch back.
- Sync happens **when you open the app** (and after pairing/unpairing). There is no background
  sync yet (I-024): a reply you approve is sent the next time the app syncs, so keep the app open
  while testing.

### 5.2 Pair

In the app: **Settings > Connections > Phone SMS**.

1. **Make Wingman the default SMS app**: read the explanation, tap it, accept the system dialog.
2. **Allow SMS access**: grant receive, send and read SMS.
3. **Pair this phone's SMS**. If it fails (no network), tap again: it reuses the same request and
   never creates a duplicate.
4. The section must show `Status: Connected`, `Send replies: UNVERIFIED (not yet tested on a
   device)` and `Autopilot: not available for SMS`. If it shows anything else, stop and report it.

Observed on the CI emulator with the role granted by `adb` (not the dialog): the same three lines
(`e2e_results.md`, SMS step 2). The pairing API was also checked by hand while writing this guide:
a new SMS connection is stored with `read` and `send` UNVERIFIED and the other capabilities
UNSUPPORTED.

### 5.3 Unpair

**Settings > Connections > Phone SMS > Disconnect**, read the dialog, confirm. Queued replies and
pending approvals for that connection are canceled; a reply the phone already started sending
cannot be recalled. Wingman stops reading and sending texts. Over HTTP this is `F8` (PASS); the
in-app flow is UNVERIFIED on a phone.

### 5.4 Restore your previous SMS app

Do this after every session and before uninstalling:

- **Settings > Apps > Default apps > SMS app**, pick your usual app (Google Messages, Samsung
  Messages, ...). Wording differs by manufacturer.
- Or, with USB debugging: `adb shell cmd role add-role-holder android.app.role.SMS <package>`
  (e.g. `com.google.android.apps.messaging`), then check with
  `adb shell cmd role get-role-holders android.app.role.SMS`.
- Then unpair in Wingman (§5.3) if you are done testing SMS. Wingman notices it lost the role and
  stops treating SMS as readable (D12 in the device checklist; UNVERIFIED).

## 6. Device and emulator support matrix

| Target | Status | Evidence |
|---|---|---|
| Emulator: API 34, `google_apis`, x86_64, `pixel_6` profile, KVM, GitHub `ubuntu-24.04` runner | **Tested in CI** on every PR and on `main` (app drives backend: sign-in, onboarding, inbox, approve, activity, takeover; emulator SMS round trip) | `.github/workflows/e2e.yml` job `emulator`; `e2e_results.md` |
| Any other emulator API level (26 to 33, 35) or image (`google_apis_playstore`, ARM) | UNVERIFIED (not run) | minSdk 26 and targetSdk 35 compile in CI |
| Any physical Android phone | **UNVERIFIED** (never run, I-003) | Checklist: `docs/integrations/android_sms.md` §7 (D1 to D12, B1 to B6, P1 to P8) |
| Dual-SIM phones | UNVERIFIED; replies may leave from the default SMS SIM, not the one the text arrived on | `android_sms.md` §5 |
| Android below 8.0 (API < 26) | UNSUPPORTED (will not install) | `minSdk = 26` |
| Tablets / devices without telephony | App runs; SMS pairing refused ("This device can't send or receive SMS") | JVM-tested capability probe |
| iOS | UNSUPPORTED (no app) | |
| JVM unit tests (398) and both build variants compile | VERIFIED in CI (`android` job) | `ci.yml` |

## 7. What was actually validated for this guide (2026-11-01)

Run by the WING-026 agent in a Linux sandbox (Python 3.12, `uv`, Postgres 16 binaries, OpenJDK 21,
Android SDK 35, **no KVM, no emulator, no phone, no Docker daemon**), on `main` @ `266243a` plus
this branch's scripts:

| Step | Result |
|---|---|
| §1 `scripts/release/build.sh --skip-image` (APK only) | PASS: `wingman-0.1.0-beta.1-debug.apk`; `aapt2 dump badging` shows `versionCode='2' versionName='0.1.0-beta.1'`; `BUILD_INFO`, `SHA256SUMS` written. With `--api-base-url http://127.0.0.1:8000/`, the generated `BuildConfig.API_BASE_URL` was that URL |
| §1 backend image tag | Not built (no Docker daemon); `build.sh --dry-run` printed `docker build -t wingman-backend:0.1.0-beta.1-<sha>` |
| §1 `scripts/release/check.sh` | PASS, `PRE-RELEASE CHECKS PASSED (4/4)`: backend-check 954 passed; `CONTRACT VALIDATION PASSED`; `MIGRATION CHECK PASSED (18 tables at head)`; E2E 17 passed, every zero-duplicate counter 0. It printed `Release scope: LIMITED PRIVATE BETA only (G5 blocked ...)`. `check.sh --paid` exited 3 (`PAID LAUNCH REFUSED: G5 is blocked`) |
| §2.2 migrate + `scripts/dev-run.sh` | PASS against an empty Postgres 16 (throwaway cluster instead of `docker compose up postgres`): `alembic upgrade head` to `0001`, `/health` `{"status":"ok","version":"0.1.0","env":"dev"}` |
| §2.2 Compose | Not run here (no Docker daemon); Compose with `--profile backend` was verified by WING-023 |
| §2.2 note on `app.main` + `mock` | Observed: with the plain API and `WINGMAN_LLM_PROVIDER=mock`, the inbound pipeline logged `config_error` and audited `draft_generated` with reason `model_unavailable`; no approval was created |
| §3.1 minting a token | PASS: `GET /v1/me` `200` with the token, `401` without |
| §3.3 API flow | PASS: preferences `version 1`, `serious`, `assisted`; mock connection `connected` with `send` VERIFIED (simulator only); inbound `202 accepted`; one pending approval with reasons `assisted_mode, first_message` and a `[dev mock]` draft; approve-with-edit; the outbound message `sent` with exactly the edited text, origin `approved_draft`; audit `message_received, analysis_updated, draft_generated, approval_requested, approval_approved, send_queued, send_attempted, send_acknowledged` |
| Takeover / resume over HTTP | PASS: `taken_over`, then `active`; audit `automation_takeover`, `automation_resumed` |
| SMS pairing over HTTP (fictional install ID) | PASS: stored `read`/`send` UNVERIFIED, others UNSUPPORTED; unpair (body `{}`) gave `disconnected`, every capability UNSUPPORTED |
| Another tester's token on the first tester's conversation | `404` (isolation) |
| Account deletion by SQL ([checklist §5](release_checklist.md#5-privacy-and-data-handling)) | PASS: every user-owned row removed |
| §3.2, §4, §5 in the app; §1 `adb install` | **Not run here** (no emulator, no phone). The emulator equivalents run in CI (`e2e_results.md` A1 to A6, SMS steps 1 to 4); the phone steps are UNVERIFIED |

### 7.1 Integrator re-run (2026-11-01, PR #36 head `c3cdd08`)

Re-run by the integrator from this document as written, in the same kind of sandbox (no Docker
daemon, so an empty Postgres 16 cluster with role and database `wingman`/`wingman` on
`localhost:5432` stood in for `docker compose up -d postgres`):

| Step | Result |
|---|---|
| §2.2 `cp .env.example .env`, the four exports, `uv sync`, `alembic upgrade head`, `scripts/dev-run.sh` | PASS: migrated to `0001`; `/health` `{"status":"ok","version":"0.1.0","env":"dev",...}` |
| §3.1 / §3.3 token: `GET /v1/me` | PASS: `200` with the token, `401` without |
| §3.3 every command, copied verbatim | PASS after one fix: preferences `version 1` (`serious`, `assisted`); mock connection; inbound `202 accepted`; one pending approval with a `[dev mock]` draft (reasons `assisted_mode`, `first_message`); approve-with-edit; outbound `sent`, origin `approved_draft`, exactly the edited text; audit `preferences_updated, connection_paired, message_received, analysis_updated, draft_generated, approval_requested, approval_approved, send_queued, send_attempted, send_acknowledged` |
| **Fix** | The listing used `?state=pending`; the contract's parameter is `status` (`listApprovals`). The unknown parameter was silently ignored, so the command only worked on a fresh account. Now `?status=pending` |
| SMS pairing over HTTP (`contracts/examples/connection.create.sms.request.json`, which claims nothing VERIFIED) then unpair | PASS: `connected`, `read`/`send` UNVERIFIED, others UNSUPPORTED; unpair gave `disconnected`, every capability UNSUPPORTED |
| A second tester's token on the first tester's conversation | `404` |
| `metrics.md` queries (`psql -v ON_ERROR_STOP=1`) on that database | All execute: WAU 2, one approved draft sent and edited, 1 assisted conversation, 0 confirmed dates |
| `scripts/release/check.sh` | `PRE-RELEASE CHECKS PASSED (4/4)`: 954 backend tests, contracts, migrations (18 tables), E2E 17 passed in 190 s. `check.sh --dry-run --paid`: exit 3. A stub repository whose `migration-check.sh` fails stops at `[3/4]`, exit 1, E2E not run |
| §1 APK build, §3.2 / §4 / §5 in the app, `adb install` | Not run by the integrator (no Android SDK or emulator in this sandbox); the emulator equivalents are CI (`E2E Android emulator`) |
