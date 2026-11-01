# Authentication and user isolation (WING-004)

Wingman stores no passwords. Clients sign in with an OIDC identity provider (IdP) and
call the API with `Authorization: Bearer <access token>`. The backend verifies the JWT,
maps its `(iss, sub)` pair to exactly one `users` row (created on first login), and
hands a `UserScope` to every repository. Every query on a user-owned table is then
filtered by that user, so another user's resource behaves exactly like a missing one:
`404 not_found`, never `403` (ADR 0002).

## Code map

| Module | Purpose |
|---|---|
| `app/security/tokens.py` | `TokenVerifier` protocol, `JwtVerifier`, `KeySource` protocol with `JwksKeySource` (cached) and `StaticKeySource`, `IssuerRoutingVerifier`, `RejectAllVerifier` |
| `app/security/settings.py` | `AuthSettings` (env prefix `WINGMAN_AUTH_`) |
| `app/security/dev_stub.py` | DEV-ONLY HS256 stub, `mint_dev_token`, CLI |
| `app/security/factory.py` | `build_verifier(settings, env)`, `get_token_verifier()` (cached dependency), `check_auth_config()` startup check |
| `app/security/deps.py` | `get_current_user_scope` (stable interface, D-007), `get_current_user`, `CurrentUser`, `CurrentScope` |
| `app/security/scoped.py` | `scoped_repository(RepoCls)`: dependency that builds a WING-003 repository bound to the caller's scope |
| `app/services/auth.py` | `resolve_user`: `(iss, sub)` to `User`, first-login create, rejects deleted accounts |
| `app/api/auth.py` | `GET /v1/me` (`create_auth_router()`) |
| `app/api/errors.py` | `register_error_handlers(app)`, `ProblemException`, `ErrorCode` |

## What a token must satisfy

- Header `alg` in the allowlist. `none` is never accepted. Only asymmetric algorithms
  (`RS*`, `PS*`, `ES*`, `EdDSA`) can be configured for OIDC, and the key's type must
  match the algorithm, so an `HS256` token can never be checked against an RSA public
  key ("alg confusion").
- Signature valid for the key named by `kid` in the IdP's JWKS (`use` must be `sig` or absent).
- `iss` equals `WINGMAN_AUTH_ISSUER`; `aud` equals or contains `WINGMAN_AUTH_AUDIENCE`.
- `exp` present and not passed; `nbf` and `iat`, if present, not in the future. All
  with `WINGMAN_AUTH_LEEWAY_S` seconds of clock-skew tolerance (default 30).
- `sub` non-empty, at most 512 characters.

Any failure, a missing or malformed `Authorization` header, or a deleted account gives
`401 unauthenticated` with `WWW-Authenticate: Bearer` and a generic message. The exact
reason is logged server-side only. If the JWKS cannot be fetched the API returns
`503 upstream_unavailable` with `Retry-After`.

`email` (if well-formed) and `name` are copied to the new user on first login only.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `WINGMAN_AUTH_ISSUER` | unset | Exact `iss` of the IdP |
| `WINGMAN_AUTH_AUDIENCE` | unset | API audience the IdP puts in `aud` |
| `WINGMAN_AUTH_JWKS_URL` | unset | IdP JWKS endpoint (`https` required in prod) |
| `WINGMAN_AUTH_ALGORITHMS` | `RS256,ES256` | Comma-separated allowlist (asymmetric only) |
| `WINGMAN_AUTH_LEEWAY_S` | `30` | Clock skew tolerance, 0 to 300 |
| `WINGMAN_AUTH_JWKS_CACHE_TTL_S` | `300` | JWKS cache lifetime |
| `WINGMAN_AUTH_JWKS_MIN_REFRESH_S` | `30` | Minimum gap between refetches caused by an unknown `kid` (key rotation) |
| `WINGMAN_AUTH_JWKS_TIMEOUT_S` | `5` | JWKS HTTP timeout |
| `WINGMAN_AUTH_DEV_STUB_ENABLED` | `false` | DEV-ONLY stub opt-in (see below) |
| `WINGMAN_AUTH_DEV_SECRET` | unset | HS256 secret for the stub, at least 32 characters |
| `WINGMAN_AUTH_DEV_ISSUER` | `https://dev-auth.wingman.invalid` | `iss` of stub tokens |
| `WINGMAN_AUTH_DEV_AUDIENCE` | `wingman-dev` | `aud` of stub tokens |

Set all three of issuer, audience and JWKS URL, or none; a partial set is a startup
error. With neither OIDC nor the stub configured, every protected call returns 401
(fail closed).

## Local development credentials (dev stub)

The stub is active only when **both** `WINGMAN_ENV` is `dev` or `test` **and**
`WINGMAN_AUTH_DEV_STUB_ENABLED=true`. With `WINGMAN_ENV=prod`, enabling it makes
`check_auth_config()` raise `AuthConfigError` (the app refuses to start) and
`mint_dev_token` refuses to sign. Stub tokens go through the same `JwtVerifier`
(signature, `iss`, `aud`, `exp`, `nbf`), and its issuer is separate from the real IdP's,
so `(dev issuer, "alice")` and `(real issuer, "alice")` are different users.

```bash
# backend/.env (git-ignored)
WINGMAN_ENV=dev
WINGMAN_AUTH_DEV_STUB_ENABLED=true
WINGMAN_AUTH_DEV_SECRET=<output of: python -c 'import secrets; print(secrets.token_urlsafe(48))'>

cd backend
TOKEN=$(uv run python -m app.security.dev_stub --sub alice --email alice@example.com --name Alice)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8000/v1/me
```

Use different `--sub` values to act as different users (e.g. to check isolation).
Tokens last 12 hours by default (`--ttl-hours`).

## Writing a protected endpoint

```python
from typing import Annotated
from uuid import UUID
from fastapi import APIRouter, Depends
from app.db.repositories import ConversationRepository
from app.security.deps import CurrentScope
from app.security.scoped import scoped_repository

Conversations = Annotated[ConversationRepository, Depends(scoped_repository(ConversationRepository))]

@router.get("/v1/conversations/{conversation_id}")
async def get_conversation(conversation_id: UUID, repo: Conversations) -> ConversationOut:
    return ConversationOut.from_row(await repo.require(conversation_id))  # NotFoundError -> 404
```

Never accept a `user_id` from the request; it always comes from the token. Raise
`app.api.errors.ProblemException(status, ErrorCode.X, detail)` for a specific contract
error code.

## Errors (problem+json)

`register_error_handlers(app)` makes every error an RFC 9457 `application/problem+json`
body matching the contract `Problem` schema, with `code`, `instance` (the path) and
`request_id` (echoes a safe `X-Request-ID` header, else generated; also returned as the
`X-Request-ID` response header).

| Source | Status | `code` |
|---|---|---|
| Request validation (replaces FastAPI's default 422 body; `errors[]` holds JSON Pointers or `query:<name>`/`header:<name>`, never the rejected values) | 422 | `validation_error` |
| `HTTPException(401)` | 401 | `unauthenticated` |
| `NotFoundError`, `HTTPException(404)`, unknown route, `HTTPException(403)` | 404 | `not_found` |
| `VersionConflictError` | 412 | `precondition_failed` |
| `InvalidCursorError` | 422 | `validation_error` |
| `HTTPException(429)` | 429 | `rate_limited` |
| `HTTPException(502/503/504)` | same | `upstream_unavailable` |
| `ProblemException` | its status | its code |
| Any other exception | 500 | `internal_error` (no detail; logged with traceback) |

Method-not-allowed (405) maps to `not_found` because the contract has no dedicated code.

## Android client

The app should obtain an access token from the IdP with the system browser (AppAuth,
authorization code + PKCE), keep it and the refresh token in encrypted storage, and
have `AppContainer.tokenProvider` return the current access token (refreshing it when
near expiry). On a `401` the client refreshes once and retries; if that fails, it signs
the user out. For local development against the dev stub, a debug-only build can
return a token minted with the CLI above. Never ship the dev secret in an APK.
