"""Dev stub is reachable only in dev/test with explicit opt-in; prod refuses it."""

from typing import Any

import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr

from app.config import get_settings
from app.security import factory
from app.security.dev_stub import (
    AuthConfigError,
    build_dev_verifier,
    dev_stub_active,
    main,
    mint_dev_token,
)
from app.security.factory import build_verifier, check_auth_config, get_token_verifier
from app.security.settings import AuthSettings
from app.security.tokens import (
    InvalidTokenError,
    IssuerRoutingVerifier,
    JwtVerifier,
    RejectAllVerifier,
)
from tests.auth.conftest import build_app

SECRET = "s" * 40


def stub_settings(**kw: Any) -> AuthSettings:
    base: dict[str, Any] = {"dev_stub_enabled": True, "dev_secret": SecretStr(SECRET)}
    return AuthSettings(**(base | kw))


@pytest.mark.parametrize("env", ["dev", "test"])
async def test_stub_works_in_dev_and_test(env: str) -> None:
    auth = stub_settings()
    verifier = build_verifier(auth, env)
    token = mint_dev_token(auth, env, subject="alice", email="alice@example.com")
    ident = await verifier.verify(token)
    assert (ident.issuer, ident.subject) == (auth.dev_issuer, "alice")


def test_stub_off_by_default_even_in_dev() -> None:
    auth = AuthSettings(dev_secret=SecretStr(SECRET))
    assert dev_stub_active(auth, "dev") is False
    assert isinstance(build_verifier(auth, "dev"), RejectAllVerifier)
    with pytest.raises(AuthConfigError):
        mint_dev_token(auth, "dev", subject="alice")


def test_prod_refuses_to_build_with_stub_enabled() -> None:
    auth = stub_settings()
    with pytest.raises(AuthConfigError, match="forbidden"):
        build_verifier(auth, "prod")
    with pytest.raises(AuthConfigError):
        build_dev_verifier(auth, "prod")
    with pytest.raises(AuthConfigError):
        mint_dev_token(auth, "prod", subject="alice")


def test_prod_with_stub_enabled_and_oidc_configured_still_refuses() -> None:
    auth = stub_settings(
        issuer="https://idp.example/", audience="api", jwks_url="https://idp.example/jwks"
    )
    with pytest.raises(AuthConfigError):
        build_verifier(auth, "prod")


async def test_prod_never_accepts_a_dev_token() -> None:
    """A token minted in dev is useless against a prod config (stub off)."""
    dev = stub_settings()
    token = mint_dev_token(dev, "dev", subject="alice")
    prod = AuthSettings(
        issuer="https://idp.example/", audience="api", jwks_url="https://idp.example/jwks"
    )
    verifier = build_verifier(prod, "prod")
    assert isinstance(verifier, IssuerRoutingVerifier)
    assert verifier.issuers == {"https://idp.example/"}
    with pytest.raises(InvalidTokenError):
        await verifier.verify(token)


def test_prod_rejects_hs_algorithms_in_allowlist() -> None:
    auth = AuthSettings(
        issuer="https://idp.example/",
        audience="api",
        jwks_url="https://idp.example/jwks",
        algorithms="RS256,HS256",
    )
    with pytest.raises(AuthConfigError, match="disallowed"):
        build_verifier(auth, "prod")


@pytest.mark.parametrize("algs", ["none", "RS256,none", "", " , "])
def test_bad_allowlist_rejected(algs: str) -> None:
    with pytest.raises(AuthConfigError):
        build_verifier(AuthSettings(algorithms=algs), "dev")


def test_prod_requires_https_jwks() -> None:
    auth = AuthSettings(issuer="https://idp/", audience="api", jwks_url="http://idp/jwks")
    with pytest.raises(AuthConfigError, match="https"):
        build_verifier(auth, "prod")


def test_partial_oidc_config_rejected() -> None:
    with pytest.raises(AuthConfigError, match="partial"):
        build_verifier(AuthSettings(issuer="https://idp/"), "dev")


@pytest.mark.parametrize("secret", [None, "short"])
def test_stub_needs_a_strong_secret(secret: str | None) -> None:
    auth = AuthSettings(
        dev_stub_enabled=True, dev_secret=SecretStr(secret) if secret is not None else None
    )
    with pytest.raises(AuthConfigError, match="at least"):
        build_verifier(auth, "dev")


def test_stub_issuer_must_differ_from_oidc_issuer() -> None:
    auth = stub_settings(
        issuer="https://same/",
        audience="api",
        jwks_url="https://same/jwks",
        dev_issuer="https://same/",
    )
    with pytest.raises(AuthConfigError, match="differ"):
        build_verifier(auth, "dev")


async def test_stub_tokens_still_fully_verified() -> None:
    auth = stub_settings()
    v = build_dev_verifier(auth, "dev")
    good = mint_dev_token(auth, "dev", subject="alice")
    assert (await v.verify(good)).subject == "alice"
    other = stub_settings(dev_secret=SecretStr("o" * 40))
    with pytest.raises(InvalidTokenError):
        await v.verify(mint_dev_token(other, "dev", subject="alice"))
    from datetime import timedelta

    with pytest.raises(InvalidTokenError):
        await v.verify(mint_dev_token(auth, "dev", subject="a", ttl=timedelta(minutes=-5)))


# --------------------------------------------------------------------------- from env


def test_settings_from_env_prod_with_stub_refuses(
    clean_auth_env: pytest.MonkeyPatch,
) -> None:
    clean_auth_env.setenv("WINGMAN_ENV", "prod")
    clean_auth_env.setenv("WINGMAN_AUTH_DEV_STUB_ENABLED", "true")
    clean_auth_env.setenv("WINGMAN_AUTH_DEV_SECRET", SECRET)
    get_settings.cache_clear()
    get_token_verifier.cache_clear()
    try:
        with pytest.raises(AuthConfigError):
            check_auth_config()
        # Even without the startup check, every protected request fails closed (500,
        # no identity leaked), never authenticated.
        app = build_app(RejectAllVerifier())
        app.dependency_overrides.pop(get_token_verifier)
        with TestClient(app, raise_server_exceptions=False) as client:
            resp = client.get("/v1/me", headers={"Authorization": "Bearer x.y.z"})
        assert resp.status_code == 500
        assert resp.json()["code"] == "internal_error"
        assert "STUB" not in resp.text and SECRET not in resp.text
    finally:
        get_settings.cache_clear()
        get_token_verifier.cache_clear()


def test_settings_from_env_dev_with_stub(clean_auth_env: pytest.MonkeyPatch) -> None:
    clean_auth_env.setenv("WINGMAN_ENV", "dev")
    clean_auth_env.setenv("WINGMAN_AUTH_DEV_STUB_ENABLED", "true")
    clean_auth_env.setenv("WINGMAN_AUTH_DEV_SECRET", SECRET)
    get_settings.cache_clear()
    get_token_verifier.cache_clear()
    try:
        check_auth_config()
        assert isinstance(factory.get_token_verifier(), IssuerRoutingVerifier)
    finally:
        get_settings.cache_clear()
        get_token_verifier.cache_clear()


def test_defaults_fail_closed(clean_auth_env: pytest.MonkeyPatch) -> None:
    get_settings.cache_clear()
    get_token_verifier.cache_clear()
    try:
        assert isinstance(get_token_verifier(), RejectAllVerifier)
    finally:
        get_token_verifier.cache_clear()
        get_settings.cache_clear()


def test_mint_cli(clean_auth_env: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]) -> None:
    clean_auth_env.setenv("WINGMAN_ENV", "prod")
    clean_auth_env.setenv("WINGMAN_AUTH_DEV_STUB_ENABLED", "true")
    clean_auth_env.setenv("WINGMAN_AUTH_DEV_SECRET", SECRET)
    get_settings.cache_clear()
    try:
        assert main(["--sub", "alice"]) == 2
        assert "forbidden" in capsys.readouterr().err
        clean_auth_env.setenv("WINGMAN_ENV", "dev")
        get_settings.cache_clear()
        assert main(["--sub", "alice"]) == 0
        token = capsys.readouterr().out.strip()
        assert token.count(".") == 2
    finally:
        get_settings.cache_clear()


def test_jwt_verifier_type_for_stub() -> None:
    assert isinstance(build_dev_verifier(stub_settings(), "test"), JwtVerifier)
