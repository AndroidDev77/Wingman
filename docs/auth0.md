# Auth0 + Google sign-in setup (I-014)

The owner chose **Auth0** as the identity provider, with **Google** as the social connection
(I-014). This page is the exact setup: the Auth0 tenant, API, Native application and Google
connection, then the backend env values and the Android Gradle properties, then troubleshooting.

**Status:** the code is Auth0-ready but **UNVERIFIED** against a real tenant. The backend verifier
is tested with realistic Auth0-shaped access tokens and a mocked JWKS
(`backend/tests/auth/test_auth0_tokens.py`), and the Android authorization request is unit-tested
(`android/app/src/test/.../core/auth/Auth0RequestTest.kt`). Nobody has signed in on a device yet.
I-014 stays open until the real values are configured and a device sign-in is recorded (see
[Done when](#done-when)).

You need three **non-secret** values from the Auth0 dashboard:

| Value | Where | Example |
|---|---|---|
| Tenant domain | Applications > *Wingman Android* > Settings > **Domain** | `wingman.us.auth0.com` |
| Native app Client ID | Same page > **Client ID** | `aBcD...` (32 characters) |
| API Identifier | Applications > APIs > *Wingman API* > Settings > **Identifier** | `https://api.wingman.app` |

Then one command prints (and with `--write` stores) everything:

```bash
scripts/configure-auth0.sh --write wingman.us.auth0.com <client-id> https://api.wingman.app google-oauth2
```

## 1. Auth0 tenant

1. Sign up at auth0.com and create a tenant. Pick the region closest to the backend. The
   **Domain** looks like `<tenant>.<region>.auth0.com` (e.g. `wingman.us.auth0.com`).
2. One tenant for the beta is fine. Use a separate tenant later for production so test users and
   settings do not mix.

## 2. API (the backend)

Applications > APIs > **Create API**:

| Field | Value |
|---|---|
| Name | `Wingman API` |
| Identifier | `https://api.wingman.app` (any URI; it does not have to resolve). **Cannot be changed later.** This becomes the token `aud` and `WINGMAN_AUTH_AUDIENCE` |
| JSON Web Token (JWT) Profile | Auth0 (default) |
| JSON Web Token Signing Algorithm | **RS256** (the backend accepts only asymmetric algorithms; HS256 is rejected) |

Then on the API's **Settings** tab, under Access Settings:

- **Allow Offline Access: on.** Without it Auth0 issues no refresh token even when the app asks
  for `offline_access`, and users are signed out when the access token expires.
- Token Expiration: the default (86400 s) is fine for the beta. The app refreshes near expiry.
- RBAC / permissions: leave off; Wingman does not use API scopes or permissions.

## 3. Native application (the Android app)

Applications > Applications > **Create Application** > **Native**, named `Wingman Android`.
On its **Settings** tab:

| Field | Value |
|---|---|
| Allowed Callback URLs | `com.wingman.auth:/oauth2redirect` |
| Allowed Logout URLs | `com.wingman.auth:/oauth2redirect` |
| Allowed Web Origins / Allowed Origins (CORS) | empty |
| Refresh Token Rotation | optional; the app stores whichever refresh token Auth0 returns last |

Advanced Settings > **Grant Types**: keep **Authorization Code** and **Refresh Token** checked.
Uncheck Implicit, Client Credentials and Password if present. There is no client secret: the app
is a public client and uses PKCE (AppAuth does this automatically).

The redirect URI uses the custom scheme `com.wingman.auth`. `android/app/build.gradle.kts` sets the
manifest placeholder `appAuthRedirectScheme` from the redirect URI's scheme, falling back to
`com.wingman.auth`, so AppAuth's `RedirectUriReceiverActivity` receives it with no manifest edit.
It must match exactly, character for character, in Auth0 and in `wingman.oidc.redirectUri`.

On the application's **Connections** tab, enable **google-oauth2** and disable the others you do
not want (e.g. Username-Password-Authentication) so Google is the only way in.

## 4. Google social connection

Authentication > Social > **google-oauth2** (create it if it is not listed).

- **For the beta use your own Google OAuth credentials**, not Auth0's developer keys. Auth0 says
  its developer keys are meant strictly for testing and not for production: the consent screen can
  show Auth0's name instead of yours, single sign-on and federated logout do not work properly,
  `prompt=none` fails, and they cannot be used with a custom domain.
- In the Google Cloud console (APIs & Services):
  1. OAuth consent screen: user type External, app name `Wingman`, your support email, scopes
     `openid`, `email`, `profile` only. While the app is in *Testing*, add each beta tester's Google
     account as a test user (or publish the consent screen).
  2. Credentials > Create credentials > **OAuth client ID** > type **Web application**.
     Authorized redirect URI: `https://<tenant-domain>/login/callback`
     (e.g. `https://wingman.us.auth0.com/login/callback`). Authorized JavaScript origin:
     `https://<tenant-domain>`.
  3. Copy the Google **Client ID** and **Client Secret** into the Auth0 google-oauth2 connection.
     The Google client secret lives only in Auth0; it never goes in the repo, the backend or the app.
- Attributes: Basic Profile and Extended Profile (email) are enough.
- On the connection's Applications tab, make sure **Wingman Android** is enabled.

## 5. Backend configuration

```
WINGMAN_AUTH_ISSUER=https://<tenant-domain>/                      # WITH the trailing slash
WINGMAN_AUTH_AUDIENCE=<API Identifier>                            # e.g. https://api.wingman.app
WINGMAN_AUTH_JWKS_URL=https://<tenant-domain>/.well-known/jwks.json
WINGMAN_AUTH_ALGORITHMS=RS256
```

`scripts/configure-auth0.sh` prints exactly these lines. With `--write` it puts them in
`backend/.env` (git-ignored; it refuses any file git would commit, keeps your other lines and a
`.bak` copy). Add `--dry-run` to see the change first. For a hosted backend, put the same four
values in the deployment's env/secret store (they are not secret, but keep them with the rest of
the environment; see [`deployment.md`](deployment.md)).

What the backend checks (unchanged by Auth0; see [`auth.md`](auth.md)):

- `iss` must **equal** `WINGMAN_AUTH_ISSUER` exactly. Auth0's `iss` is `https://<tenant-domain>/`
  with a trailing slash, so the configured issuer must include it.
- `aud` must equal or contain `WINGMAN_AUTH_AUDIENCE`. When the app asks for `openid` plus the
  API audience, Auth0 sends `aud` as a list `[<API Identifier>, "https://<tenant-domain>/userinfo"]`;
  that is accepted. A token whose `aud` lacks the API Identifier (an ID token, a `/userinfo`-only
  token) is rejected.
- Signature: RS256 with the key whose `kid` is in the JWKS. When Auth0 rotates its signing key,
  an unknown `kid` triggers one JWKS refetch (at most once per `WINGMAN_AUTH_JWKS_MIN_REFRESH_S`,
  default 30 s), so rotation needs no restart.
- `sub` is the user's Auth0 ID, for Google users `google-oauth2|<numeric Google ID>`. Wingman keys
  the account on `(iss, sub)`; the first request creates the user and later ones reuse it.
- Auth0 access tokens normally carry no `email` or `name`. That is fine: the user is created with
  no email. (If you later add an Auth0 Action that sets a standard `email` claim on the access
  token, it is recorded at account creation.)

With `WINGMAN_ENV=prod` the JWKS URL must be `https`, and the dev stub must be off. The dev stub
can stay on in `dev`/`test` alongside Auth0: tokens are routed by `iss`, and the two issuers are
different users.

## 6. Android build

Gradle properties (on the command line with `-P...`, or in `~/.gradle/gradle.properties`):

| Property | Value |
|---|---|
| `wingman.useMockApi` | `false` (talk to the real backend) |
| `wingman.oidc.issuer` | `https://<tenant-domain>/` (same as `WINGMAN_AUTH_ISSUER`) |
| `wingman.oidc.clientId` | Native app Client ID |
| `wingman.oidc.redirectUri` | `com.wingman.auth:/oauth2redirect` |
| `wingman.oidc.audience` | API Identifier. **Required for Auth0**: it is sent as the `audience` authorization parameter; without it Auth0 issues an opaque access token the backend cannot verify |
| `wingman.oidc.scopes` | leave unset: the default is `openid profile email offline_access` (`offline_access` gets the refresh token) |
| `wingman.oidc.connection` | optional, `google-oauth2`: sent as the `connection` parameter so the browser goes straight to Google instead of the Auth0 login picker |

`scripts/configure-auth0.sh` prints the full command, e.g.:

```bash
cd android && ./gradlew :app:assembleDebug -Pwingman.useMockApi=false \
  -Pwingman.oidc.issuer=https://wingman.us.auth0.com/ \
  -Pwingman.oidc.clientId=<client-id> \
  -Pwingman.oidc.redirectUri=com.wingman.auth:/oauth2redirect \
  -Pwingman.oidc.audience=https://api.wingman.app \
  -Pwingman.oidc.connection=google-oauth2
```

Debug builds keep the developer dev-token panel next to the Auth0 button, so the dev-stub path
still works. Mock builds (`useMockApi=true`, the default) build no session and need no sign-in.
With no `wingman.oidc.*` properties the sign-in screen says sign-in is not available, as before.

The request the app sends (AppAuth, system browser, authorization code + PKCE S256):
`GET https://<tenant-domain>/authorize?response_type=code&client_id=...&redirect_uri=com.wingman.auth:/oauth2redirect&scope=openid profile email offline_access&audience=<API Identifier>[&connection=google-oauth2]&state=...&nonce=...&code_challenge=...&code_challenge_method=S256`.
AppAuth finds the endpoints through `https://<tenant-domain>/.well-known/openid-configuration`.

## 7. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Every API call is `401` after sign-in; server log `auth rejected: unknown issuer` | `WINGMAN_AUTH_ISSUER` lacks the trailing slash (or has a typo). It must be exactly `https://<tenant-domain>/`. The check is exact on purpose |
| `401`, log `auth rejected: malformed token` | The access token is opaque (not a JWT): the app did not send `audience`. Set `wingman.oidc.audience` to the API Identifier and rebuild. An access token from Auth0 without an audience is only good for `/userinfo` |
| `401`, log mentions `InvalidAudienceError` | `WINGMAN_AUTH_AUDIENCE` and `wingman.oidc.audience` differ, or neither equals the API's Identifier exactly (watch for a trailing slash or `http` vs `https`). Or the app sent the ID token instead of the access token |
| `401`, log `alg 'HS256' not allowed` | The API's signing algorithm is HS256. Change it to RS256 in the API settings |
| `503 upstream_unavailable` | The backend cannot fetch `WINGMAN_AUTH_JWKS_URL`. Check the URL and outbound network. `scripts/configure-auth0.sh --check ...` fetches it |
| Browser shows *Callback URL mismatch* | `com.wingman.auth:/oauth2redirect` is missing from the Native app's Allowed Callback URLs, or differs from `wingman.oidc.redirectUri` |
| Browser redirects but the app never resumes | The redirect scheme and the manifest placeholder differ. Keep the redirect URI on the `com.wingman.auth` scheme (the build derives `appAuthRedirectScheme` from it) |
| Signed out when the access token expires | No refresh token: turn on **Allow Offline Access** on the API, keep the Refresh Token grant on the Native app, keep `offline_access` in the scopes |
| *Access denied* / Google consent error | The Google connection is not enabled for the Native app, or (consent screen in Testing) the Google account is not a test user |
| Auth0 login picker appears | Expected without `wingman.oidc.connection`. Set it to `google-oauth2` to skip the picker |

## Done when

I-014 is resolved only after all of these are recorded (in `docs/DECISIONS.md` and the beta docs):

1. The three real values are configured (`scripts/configure-auth0.sh --write ...`) and the backend
   starts with them (`--check` passes from a machine with network).
2. A debug or release APK built with the printed Gradle command signs in with Google on a device
   or emulator, `GET /v1/me` returns `200`, relaunch keeps the session, and a refresh near expiry
   works.
3. The device checklist in [`android_auth.md`](android_auth.md) is run and its results labeled.

## References (Auth0 documentation)

- Access token profile (sample `iss` `https://my-domain.auth0.com/` with trailing slash; `aud` list
  with the API and `/userinfo`; `sub` format): https://auth0.com/docs/secure/tokens/access-tokens/access-token-profiles
- Get access tokens (`audience` parameter; without it the token is opaque and only valid for
  `/userinfo`; `openid` + API makes `aud` an array; RS256 required for multiple audiences):
  https://auth0.com/docs/secure/tokens/access-tokens/get-access-tokens
- Validate access tokens (at least one `aud` value must be the API identifier):
  https://auth0.com/docs/secure/tokens/access-tokens/validate-access-tokens
- JWKS location `https://{yourDomain}/.well-known/jwks.json`, match by `kid`, expect several keys
  during rotation: https://auth0.com/docs/secure/tokens/json-web-tokens/locate-json-web-key-sets
  and https://auth0.com/docs/secure/tokens/json-web-tokens/validate-json-web-tokens
- Refresh tokens (`offline_access` scope; Allow Offline Access on the API):
  https://auth0.com/docs/secure/tokens/refresh-tokens/get-refresh-tokens and
  https://auth0.com/docs/secure/tokens/refresh-tokens
- Google user ID format `google-oauth2|<id>`: https://auth0.com/docs/manage-users/user-accounts/user-profiles/sample-user-profiles
- Developer keys are for testing only: https://auth0.com/docs/authenticate/identity-providers/social-identity-providers/devkeys
- Domain without `https://`; audience must exactly match the API identifier:
  https://auth0.com/docs/quickstart/backend/java-spring-security5/03-troubleshooting
