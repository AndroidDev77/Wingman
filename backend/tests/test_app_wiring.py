"""Integrator wiring: which routers are reachable per environment.

Checked over HTTP rather than by inspecting `app.routes`, because FastAPI
represents included routers differently across versions.
"""

from collections.abc import Iterator
from typing import Any

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


_CONTRACT_V12_ROUTES = [
    ("GET", "/v1/connections"),
    ("POST", "/v1/connections"),
    ("GET", "/v1/connections/00000000-0000-0000-0000-000000000001"),
    ("POST", "/v1/connections/00000000-0000-0000-0000-000000000001/unpair"),
]


@pytest.mark.parametrize(("method", "path"), _CONTRACT_V12_ROUTES)
@pytest.mark.parametrize("env", ["dev", "prod"])
def test_connection_routes_are_mounted_and_require_a_token(
    monkeypatch: pytest.MonkeyPatch, env: str, method: str, path: str
) -> None:
    # Contract 1.2.0 (I-017, ADR 0007): mounted in every environment.
    with _client(monkeypatch, env) as client:
        resp = client.request(method, path, json={}, headers={"Idempotency-Key": "wiring-key-0001"})
        assert resp.status_code == 401, resp.text
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.json()["code"] == "unauthenticated"


def test_connection_operation_ids_match_the_contract(monkeypatch: pytest.MonkeyPatch) -> None:
    import yaml

    from tests.automation.conftest import OPENAPI

    contract = yaml.safe_load(OPENAPI.read_text())["paths"]
    with _client(monkeypatch, "prod") as client:
        served = client.get("/openapi.json").json()["paths"]
    ops = {"listConnections", "createConnection", "getConnection", "unpairConnection"}
    found = set()
    for path, item in contract.items():
        for method, op in item.items():
            if isinstance(op, dict) and op.get("operationId") in ops:
                assert served[path][method]["operationId"] == op["operationId"], (path, method)
                found.add(op["operationId"])
    assert found == ops


_I021_ROUTES = [
    "/v1/conversations",
    "/v1/conversations/00000000-0000-0000-0000-000000000001",
    "/v1/conversations/00000000-0000-0000-0000-000000000001/messages",
]


@pytest.mark.parametrize("path", _I021_ROUTES)
@pytest.mark.parametrize("env", ["dev", "prod"])
def test_conversation_read_routes_are_mounted_and_require_a_token(
    monkeypatch: pytest.MonkeyPatch, env: str, path: str
) -> None:
    # I-021: listConversations, getConversation, listMessages (GET; 405 before).
    with _client(monkeypatch, env) as client:
        resp = client.get(path)
        assert resp.status_code == 401, resp.text
        assert resp.headers["content-type"].startswith("application/problem+json")
        assert resp.json()["code"] == "unauthenticated"


# Routes served outside the contract on purpose: the WING-006 dev-only simulator (never
# mounted in prod, see test_demo_connector_absent_in_prod). Nothing else may be extra.
DEV_ONLY_PATH_PREFIXES = ("/dev/connectors/mock",)


def _operations(paths: dict[str, Any]) -> dict[tuple[str, str], str]:
    methods = {"get", "put", "post", "delete", "patch"}
    return {
        (path, method): op.get("operationId")
        for path, item in paths.items()
        for method, op in item.items()
        if method in methods and isinstance(op, dict)
    }


@pytest.mark.parametrize("env", ["dev", "prod"])
def test_every_contract_operation_is_mounted_with_its_operation_id(
    monkeypatch: pytest.MonkeyPatch, env: str
) -> None:
    """The set of served (path, method, operationId) equals the contract's, apart from
    the explicitly listed dev-only routes."""
    import yaml

    from tests.automation.conftest import OPENAPI

    contract = _operations(yaml.safe_load(OPENAPI.read_text())["paths"])
    with _client(monkeypatch, env) as client:
        served_all = _operations(client.get("/openapi.json").json()["paths"])
    served = {k: v for k, v in served_all.items() if not k[0].startswith(DEV_ONLY_PATH_PREFIXES)}
    assert len(contract) == 35
    assert served == contract
    assert set(served.values()) == set(contract.values())
    dev_only = set(served_all) - set(served)
    assert (dev_only != set()) is (env != "prod")


def test_request_sessions_commit_before_the_response_is_sent() -> None:
    """WING-025 regression. With FastAPI's default ``request`` scope, a ``yield``
    dependency runs its exit code (for ``get_session``: ``session.commit()``) only after
    the response has been sent, so a client could read its own write before it was
    committed (the E2E X2 scenario saw a just-claimed message still ``queued``). Every
    ``Depends(get_session...)`` in the app must therefore be ``scope="function"``.

    Checked in the source because FastAPI's route internals differ across versions
    (see the module docstring); the uvicorn behavior itself is shown by
    ``tests/e2e`` (X2 reads its claim back immediately)."""
    import re
    from pathlib import Path

    app_dir = Path(__file__).resolve().parents[1] / "app"
    uses = []
    for path in sorted(app_dir.rglob("*.py")):
        for n, line in enumerate(path.read_text().splitlines(), 1):
            if re.search(r"Depends\(\s*get_session\b", line):
                uses.append((path.relative_to(app_dir).as_posix(), n, line.strip()))
    assert len(uses) >= 10, uses
    wrong = [u for u in uses if 'scope="function"' not in u[2]]
    assert not wrong, wrong
