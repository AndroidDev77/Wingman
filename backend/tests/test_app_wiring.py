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


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/v1/preferences"),
        ("PUT", "/v1/preferences"),
        ("GET", "/v1/preferences/versions"),
        ("GET", "/v1/contacts/00000000-0000-0000-0000-000000000001/preferences"),
        ("PUT", "/v1/contacts/00000000-0000-0000-0000-000000000001/preferences"),
        ("DELETE", "/v1/contacts/00000000-0000-0000-0000-000000000001/preferences"),
    ],
)
def test_preferences_routes_are_mounted_and_require_a_token(
    monkeypatch: pytest.MonkeyPatch, method: str, path: str
) -> None:
    with _client(monkeypatch, "prod") as client:
        resp = client.request(method, path)
        assert resp.status_code == 401, resp.text
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.json()["code"] == "unauthenticated"


@pytest.mark.parametrize("env", ["dev", "prod"])
def test_drafts_route_is_mounted_and_requires_a_token(
    monkeypatch: pytest.MonkeyPatch, env: str
) -> None:
    path = "/v1/conversations/00000000-0000-0000-0000-000000000001/drafts"
    with _client(monkeypatch, env) as client:
        assert (
            "post"
            in client.get("/openapi.json").json()["paths"][
                path.replace("00000000-0000-0000-0000-000000000001", "{conversation_id}")
            ]
        )
        resp = client.post(path, json={}, headers={"Idempotency-Key": "wiring-key-0001"})
        assert resp.status_code == 401, resp.text
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.json()["code"] == "unauthenticated"


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/v1/contacts/00000000-0000-0000-0000-000000000001/memories"),
        ("PUT", "/v1/memories/00000000-0000-0000-0000-000000000001"),
        ("DELETE", "/v1/memories/00000000-0000-0000-0000-000000000001"),
    ],
)
@pytest.mark.parametrize("env", ["dev", "prod"])
def test_memory_routes_are_mounted_and_require_a_token(
    monkeypatch: pytest.MonkeyPatch, env: str, method: str, path: str
) -> None:
    # WING-008 router, mounted once contract v1.1.0 added the memory routes (D-012).
    with _client(monkeypatch, env) as client:
        kwargs = {"json": {"content": "x"}} if method == "PUT" else {}
        resp = client.request(method, path, **kwargs)
        assert resp.status_code == 401, resp.text
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.headers["WWW-Authenticate"] == "Bearer"
        assert resp.json()["code"] == "unauthenticated"
        paths = client.get("/openapi.json").json()["paths"]
        assert "/v1/contacts/{contact_id}/memories" in paths
        assert "/v1/memories/{memory_id}" in paths


def test_memory_service_is_built_once_per_app(monkeypatch: pytest.MonkeyPatch) -> None:
    from app import main

    built: list[object] = []
    real = main.build_gateway

    def counting_build_gateway() -> object:
        gw = real()
        built.append(gw)
        return gw

    monkeypatch.setattr(main, "build_gateway", counting_build_gateway)
    monkeypatch.setenv("WINGMAN_ENV", "dev")
    get_settings.cache_clear()
    app = create_app()

    class _Req:
        def __init__(self) -> None:
            self.app = app

    import asyncio

    first = asyncio.run(main.get_memory_service(_Req()))  # type: ignore[arg-type]
    second = asyncio.run(main.get_memory_service(_Req()))  # type: ignore[arg-type]
    assert first is second
    assert len(built) == 1
    assert first.gateway is built[0]


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


_WING011_ROUTES = [
    ("GET", "/v1/approvals"),
    ("GET", "/v1/approvals/00000000-0000-0000-0000-000000000001"),
    ("POST", "/v1/approvals/00000000-0000-0000-0000-000000000001/approve"),
    ("POST", "/v1/approvals/00000000-0000-0000-0000-000000000001/reject"),
    ("GET", "/v1/automation"),
    ("POST", "/v1/automation/pause"),
    ("POST", "/v1/automation/resume"),
    ("GET", "/v1/contacts/00000000-0000-0000-0000-000000000001/automation"),
    ("POST", "/v1/contacts/00000000-0000-0000-0000-000000000001/automation/pause"),
    ("POST", "/v1/contacts/00000000-0000-0000-0000-000000000001/automation/resume"),
    ("POST", "/v1/contacts/00000000-0000-0000-0000-000000000001/automation/takeover"),
    ("GET", "/v1/agent-actions"),
    ("POST", "/v1/conversations/00000000-0000-0000-0000-000000000001/messages"),
    ("POST", "/v1/connections/00000000-0000-0000-0000-000000000001/events"),
    ("GET", "/v1/connections/00000000-0000-0000-0000-000000000001/outbox"),
    ("POST", "/v1/connections/00000000-0000-0000-0000-000000000001/outbox/claim"),
]


@pytest.mark.parametrize(("method", "path"), _WING011_ROUTES)
@pytest.mark.parametrize("env", ["dev", "prod"])
def test_wing011_routes_are_mounted_and_require_a_token(
    monkeypatch: pytest.MonkeyPatch, env: str, method: str, path: str
) -> None:
    with _client(monkeypatch, env) as client:
        resp = client.request(method, path, json={}, headers={"Idempotency-Key": "wiring-key-0001"})
        assert resp.status_code == 401, resp.text
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.json()["code"] == "unauthenticated"


def test_wing011_operation_ids_match_the_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    import yaml

    from tests.automation.conftest import OPENAPI

    contract = yaml.safe_load(OPENAPI.read_text())["paths"]
    with _client(monkeypatch, "prod") as client:
        served = client.get("/openapi.json").json()["paths"]
    ops = {
        "listApprovals",
        "getApproval",
        "approveApproval",
        "rejectApproval",
        "getGlobalAutomationState",
        "pauseAllAutomation",
        "resumeAllAutomation",
        "getContactAutomationState",
        "pauseContactAutomation",
        "resumeContactAutomation",
        "takeoverContact",
        "listAgentActions",
        "sendMessage",
        "ingestConnectorEvent",
        "listOutboxInstructions",
        "claimOutboxInstructions",
    }
    for path, item in contract.items():
        for method, op in item.items():
            if isinstance(op, dict) and op.get("operationId") in ops:
                assert served[path][method]["operationId"] == op["operationId"], (path, method)
