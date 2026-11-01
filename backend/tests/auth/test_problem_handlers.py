"""app.api.errors: every error is a contract Problem (application/problem+json)."""

import uuid
from typing import Annotated

import pytest
from fastapi import FastAPI, HTTPException, Query
from fastapi.testclient import TestClient
from pydantic import BaseModel, ConfigDict

from app.api.errors import (
    ErrorCode,
    ProblemException,
    error_pointer,
    register_error_handlers,
)
from app.db.errors import InvalidCursorError, NotFoundError, VersionConflictError
from tests.auth.conftest import assert_problem, openapi


class Body(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str
    count: int


def _app() -> FastAPI:
    app = FastAPI()
    register_error_handlers(app)

    @app.post("/body")
    async def body(b: Body, limit: Annotated[int, Query(ge=1, le=10)] = 5) -> dict[str, str]:
        return {"ok": b.name}

    @app.get("/raise/{kind}")
    async def raise_(kind: str) -> None:
        match kind:
            case "not_found":
                raise NotFoundError("conversations 123 not found (user OWNER-zq)")
            case "version":
                raise VersionConflictError("stale")
            case "cursor":
                raise InvalidCursorError("bad cursor")
            case "forbidden":
                raise HTTPException(status_code=403, detail="owned by user OWNER-zq")
            case "teapot":
                raise HTTPException(status_code=418)
            case "http500":
                raise HTTPException(status_code=500, detail="db password is hunter2")
            case "unavailable":
                raise HTTPException(status_code=503, detail="LLM down")
            case "problem":
                raise ProblemException(
                    409, ErrorCode.APPROVAL_NOT_PENDING, "already decided", headers={"X-A": "1"}
                )
            case "boom":
                raise RuntimeError("secret internals: postgres://u:p@h/db")

    return app


@pytest.fixture(scope="module")
def client() -> TestClient:
    return TestClient(_app(), raise_server_exceptions=False)


def test_error_code_enum_matches_contract() -> None:
    contract = openapi()["components"]["schemas"]["ErrorCode"]["enum"]
    assert [c.value for c in ErrorCode] == contract


def test_validation_error_replaces_fastapi_default(client: TestClient) -> None:
    resp = client.post("/body?limit=99", json={"name": "x", "count": "nope", "extra": "PRIVATE"})
    body = assert_problem(resp, 422, "validation_error")
    assert "detail" in body and not isinstance(body["detail"], list)
    pointers = {e["pointer"] for e in body["errors"]}
    assert {"/count", "/extra", "query:limit"} <= pointers
    assert "PRIVATE" not in resp.text  # rejected input is never echoed


def test_malformed_json_body(client: TestClient) -> None:
    resp = client.post("/body", content=b"{not json", headers={"Content-Type": "application/json"})
    assert_problem(resp, 422, "validation_error")


@pytest.mark.parametrize(
    ("kind", "status", "code"),
    [
        ("not_found", 404, "not_found"),
        ("version", 412, "precondition_failed"),
        ("cursor", 422, "validation_error"),
        ("forbidden", 404, "not_found"),
        ("teapot", 418, "validation_error"),
        ("http500", 500, "internal_error"),
        ("unavailable", 503, "upstream_unavailable"),
        ("problem", 409, "approval_not_pending"),
        ("boom", 500, "internal_error"),
    ],
)
def test_mapped_errors(client: TestClient, kind: str, status: int, code: str) -> None:
    resp = client.get(f"/raise/{kind}")
    body = assert_problem(resp, status, code)
    assert body["instance"] == f"/raise/{kind}"
    assert resp.headers["X-Request-ID"] == body["request_id"]


def test_no_internals_leak(client: TestClient) -> None:
    for kind in ("not_found", "forbidden", "http500", "boom"):
        text = client.get(f"/raise/{kind}").text
        for secret in ("OWNER-zq", "hunter2", "postgres://", "RuntimeError", "Traceback"):
            assert secret not in text, (kind, secret)


def test_problem_exception_headers_and_detail(client: TestClient) -> None:
    resp = client.get("/raise/problem")
    assert resp.headers["X-A"] == "1"
    assert resp.json()["detail"] == "already decided"


def test_unknown_route_and_method(client: TestClient) -> None:
    assert_problem(client.get(f"/nope/{uuid.uuid4()}"), 404, "not_found")
    assert_problem(client.delete("/body"), 405, "not_found")


def test_incoming_request_id_is_echoed_when_safe(client: TestClient) -> None:
    resp = client.get("/raise/not_found", headers={"X-Request-ID": "req-123"})
    assert resp.json()["request_id"] == "req-123"
    resp = client.get("/raise/not_found", headers={"X-Request-ID": "bad id <script>"})
    assert resp.json()["request_id"] != "bad id <script>"


def test_problem_exception_rejects_non_error_status() -> None:
    with pytest.raises(ValueError):
        ProblemException(200, ErrorCode.NOT_FOUND)


def test_error_pointer() -> None:
    assert error_pointer(("body", "a", 0, "b/c")) == "/a/0/b~1c"
    assert error_pointer(("query", "limit")) == "query:limit"
    assert error_pointer(("header", "Idempotency-Key")) == "header:Idempotency-Key"
    assert error_pointer(("body",)) == ""
    assert error_pointer(()) == ""
