"""Integrator wiring: which routers are reachable per environment.

Checked over HTTP rather than by inspecting `app.routes`, because FastAPI
represents included routers differently across versions.
"""

from collections.abc import Iterator

import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import create_app

DEMO_CAPS = "/dev/connectors/mock/capabilities"


@pytest.fixture(autouse=True)
def _fresh_settings() -> Iterator[None]:
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _client(monkeypatch: pytest.MonkeyPatch, env: str) -> TestClient:
    monkeypatch.setenv("WINGMAN_ENV", env)
    get_settings.cache_clear()
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
