"""The memory router: not mounted by default; correct behavior when an app mounts it."""

from collections.abc import AsyncIterator
from datetime import timedelta

import httpx
import pytest
from fastapi import FastAPI
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import MemoryService
from app.api.memories import CONTRACT_STATUS, create_memories_router
from app.db.scope import UserScope
from app.db.session import get_session
from app.main import create_app
from app.security.deps import get_current_user_scope
from tests.ai.memory.conftest import NOW, Harness, fact, facts, make_thread, make_user, say


def test_router_is_not_mounted_by_default() -> None:
    """Pending the additive contract PR (D-004): no memory routes in the real app."""
    assert CONTRACT_STATUS == "pending-contract-pr"
    paths = {getattr(r, "path", "") for r in create_app().routes}
    assert not any("memor" in p for p in paths)


def _app(session: AsyncSession, scope: UserScope, service: MemoryService) -> FastAPI:
    app = FastAPI()

    def svc() -> MemoryService:
        return service

    app.include_router(create_memories_router(svc))

    async def sess() -> AsyncIterator[AsyncSession]:
        yield session

    app.dependency_overrides[get_session] = sess
    app.dependency_overrides[get_current_user_scope] = lambda: scope
    return app


async def _client(app: FastAPI) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://t")


async def test_unauthenticated_without_override(session: AsyncSession, harness: Harness) -> None:
    app = FastAPI()
    app.include_router(create_memories_router(lambda: harness.service))
    async with await _client(app) as c:
        r = await c.delete("/v1/memories/0192f3a0-0000-7000-8000-000000000001")
    assert r.status_code == 401


async def test_list_correct_delete_roundtrip(session: AsyncSession, harness: Harness) -> None:
    alice = await make_user(session)
    bob = await make_user(session)
    t = await make_thread(session, alice)
    m1 = await say(session, t, "I'm a nurse", at=NOW - timedelta(days=1))
    harness.provider.push(facts(fact("work.job_title", "Works as a nurse", ["m1"])))
    created = (
        await harness.service.extract_from_messages(session, alice, t.conversation_id)
    ).created[0]

    async with await _client(_app(session, alice, harness.service)) as c:
        r = await c.get(f"/v1/contacts/{t.contact_id}/memories")
        assert r.status_code == 200
        body = r.json()
        assert [i["id"] for i in body["items"]] == [str(created.id)]
        item = body["items"][0]
        assert item["source_message_ids"] == [str(m1)]
        assert item["created_at"].endswith("Z") and item["basis"] == "stated"
        assert body["next_cursor"] is None

        r = await c.put(f"/v1/memories/{created.id}", json={"content": "Works as a midwife"})
        assert r.status_code == 200
        corr = r.json()
        assert corr["origin"] == "user_correction" and corr["content"] == "Works as a midwife"
        r2 = await c.put(f"/v1/memories/{created.id}", json={"content": "Works as a midwife"})
        assert r2.json()["id"] == corr["id"]

        r = await c.put(f"/v1/memories/{created.id}", json={"content": "x", "evil": 1})
        assert r.status_code == 422

        r = await c.get(f"/v1/contacts/{t.contact_id}/memories", params={"include_history": True})
        assert {i["id"] for i in r.json()["items"]} == {str(created.id), corr["id"]}

        assert (await c.delete(f"/v1/memories/{corr['id']}")).status_code == 204
        assert (await c.delete(f"/v1/memories/{corr['id']}")).status_code == 204
        r = await c.get(f"/v1/contacts/{t.contact_id}/memories")
        assert r.json()["items"] == []

    # Another user gets problem+json 404 for everything of Alice's.
    async with await _client(_app(session, bob, harness.service)) as c:
        for resp in (
            await c.get(f"/v1/contacts/{t.contact_id}/memories"),
            await c.put(f"/v1/memories/{created.id}", json={"content": "hijack"}),
            await c.delete(f"/v1/memories/{created.id}"),
        ):
            assert resp.status_code == 404
            assert resp.headers["content-type"].startswith("application/problem+json")
            assert resp.json()["code"] == "not_found"


@pytest.mark.parametrize("content", ["", "x" * 501])
async def test_correction_body_validated(
    session: AsyncSession, harness: Harness, content: str
) -> None:
    scope = await make_user(session)
    async with await _client(_app(session, scope, harness.service)) as c:
        r = await c.put(
            "/v1/memories/0192f3a0-0000-7000-8000-000000000001", json={"content": content}
        )
    assert r.status_code == 422
