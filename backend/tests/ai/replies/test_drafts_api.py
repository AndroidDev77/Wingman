"""``POST /v1/conversations/{id}/drafts`` over HTTP (router not mounted in ``main.py`` yet).

The test app mounts only the drafts router plus the shared problem+json handlers. The
DB session is the test's rolled-back session; auth comes from an ``X-Test-User`` header
via ``dependency_overrides`` (D-007); the generator uses the scripted mock model.
"""

import uuid
from collections.abc import AsyncIterator
from typing import Any

import httpx
import pytest
from fastapi import FastAPI, Header, HTTPException
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.drafts import get_reply_generator, router
from app.api.errors import register_error_handlers
from app.db.enums import AgentActionType
from app.db.models import AgentAction, Message
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope
from tests.ai.replies.conftest import (
    Harness,
    assert_contract,
    contact_says,
    make_thread,
    make_user,
    reply,
    user_says,
)

KEY = "draft-key-0001"


def build_app(session: AsyncSession, harness: Harness) -> FastAPI:
    app = FastAPI()
    register_error_handlers(app)
    app.include_router(router)

    async def test_session() -> AsyncIterator[AsyncSession]:
        yield session

    async def test_scope(x_test_user: str | None = Header(default=None)) -> UserScope:
        if x_test_user is None:
            raise HTTPException(status_code=401, detail="no test user")
        return UserScope(uuid.UUID(x_test_user))

    app.dependency_overrides[get_session] = test_session
    app.dependency_overrides[get_current_user_scope] = test_scope
    app.dependency_overrides[get_reply_generator] = lambda: harness.generator
    return app


@pytest.fixture
async def client(session: AsyncSession, harness: Harness) -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=build_app(session, harness))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


def headers(scope: UserScope, key: str | None = KEY) -> dict[str, str]:
    out = {"X-Test-User": str(scope.user_id)}
    if key is not None:
        out["Idempotency-Key"] = key
    return out


def problem(resp: httpx.Response, status: int, code: str) -> dict[str, Any]:
    assert resp.status_code == status, resp.text
    assert resp.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = resp.json()
    assert_contract("Problem", body)
    assert body["code"] == code
    return body


async def test_create_draft_returns_contract_draft(
    session: AsyncSession, harness: Harness, client: httpx.AsyncClient
) -> None:
    scope = await make_user(session)
    thread = await make_thread(session, scope)
    await user_says(session, thread, "hey!")
    await contact_says(session, thread, "Back from my trip!")
    harness.provider.push(reply("Welcome back! What was the highlight?", message_refs=["m2"]))

    resp = await client.post(
        f"/v1/conversations/{thread.conversation_id}/drafts",
        json={"guidance": "ask about the trip"},
        headers=headers(scope),
    )

    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert_contract("Draft", body)
    assert body["decision"] == "send_candidate"
    assert body["conversation_id"] == str(thread.conversation_id)
    assert "Idempotent-Replayed" not in resp.headers
    # Never sends: no new message rows.
    count = await session.scalar(
        select(func.count()).select_from(Message).where(Message.user_id == scope.user_id)
    )
    assert count == 2


async def test_ask_user_and_no_send_are_201(
    session: AsyncSession, harness: Harness, client: httpx.AsyncClient
) -> None:
    scope = await make_user(session)
    thread = await make_thread(session, scope)
    resp = await client.post(
        f"/v1/conversations/{thread.conversation_id}/drafts", json={}, headers=headers(scope)
    )
    assert resp.status_code == 201
    assert_contract("Draft", resp.json())
    assert resp.json()["decision"] == "ask_user"

    await contact_says(session, thread, "hi")
    harness.provider.push("garbage")
    resp = await client.post(
        f"/v1/conversations/{thread.conversation_id}/drafts",
        json={},
        headers=headers(scope, "draft-key-0002"),
    )
    assert resp.status_code == 201
    assert_contract("Draft", resp.json())
    assert resp.json()["decision"] == "no_send"


async def test_idempotent_replay_does_not_call_the_model_twice(
    session: AsyncSession, harness: Harness, client: httpx.AsyncClient
) -> None:
    scope = await make_user(session)
    thread = await make_thread(session, scope)
    await contact_says(session, thread, "hey")
    harness.provider.push(reply("hey you! how's your week?"))
    url = f"/v1/conversations/{thread.conversation_id}/drafts"

    first = await client.post(url, json={"guidance": None}, headers=headers(scope))
    second = await client.post(url, json={"guidance": None}, headers=headers(scope))

    assert first.status_code == second.status_code == 201
    assert second.json() == first.json()
    assert second.headers["Idempotent-Replayed"] == "true"
    assert harness.provider.calls == 1
    audits = await session.scalar(
        select(func.count())
        .select_from(AgentAction)
        .where(
            AgentAction.user_id == scope.user_id,
            AgentAction.type == AgentActionType.DRAFT_GENERATED,
        )
    )
    assert audits == 1

    mismatch = await client.post(url, json={"guidance": "different"}, headers=headers(scope))
    problem(mismatch, 422, "idempotency_key_mismatch")


async def test_missing_or_bad_idempotency_key(
    session: AsyncSession, client: httpx.AsyncClient
) -> None:
    scope = await make_user(session)
    thread = await make_thread(session, scope)
    url = f"/v1/conversations/{thread.conversation_id}/drafts"
    problem(
        await client.post(url, json={}, headers=headers(scope, None)),
        400,
        "idempotency_key_required",
    )
    problem(
        await client.post(url, json={}, headers=headers(scope, "bad key!")), 400, "validation_error"
    )


async def test_unknown_fields_rejected(session: AsyncSession, client: httpx.AsyncClient) -> None:
    scope = await make_user(session)
    thread = await make_thread(session, scope)
    resp = await client.post(
        f"/v1/conversations/{thread.conversation_id}/drafts",
        json={"guidance": "x", "send": True},
        headers=headers(scope),
    )
    problem(resp, 422, "validation_error")


async def test_other_users_conversation_is_404(
    session: AsyncSession, harness: Harness, client: httpx.AsyncClient
) -> None:
    alice = await make_user(session)
    bob = await make_user(session)
    thread = await make_thread(session, alice)
    await contact_says(session, thread, "hi")
    resp = await client.post(
        f"/v1/conversations/{thread.conversation_id}/drafts", json={}, headers=headers(bob)
    )
    problem(resp, 404, "not_found")
    assert harness.provider.calls == 0


async def test_unauthenticated_is_401(session: AsyncSession, client: httpx.AsyncClient) -> None:
    resp = await client.post(
        f"/v1/conversations/{uuid.uuid4()}/drafts", json={}, headers={"Idempotency-Key": KEY}
    )
    problem(resp, 401, "unauthenticated")
