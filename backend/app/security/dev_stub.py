"""DEV-ONLY auth stub: HS256 tokens signed with a local secret.

Lets developers and tests call protected endpoints without an identity provider.
It is active only when BOTH hold:

* ``WINGMAN_ENV`` is ``dev`` or ``test``, and
* ``WINGMAN_AUTH_DEV_STUB_ENABLED=true`` (with ``WINGMAN_AUTH_DEV_SECRET`` of at least
  32 characters).

With ``WINGMAN_ENV=prod`` it cannot be enabled: :func:`app.security.factory.build_verifier`
raises :class:`AuthConfigError` (the app refuses to start / every request fails
closed), and :func:`mint_dev_token` refuses to sign.

Mint a token locally::

    cd backend
    WINGMAN_ENV=dev WINGMAN_AUTH_DEV_STUB_ENABLED=true \\
    WINGMAN_AUTH_DEV_SECRET=$(python -c 'import secrets; print(secrets.token_urlsafe(48))') \\
      uv run python -m app.security.dev_stub --sub alice --email alice@example.com
"""

from __future__ import annotations

import argparse
import sys
from datetime import UTC, datetime, timedelta
from typing import Any

import jwt

from app.security.settings import DEV_STUB_ALGORITHM, MIN_DEV_SECRET_LENGTH, AuthSettings
from app.security.tokens import JwtVerifier, StaticKeySource

DEV_STUB_ENVS = frozenset({"dev", "test"})
DEV_KEY_ID = "wingman-dev-stub"


class AuthConfigError(RuntimeError):
    """Auth settings are unsafe or incomplete. Raised at startup; never ignore it."""


def dev_stub_active(auth: AuthSettings, env: str) -> bool:
    """Whether the dev stub is on. Raises if it is requested where it is forbidden."""
    if not auth.dev_stub_enabled:
        return False
    if env not in DEV_STUB_ENVS:
        raise AuthConfigError(
            f"WINGMAN_AUTH_DEV_STUB_ENABLED=true is forbidden when WINGMAN_ENV={env!r}; "
            "the dev auth stub only runs in dev or test"
        )
    secret = auth.dev_secret.get_secret_value() if auth.dev_secret else ""
    if len(secret) < MIN_DEV_SECRET_LENGTH:
        raise AuthConfigError(
            f"WINGMAN_AUTH_DEV_SECRET must be at least {MIN_DEV_SECRET_LENGTH} characters "
            "when the dev auth stub is enabled"
        )
    if auth.issuer is not None and auth.dev_issuer == auth.issuer:
        raise AuthConfigError("WINGMAN_AUTH_DEV_ISSUER must differ from WINGMAN_AUTH_ISSUER")
    return True


def _secret(auth: AuthSettings) -> str:
    assert auth.dev_secret is not None  # noqa: S101 - guaranteed by dev_stub_active
    return auth.dev_secret.get_secret_value()


def build_dev_verifier(auth: AuthSettings, env: str) -> JwtVerifier:
    if not dev_stub_active(auth, env):
        raise AuthConfigError("dev auth stub is not enabled")
    jwk = {
        "kty": "oct",
        "kid": DEV_KEY_ID,
        "alg": DEV_STUB_ALGORITHM,
        "use": "sig",
        "k": jwt.utils.base64url_encode(_secret(auth).encode()).decode(),
    }
    return JwtVerifier(
        issuer=auth.dev_issuer,
        audience=auth.dev_audience,
        algorithms=[DEV_STUB_ALGORITHM],
        key_source=StaticKeySource({DEV_KEY_ID: jwk}),
        leeway_s=auth.leeway_s,
    )


def mint_dev_token(
    auth: AuthSettings,
    env: str,
    *,
    subject: str,
    email: str | None = None,
    name: str | None = None,
    ttl: timedelta = timedelta(hours=12),
    now: datetime | None = None,
    extra_claims: dict[str, Any] | None = None,
) -> str:
    """Sign a dev token. Refuses unless the dev stub is active for ``env``."""
    if not dev_stub_active(auth, env):
        raise AuthConfigError("dev auth stub is not enabled; refusing to mint a token")
    issued = now or datetime.now(UTC)
    claims: dict[str, Any] = {
        "iss": auth.dev_issuer,
        "aud": auth.dev_audience,
        "sub": subject,
        "iat": issued,
        "nbf": issued,
        "exp": issued + ttl,
    }
    if email is not None:
        claims["email"] = email
    if name is not None:
        claims["name"] = name
    claims |= extra_claims or {}
    return jwt.encode(
        claims, _secret(auth), algorithm=DEV_STUB_ALGORITHM, headers={"kid": DEV_KEY_ID}
    )


def main(argv: list[str] | None = None) -> int:
    from app.config import get_settings

    parser = argparse.ArgumentParser(description="Mint a DEV-ONLY Wingman bearer token.")
    parser.add_argument("--sub", required=True, help="subject (stable user id at the dev IdP)")
    parser.add_argument("--email")
    parser.add_argument("--name")
    parser.add_argument("--ttl-hours", type=float, default=12.0)
    args = parser.parse_args(argv)
    try:
        token = mint_dev_token(
            AuthSettings(),
            get_settings().env,
            subject=args.sub,
            email=args.email,
            name=args.name,
            ttl=timedelta(hours=args.ttl_hours),
        )
    except AuthConfigError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(token)
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
