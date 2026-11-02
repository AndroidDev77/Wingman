# Android sign-in and token provider (I-011)

The Android app calls the backend with `Authorization: Bearer <access token>`, as described in
[`auth.md`](auth.md). This page covers the client side: where tokens come from, how they are
stored and refreshed, and how to sign in on a debug build today.

**Identity provider: Auth0 with Google sign-in** (chosen for I-014). The tenant, API and Native app
setup, the Gradle values and troubleshooting are in [`auth0.md`](auth0.md);
the beta tenant's values are committed in `config/beta/auth0.gradle.properties`, which
`scripts/release/build.sh` passes as `-P` flags (plain `./gradlew` and CI do not), and
`scripts/configure-auth0.sh --beta` prints the exact Gradle command.

**Status:** the pure-Kotlin core (session, refresh, OkHttp wiring, dev-token rules) is JVM-tested.
The Android pieces (Keystore cipher, AppAuth, the Compose sign-in screen) are compiled by CI but
have not run on a device, and OIDC has never run against the real Auth0 tenant (configured in
`config/beta/`, but no sign-in yet, I-014). Treat them as **UNVERIFIED**.

## Code map

| Path | Purpose |
|---|---|
| `core/auth/TokenProvider.kt` | `TokenProvider` interface: `accessToken()`, `refreshAfterUnauthorized()`, `onRetryUnauthorized()`, `signOut()`. `StaticTokenProvider` for a fixed token |
| `core/auth/AuthSession.kt` | The app's session: implements `TokenProvider`, exposes `state: StateFlow<AuthState>`, `signIn()`, `signInWithDevToken()` |
| `core/auth/BearerAuth.kt` | `BearerTokenInterceptor`, `BearerTokenAuthenticator`, `OkHttpClient.Builder.bearerAuth(provider)` |
| `core/auth/TokenStore.kt` | `TokenStore`, `EncryptedTokenStore` (cipher + blob store), `InMemoryTokenStore` (tests) |
| `core/auth/DevToken.kt` | Validation of a pasted dev-stub token; refuses unless the build is debug |
| `core/auth/OidcConfig.kt` | OIDC settings from build config; null (sign-in disabled) when unconfigured |
| `core/auth/android/KeystoreStorage.kt` | `KeystoreSecretCipher` (AES-256-GCM, Android Keystore key), `SharedPrefsBlobStore` |
| `core/auth/android/AppAuthOidc.kt` | AppAuth: discovery, authorization code + PKCE in the system browser, code exchange, refresh |
| `core/auth/android/AndroidAuth.kt` | Process-wide session and AppAuth client |
| `features/auth/SignInScreen.kt`, `AuthGate.kt`, `SignOutButton.kt` | Sign-in screen, gate rules, sign-out control |
| `src/debug/.../features/auth/DevSignIn.kt` | Debug builds only: the "paste dev token" panel |
| `src/release/.../features/auth/DevSignIn.kt` | Release builds: no panel (`null`) |

## Request flow

1. `BearerTokenInterceptor` asks `AuthSession.accessToken()`. If the token expires within 60 s
   (`DEFAULT_SKEW_MS`; the backend's leeway is 30 s), the session refreshes first. Refreshes run
   under one mutex and re-check the token, so many concurrent requests cause one refresh.
2. With a token, the request gets `Authorization: Bearer ...`; signed out, it gets no header.
3. On `401`, `BearerTokenAuthenticator` asks for a refresh once and retries once with the new token.
   If the retried request also gets `401`, the session signs out and the `401` goes to the caller.
   It never retries a third time. A `401` on a request without a token is returned as-is.
4. Refresh outcomes: success replaces the tokens (keeping the old refresh token if the IdP omits
   one); `invalid_grant` and other OAuth token errors sign out; a network or IdP outage keeps the
   session but fails the request (an offline phone is not signed out).

When the session signs out, `WingmanApp` replaces the whole shell with the sign-in screen. After
sign-in the shell is composed again from scratch, so `OnboardingGate` runs as usual.

The SMS uploader and outbox poller use the same `container.api`, so they authenticate the same
way. While signed out their calls get `401` and envelopes stay queued (`RetryLater`).

Mock-API builds (`USE_MOCK_API=true`, the default) build no session: there is no sign-in screen
and the Keystore is never used.

## Token storage

`EncryptedTokenStore` serializes the session to JSON, encrypts it with AES-256-GCM using a
non-exportable key in the Android Keystore (alias `wingman.auth.session.v1`), and keeps only the
Base64 ciphertext in the private `wingman_auth` SharedPreferences file. The manifest already has
`allowBackup="false"`. A blob that cannot be decrypted (a changed key, corruption) is deleted and
the user is signed out. Sign-out deletes it with `commit()`.

Tokens are never logged. `AuthTokens.toString()` is redacted, the auth log writes only fixed
messages (`AuthLogger`), and `BearerAuthTest.tokensNeverAppearInLogs` checks this with sentinel
values.

## Debug builds: sign in with a dev-stub token (works today)

The backend dev stub signs HS256 tokens with a secret that exists only on the developer's machine.
The app never sees that secret. You mint a token on your machine and paste it into the app.

1. Start the backend with the stub enabled (`backend/.env`, see [`auth.md`](auth.md)):
   ```
   WINGMAN_ENV=dev
   WINGMAN_AUTH_DEV_STUB_ENABLED=true
   WINGMAN_AUTH_DEV_SECRET=<python -c 'import secrets; print(secrets.token_urlsafe(48))'>
   ```
2. Build a debug APK against the real API and install it on an emulator:
   `cd android && ./gradlew :app:installDebug -Pwingman.useMockApi=false`
   (`API_BASE_URL` is `http://10.0.2.2:8000/`, the host's loopback seen from the emulator).
3. Mint a token: `cd backend && uv run python -m app.security.dev_stub --sub alice`
   (add `--email`/`--name` if you like; tokens last 12 hours, `--ttl-hours` changes that).
4. Copy it to the emulator's clipboard (or `adb shell input text "<token>"` into the field), paste
   it under **Developer sign-in (debug build only)** and tap **Use dev token**.
5. The app checks that it is a JWT with an unexpired `exp`, stores it encrypted and opens the app.
   Dev tokens are never refreshed: when one expires or the API refuses it, the app returns to the
   sign-in screen and you mint a new one.

The dev path cannot reach a release build:
- the paste panel exists only in `src/debug`; `src/release` defines `devSignInPanel = null`;
- `AuthSession.signInWithDevToken` refuses unless it is passed `BuildConfig.DEBUG == true`;
- `AuthSupportTest.devSignInSourceSetsAreSplitByBuildType` checks both source sets and that no
  `DEV_SECRET` appears in app sources, and `testDebugUnitTest` depends on `compileReleaseKotlin`,
  so CI also compiles the release variant.

## OIDC sign-in (Auth0)

Real sign-in uses AppAuth (`net.openid:appauth`): authorization code + PKCE (S256), `state` and
`nonce`, in the system browser (Custom Tabs). There is no WebView and no password field. Until it
is configured, the sign-in screen says that sign-in is not available in this build.

Set these Gradle properties (`-P...` or `~/.gradle/gradle.properties`; never commit real values to
the repo if they are environment-specific):

| Property | Example | Meaning |
|---|---|---|
| `wingman.oidc.issuer` | `https://login.example.com/` | Exact `iss`; must equal the backend's `WINGMAN_AUTH_ISSUER`. https only |
| `wingman.oidc.clientId` | `wingman-android` | Public client registered at the IdP (no client secret) |
| `wingman.oidc.redirectUri` | `com.wingman.auth:/oauth2redirect` | Redirect URI registered at the IdP. Its scheme becomes the `appAuthRedirectScheme` manifest placeholder |
| `wingman.oidc.scopes` | `openid profile email offline_access` (default) | Must include `openid`; `offline_access` (or the IdP's equivalent) to get a refresh token |
| `wingman.oidc.audience` | `https://api.wingman.app` | `audience` authorization parameter. **Required for Auth0** (the API Identifier, equal to the backend's `WINGMAN_AUTH_AUDIENCE`); without it Auth0 issues an opaque access token |
| `wingman.oidc.connection` | `google-oauth2` | Optional. Sent as the `connection` authorization parameter so Auth0 goes straight to Google instead of its login picker. Unset: no `connection` parameter |

`OidcConfig.additionalAuthorizationParameters()` builds the extra parameters (`audience`, and
`connection` when set); `Auth0RequestTest` checks they are present when configured and absent when
not, and that the redirect scheme matches the manifest placeholder logic.

For Auth0 (I-014), follow [`auth0.md`](auth0.md):
1. Create the API (RS256, Allow Offline Access) and the Native app (callback and logout URL
   `com.wingman.auth:/oauth2redirect`, Authorization Code + Refresh Token grants), and enable the
   Google connection.
2. Run `scripts/configure-auth0.sh --beta` (the committed beta tenant) or
   `scripts/configure-auth0.sh <tenant-domain> <client-id> <api-identifier> google-oauth2` and
   use the Gradle command it prints (or put the values in `~/.gradle/gradle.properties`). The
   exact emulator steps for the beta tenant are in [`auth0.md`](auth0.md#sign-in-on-an-emulator-with-the-beta-tenant).
3. Run the device checklist below and record the results.

## Manifest

No manual change to `AndroidManifest.xml`. AppAuth's library manifest declares
`RedirectUriReceiverActivity` with `${appAuthRedirectScheme}`; `app/build.gradle.kts` sets that
placeholder from the redirect URI (an inert `com.wingman.auth` when unconfigured).

## Device checklist (not yet run: UNVERIFIED)

- Debug build, real API, dev token: sign-in, `GET /v1/me` succeeds, onboarding check runs.
- Kill and relaunch: still signed in (Keystore decrypts the stored session).
- Sign out: the sign-in screen appears, and relaunch stays signed out.
- Expired dev token: the next call returns the app to the sign-in screen.
- OIDC (Auth0 + Google, [`auth0.md`](auth0.md)): browser opens, redirect returns, code exchange, refresh near expiry,
  revoked refresh token signs out.
- Release APK: no developer sign-in panel.
