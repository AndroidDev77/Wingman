"""The memory router against contract v1.1.0 (``listContactMemories``, ``correctMemory``,
``deleteMemory``). Every response body is validated against ``contracts/openapi.yaml``."""

import uuid
from collections.abc import AsyncIterator
from datetime import timedelta
from functools import cache
from pathlib import Path
from typing import Any

import httpx
import pytest
import yaml
from fastapi import FastAPI
from jsonschema import Draft202012Validator
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import MemoryService
from app.api.errors import register_error_handlers
from app.api.memories import CONTRACT_STATUS, create_memories_router
from app.db.models import ConversationMemory
from app.db.pagination import CursorPosition, encode_cursor
from app.db.repositories import MemoryRepository
from app.db.scope import UserScope
from app.db.session import get_session
from app.main import create_app
from app.security.deps import get_current_user_scope
from tests.ai.memory.conftest import (
    NOW,
    Harness,
    Thread,
    fact,
    facts,
    make_thread,
    make_user,
    say,
)

OPENAPI = Path(__file__).resolve().parents[4] / "contracts" / "openapi.yaml"
LIST = "/v1/contacts/{}/memories"


# --------------------------------------------------------------------------- contract


@cache
def _openapi() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load(OPENAPI.read_text())
    return doc


@cache
def _validator(schema_name: str) -> Draft202012Validator:
    root = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "components": _openapi()["components"],
        "$ref": f"#/components/schemas/{schema_name}",
    }
    return Draft202012Validator(root, format_checker=Draft202012Validator.FORMAT_CHECKER)


def assert_contract(schema_name: str, body: Any) -> None:
    _validator(schema_name).validate(body)


def assert_page(resp: httpx.Response) -> dict[str, Any]:
    assert resp.status_code == 200, resp.text
    assert resp.headers["content-type"].startswith("application/json")
    body: dict[str, Any] = resp.json()
    assert_contract("MemoryPage", body)
    return body


def assert_problem(resp: httpx.Response, status: int, code: str) -> dict[str, Any]:
    assert resp.status_code == status, resp.text
    assert resp.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = resp.json()
    assert_contract("Problem", body)
    assert body["code"] == code and body["status"] == status
    assert body["type"] == f"https://wingman.invalid/problems/{code}"
    return body


def test_contract_operations_and_router_agree() -> None:
    """The router's routes and operationIds are exactly the contract's memory operations."""
    ops = {
        (method.upper(), path): op["operationId"]
        for path, item in _openapi()["paths"].items()
        for method, op in item.items()
        if isinstance(op, dict) and "memories" in op.get("tags", [])
    }
    router = create_memories_router(lambda: None)
    mine = {
        (m, r.path): r.operation_id  # type: ignore[attr-defined]
        for r in router.routes
        for m in r.methods  # type: ignore[attr-defined]
    }
    assert mine == ops
    assert CONTRACT_STATUS == "v1.1.0"


def test_router_is_mounted_in_the_real_app() -> None:
    paths = create_app().openapi()["paths"]
    assert {"get"} <= set(paths["/v1/contacts/{contact_id}/memories"])
    assert {"put", "delete"} <= set(paths["/v1/memories/{memory_id}"])


# --------------------------------------------------------------------------- app


def _app(session: AsyncSession, scope: UserScope, service: MemoryService) -> FastAPI:
    app = FastAPI()
    register_error_handlers(app)

    def svc() -> MemoryService:
        return service

    app.include_router(create_memories_router(svc))

    async def sess() -> AsyncIterator[AsyncSession]:
        yield session

    app.dependency_overrides[get_session] = sess
    app.dependency_overrides[get_current_user_scope] = lambda: scope
    return app


def _client(app: FastAPI) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")


async def _seed(
    session: AsyncSession, thread: Thread, n: int, *, key_prefix: str = "k"
) -> list[ConversationMemory]:
    """``n`` active, unkeyed-slot facts attributed to one message, oldest first."""
    msg = thread.messages[0] if thread.messages else await say(session, thread, "hi")
    repo = MemoryRepository(session, thread.scope)
    rows = []
    for i in range(n):
        rows.append(
            await repo.add(
                contact_id=thread.contact_id,
                conversation_id=thread.conversation_id,
                content=f"fact {key_prefix}{i}",
                confidence=0.8,
                source_message_ids=[msg],
                category="interests",
                fact_key=f"interests.{key_prefix}{i}",
                stated_at=NOW,
            )
        )
    return rows


def _expected_order(rows: list[ConversationMemory]) -> list[str]:
    return [str(r.id) for r in sorted(rows, key=lambda r: (r.created_at, r.id), reverse=True)]


async def _walk(
    c: httpx.AsyncClient, contact_id: uuid.UUID, *, limit: int, **params: Any
) -> list[str]:
    seen: list[str] = []
    cursor = None
    for _ in range(1000):
        q = {"limit": limit, **params} | ({"cursor": cursor} if cursor else {})
        body = assert_page(await c.get(LIST.format(contact_id), params=q))
        assert len(body["items"]) <= limit
        seen += [i["id"] for i in body["items"]]
        cursor = body["next_cursor"]
        if cursor is None:
            return seen
        assert body["items"], "a non-final page must not be empty"
    raise AssertionError("pagination did not terminate")


# --------------------------------------------------------------------------- auth


async def test_unauthenticated_is_problem_json(session: AsyncSession, harness: Harness) -> None:
    app = FastAPI()
    register_error_handlers(app)
    app.include_router(create_memories_router(lambda: harness.service))
    async with _client(app) as c:
        for resp in (
            await c.get(LIST.format(uuid.uuid4())),
            await c.put(f"/v1/memories/{uuid.uuid4()}", json={"content": "x"}),
            await c.delete(f"/v1/memories/{uuid.uuid4()}"),
        ):
            assert_problem(resp, 401, "unauthenticated")


# --------------------------------------------------------------------------- round trip


async def test_list_correct_delete_roundtrip(session: AsyncSession, harness: Harness) -> None:
    alice = await make_user(session)
    t = await make_thread(session, alice)
    m1 = await say(session, t, "I'm a nurse", at=NOW - timedelta(days=1))
    harness.provider.push(facts(fact("work.job_title", "Works as a nurse", ["m1"])))
    created = (
        await harness.service.extract_from_messages(session, alice, t.conversation_id)
    ).created[0]

    async with _client(_app(session, alice, harness.service)) as c:
        body = assert_page(await c.get(LIST.format(t.contact_id)))
        assert [i["id"] for i in body["items"]] == [str(created.id)]
        item = body["items"][0]
        assert item["source_message_ids"] == [str(m1)]
        assert item["created_at"].endswith("Z") and item["basis"] == "stated"
        assert body["next_cursor"] is None

        r = await c.put(f"/v1/memories/{created.id}", json={"content": "Works as a midwife"})
        assert r.status_code == 200, r.text
        corr = r.json()
        assert_contract("Memory", corr)
        assert corr["id"] != str(created.id)
        assert corr["origin"] == "user_correction" and corr["basis"] == "stated"
        assert corr["confidence"] == 1 and corr["key"] == "work.job_title"
        assert corr["source_message_ids"] == [str(m1)]

        # Idempotent: same content (case/whitespace-insensitive) returns the same memory.
        for again in ("Works as a midwife", "  works AS a   midwife "):
            r2 = await c.put(f"/v1/memories/{created.id}", json={"content": again})
            assert r2.status_code == 200
            assert_contract("Memory", r2.json())
            assert r2.json()["id"] == corr["id"]

        active = assert_page(await c.get(LIST.format(t.contact_id)))
        assert [i["id"] for i in active["items"]] == [corr["id"]]
        hist = assert_page(await c.get(LIST.format(t.contact_id), params={"include_history": True}))
        assert [i["id"] for i in hist["items"]] == [corr["id"], str(created.id)]
        assert hist["items"][1]["superseded_by_id"] == corr["id"]

        r = await c.delete(f"/v1/memories/{corr['id']}")
        assert r.status_code == 204 and r.content == b""
        r = await c.delete(f"/v1/memories/{corr['id']}")
        assert r.status_code == 204 and r.content == b""

        # Deleted: never listed, with or without history; deleting does not resurrect.
        assert assert_page(await c.get(LIST.format(t.contact_id)))["items"] == []
        hist = assert_page(await c.get(LIST.format(t.contact_id), params={"include_history": True}))
        assert [i["id"] for i in hist["items"]] == [str(created.id)]
        # A deleted memory cannot be corrected.
        r = await c.put(f"/v1/memories/{corr['id']}", json={"content": "anything"})
        assert_problem(r, 404, "not_found")


async def test_cross_user_is_404(session: AsyncSession, harness: Harness) -> None:
    alice = await make_user(session)
    bob = await make_user(session)
    t = await make_thread(session, alice)
    [row] = await _seed(session, t, 1)

    async with _client(_app(session, bob, harness.service)) as c:
        for resp in (
            await c.get(LIST.format(t.contact_id)),
            await c.get(LIST.format(t.contact_id), params={"include_history": True}),
            await c.put(f"/v1/memories/{row.id}", json={"content": "hijack"}),
            await c.delete(f"/v1/memories/{row.id}"),
            await c.get(LIST.format(uuid.uuid4())),
            await c.delete(f"/v1/memories/{uuid.uuid4()}"),
        ):
            assert_problem(resp, 404, "not_found")
        # Bob's own contact is fine but shows none of Alice's memories.
        bt = await make_thread(session, bob)
        assert assert_page(await c.get(LIST.format(bt.contact_id)))["items"] == []

    # Nothing of Alice's changed.
    async with _client(_app(session, alice, harness.service)) as c:
        body = assert_page(await c.get(LIST.format(t.contact_id)))
        assert [i["id"] for i in body["items"]] == [str(row.id)]
        assert body["items"][0]["content"] == "fact k0"


# --------------------------------------------------------------------------- pagination


async def test_pagination_has_no_duplicates_or_gaps(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    rows = await _seed(session, t, 57)
    other = await make_thread(session, scope)  # another contact's memories never leak in
    await _seed(session, other, 3, key_prefix="o")

    async with _client(_app(session, scope, harness.service)) as c:
        # Default limit is 50.
        first = assert_page(await c.get(LIST.format(t.contact_id)))
        assert len(first["items"]) == 50 and first["next_cursor"] is not None
        expected = _expected_order(rows)
        for limit in (1, 7, 50, 56, 57, 100):
            assert await _walk(c, t.contact_id, limit=limit) == expected
        # Exactly one page when everything fits; next_cursor is null at the end.
        body = assert_page(await c.get(LIST.format(t.contact_id), params={"limit": 57}))
        assert len(body["items"]) == 57 and body["next_cursor"] is None


async def test_pagination_breaks_created_at_ties_by_id(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    rows = await _seed(session, t, 9)
    for r in rows:  # identical sort key: only the id tiebreak orders them
        r.created_at = NOW
    await session.flush()
    expected = [str(r.id) for r in sorted(rows, key=lambda r: r.id, reverse=True)]
    async with _client(_app(session, scope, harness.service)) as c:
        for limit in (1, 2, 4):
            assert await _walk(c, t.contact_id, limit=limit) == expected


async def test_pagination_is_stable_under_inserts(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    rows = await _seed(session, t, 20)
    expected = _expected_order(rows)

    async with _client(_app(session, scope, harness.service)) as c:
        seen: list[str] = []
        cursor = None
        page_no = 0
        while True:
            q: dict[str, Any] = {"limit": 6} | ({"cursor": cursor} if cursor else {})
            body = assert_page(await c.get(LIST.format(t.contact_id), params=q))
            seen += [i["id"] for i in body["items"]]
            cursor = body["next_cursor"]
            page_no += 1
            if cursor is None:
                break
            # New memories arrive while the user scrolls.
            await _seed(session, t, 2, key_prefix=f"new{page_no}-")
        assert seen == expected  # nothing duplicated, nothing skipped, no newcomers mid-walk
        fresh = assert_page(await c.get(LIST.format(t.contact_id), params={"limit": 100}))
        assert len(fresh["items"]) == 20 + 2 * (page_no - 1)
        assert [i["id"] for i in fresh["items"][-20:]] == expected


async def test_history_toggle_and_cursor_binding(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    rows = await _seed(session, t, 8)
    repo = MemoryRepository(session, scope)
    # Supersede 3 facts and expire 2; delete 1 superseded and 1 active.
    for old in rows[:3]:
        await repo.supersede(old.id, rows[7].id)
    for exp in rows[3:5]:
        exp.expires_at = NOW - timedelta(days=1)
    await session.flush()
    await repo.soft_delete(rows[0].id)
    await repo.soft_delete(rows[5].id)

    active_ids = _expected_order([rows[6], rows[7]])
    history_ids = _expected_order([r for i, r in enumerate(rows) if i not in (0, 5)])

    async with _client(_app(session, scope, harness.service)) as c:
        assert await _walk(c, t.contact_id, limit=1) == active_ids
        assert await _walk(c, t.contact_id, limit=1, include_history=False) == active_ids
        assert await _walk(c, t.contact_id, limit=2, include_history=True) == history_ids
        assert str(rows[0].id) not in history_ids and str(rows[5].id) not in history_ids

        # A cursor carries include_history: reusing it with the other value is invalid.
        hist = assert_page(
            await c.get(LIST.format(t.contact_id), params={"include_history": True, "limit": 2})
        )
        act = assert_page(await c.get(LIST.format(t.contact_id), params={"limit": 1}))
        assert hist["next_cursor"] and act["next_cursor"]
        for cursor, flag in ((hist["next_cursor"], "false"), (act["next_cursor"], "true")):
            r = await c.get(
                LIST.format(t.contact_id), params={"cursor": cursor, "include_history": flag}
            )
            body = assert_problem(r, 422, "validation_error")
            assert body["errors"][0]["pointer"] == "query:cursor"
        # ... and so is reusing it for another contact of the same user.
        other = await make_thread(session, scope)
        await _seed(session, other, 2)
        r = await c.get(LIST.format(other.contact_id), params={"cursor": act["next_cursor"]})
        assert_problem(r, 422, "validation_error")


@pytest.mark.parametrize(
    "cursor",
    [
        "garbage",
        "x" * 513,
        "eyJrIjoibWVzc2FnZXMifQ",  # base64url JSON for another listing
        encode_cursor("conversations", CursorPosition(NOW, uuid.uuid4())),
    ],
)
async def test_invalid_cursor_is_422(session: AsyncSession, harness: Harness, cursor: str) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await _seed(session, t, 2)
    async with _client(_app(session, scope, harness.service)) as c:
        r = await c.get(LIST.format(t.contact_id), params={"cursor": cursor})
    body = assert_problem(r, 422, "validation_error")
    assert body["errors"][0]["pointer"] == "query:cursor"
    assert cursor not in r.text  # the rejected value is never echoed


@pytest.mark.parametrize(
    "params", [{"limit": 0}, {"limit": 101}, {"limit": "ten"}, {"include_history": "maybe"}]
)
async def test_bad_query_params_are_422(
    session: AsyncSession, harness: Harness, params: dict[str, Any]
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    async with _client(_app(session, scope, harness.service)) as c:
        r = await c.get(LIST.format(t.contact_id), params=params)
    body = assert_problem(r, 422, "validation_error")
    assert body["errors"][0]["pointer"] == f"query:{next(iter(params))}"


async def test_malformed_ids_are_422(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    async with _client(_app(session, scope, harness.service)) as c:
        assert_problem(await c.get(LIST.format("not-a-uuid")), 422, "validation_error")
        assert_problem(await c.delete("/v1/memories/nope"), 422, "validation_error")


# --------------------------------------------------------------------------- correction


@pytest.mark.parametrize(
    ("body", "pointer"),
    [
        ({"content": ""}, "/content"),
        ({"content": "x" * 501}, "/content"),
        ({"content": 5}, "/content"),
        ({}, "/content"),
        ({"content": "ok", "confidence": 1}, "/confidence"),
    ],
)
async def test_correction_body_validated(
    session: AsyncSession, harness: Harness, body: dict[str, Any], pointer: str
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    [row] = await _seed(session, t, 1)
    async with _client(_app(session, scope, harness.service)) as c:
        r = await c.put(f"/v1/memories/{row.id}", json=body)
        problem = assert_problem(r, 422, "validation_error")
        assert pointer in {e["pointer"] for e in problem["errors"]}
        # Nothing was created or superseded.
        page = assert_page(await c.get(LIST.format(t.contact_id), params={"include_history": 1}))
        assert [i["id"] for i in page["items"]] == [str(row.id)]


async def test_correction_limits_and_blank(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    a, b = await _seed(session, t, 2)
    summary = await MemoryRepository(session, scope).add(
        contact_id=t.contact_id,
        conversation_id=t.conversation_id,
        kind="summary",
        content="A friendly chat.",
        confidence=0.7,
        source_message_ids=[],
        stated_at=NOW,
    )
    async with _client(_app(session, scope, harness.service)) as c:
        r = await c.put(f"/v1/memories/{a.id}", json={"content": "y" * 500})
        assert r.status_code == 200
        assert_contract("Memory", r.json())
        assert_problem(
            await c.put(f"/v1/memories/{b.id}", json={"content": "   \n\t "}),
            422,
            "validation_error",
        )
        assert_problem(
            await c.put(f"/v1/memories/{summary.id}", json={"content": "Different"}),
            422,
            "validation_error",
        )
        listed = assert_page(await c.get(LIST.format(t.contact_id)))
        summ = next(i for i in listed["items"] if i["kind"] == "summary")
        assert summ["key"] is None and summ["category"] is None
        assert_problem(
            await c.put(f"/v1/memories/{uuid.uuid4()}", json={"content": "x"}),
            404,
            "not_found",
        )


async def test_delete_summary_and_unknown(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    rows = await _seed(session, t, 3)
    async with _client(_app(session, scope, harness.service)) as c:
        assert (await c.delete(f"/v1/memories/{rows[1].id}")).status_code == 204
        ids = await _walk(c, t.contact_id, limit=1, include_history=True)
        assert ids == _expected_order([rows[0], rows[2]])
        assert_problem(await c.delete(f"/v1/memories/{uuid.uuid4()}"), 404, "not_found")
