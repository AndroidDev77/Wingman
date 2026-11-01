"""Integrator wiring: which routers are reachable per environment.

Checked over HTTP rather than by inspecting `app.routes`, because FastAPI
represents included routers differently across versions.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import create_app
from app.security.dev_stub import AuthConfigError
from app.security.factory import get_token_verifier

DEMO_CAPS = "/dev/connectors/mock/capabilities"

_AUTH_ENV = (
    "WINGMAN_AUTH_ISSUER",
    "WINGMAN_AUTH_AUDIENCE",
    "WINGMAN_AUTH_JWKS_URL",
    "WINGMAN_AUTH_DEV_STUB_ENABLED",
    "WINGMAN_AUTH_DEV_SECRET",
)


@pytest.fixture(autouse=True)
def _fresh_settings(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    for name in _AUTH_ENV:
        monkeypatch.delenv(name, raising=False)
    get_settings.cache_clear()
    get_token_verifier.cache_clear()
    yield
    get_settings.cache_clear()
    get_token_verifier.cache_clear()


def _client(monkeypatch: pytest.MonkeyPatch, env: str) -> TestClient:
    monkeypatch.setenv("WINGMAN_ENV", env)
    get_settings.cache_clear()
    get_token_verifier.cache_clear()
    return TestClient(create_app())


@pytest.mark.parametrize("env", ["dev", "test"])
def test_demo_connector_reachable_outside_prod(monkeypatch: pytest.MonkeyPatch, env: str) -> None:
    with _client(monkeypatch, env) as client:
        assert client.get(DEMO_CAPS).status_code == 200


def test_demo_connector_absent_in_prod(monkeypatch: pytest.MonkeyPatch) -> None:
    with _client(monkeypatch, "prod") as client:
        assert client.get(DEMO_CAPS).status_code == 404
        assert client.get("/health").status_code == 200
        assert not any(p.startswith("/dev/") for p in client.get("/openapi.json").json()["paths"])


def test_app_starts_and_shuts_down_without_a_database(monkeypatch: pytest.MonkeyPatch) -> None:
    # Lifespan shutdown must not require Postgres when no DB call was made.
    with _client(monkeypatch, "dev") as client:
        assert client.get("/health").json()["status"] == "ok"


@pytest.mark.parametrize("env", ["dev", "prod"])
def test_me_is_mounted_and_requires_a_token(monkeypatch: pytest.MonkeyPatch, env: str) -> None:
    with _client(monkeypatch, env) as client:
        resp = client.get("/v1/me")
        assert resp.status_code == 401
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.headers["WWW-Authenticate"] == "Bearer"
        assert resp.json()["code"] == "unauthenticated"
        # A token is rejected too when no identity provider is configured (fail closed).
        resp = client.get("/v1/me", headers={"Authorization": "Bearer a.b.c"})
        assert resp.status_code == 401


def test_unknown_route_is_problem_json(monkeypatch: pytest.MonkeyPatch) -> None:
    with _client(monkeypatch, "prod") as client:
        resp = client.get("/v1/does-not-exist")
        assert resp.status_code == 404
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.json()["code"] == "not_found"


def test_prod_refuses_to_start_with_dev_stub(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WINGMAN_AUTH_DEV_STUB_ENABLED", "true")
    monkeypatch.setenv("WINGMAN_AUTH_DEV_SECRET", "x" * 48)
    monkeypatch.setenv("WINGMAN_ENV", "prod")
    get_settings.cache_clear()
    get_token_verifier.cache_clear()
    with pytest.raises(AuthConfigError):
        create_app()


def test_dev_stub_allowed_in_dev(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WINGMAN_AUTH_DEV_STUB_ENABLED", "true")
    monkeypatch.setenv("WINGMAN_AUTH_DEV_SECRET", "x" * 48)
    with _client(monkeypatch, "dev") as client:
        assert client.get("/v1/me").status_code == 401  # still needs a token
