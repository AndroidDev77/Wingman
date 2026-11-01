"""Action API over HTTP: approvals, pause/resume/takeover, audit, sendMessage. Every body
validates against the contract; every POST needs Idempotency-Key; cross-user is 404."""

import uuid

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import Message
from tests.automation.conftest import (
    World,
    assert_contract,
    headers,
    make_world,
    problem,
    set_mode,
)
from tests.automation.test_device_routes import post_event


async def pending_approval(session: AsyncSession, client: httpx.AsyncClient, world: World) -> dict:  # type: ignore[type-arg]
    assert (await post_event(client, world, world.event())).status_code == 202
    resp = await client.get("/v1/approvals", headers=headers(world.scope))
    assert resp.status_code == 200
    assert_contract("ApprovalPage", resp.json())
    [item] = resp.json()["items"]
    return item  # type: ignore[no-any-return]


async def test_approval_list_get_approve(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_client(world)
    item = await pending_approval(session, client, world)
    got = await client.get(f"/v1/approvals/{item['id']}", headers=headers(world.scope))
    assert got.status_code == 200 and got.json() == item
    assert_contract("Approval", got.json())
    # Filters.
    by_conv = await client.get(
        "/v1/approvals",
        params={"conversation_id": item["conversation_id"]},
        headers=headers(world.scope),
    )
    assert [i["id"] for i in by_conv.json()["items"]] == [item["id"]]
    none = await client.get(
        "/v1/approvals", params={"status": "approved"}, headers=headers(world.scope)
    )
    assert none.json() == {"items": [], "next_cursor": None}

    key = "approve-key-0001"
    resp = await client.post(
        f"/v1/approvals/{item['id']}/approve",
        json={"edited_text": "My edit"},
        headers=headers(world.scope, key),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert_contract("ApprovalDecisionResult", body)
    assert body["approval"]["status"] == "approved" and body["approval"]["final_text"] == "My edit"
    assert body["message"]["delivery_state"] == "queued"  # never "sent" in the response
    assert body["message"]["origin"] == "approved_draft"
    assert body["approval"]["message_id"] == body["message"]["id"]
    # The dispatcher sent it right after (mock transport).
    sent = await session.get(Message, uuid.UUID(body["message"]["id"]))
    assert sent is not None and sent.delivery_state.value in ("sent", "delivered")
    # Replay: same body, no second send.
    replay = await client.post(
        f"/v1/approvals/{item['id']}/approve",
        json={"edited_text": "My edit"},
        headers=headers(world.scope, key),
    )
    assert replay.status_code == 200 and replay.headers["Idempotent-Replayed"] == "true"
    assert replay.json() == body
    assert world.connector is not None and len(world.connector.transport_log) == 1
    # A new key: 409 approval_not_pending.
    problem(
        await client.post(
            f"/v1/approvals/{item['id']}/approve", json={}, headers=headers(world.scope)
        ),
        409,
        "approval_not_pending",
    )
    problem(
        await client.post(
            f"/v1/approvals/{item['id']}/reject", json={}, headers=headers(world.scope)
        ),
        409,
        "approval_not_pending",
    )
    # Audit timeline.
    audit = await client.get("/v1/agent-actions", headers=headers(world.scope))
    assert audit.status_code == 200
    assert_contract("AgentActionPage", audit.json())
    types = [a["type"] for a in audit.json()["items"]]
    assert {
        "message_received",
        "draft_generated",
        "approval_requested",
        "approval_approved",
        "send_queued",
        "send_attempted",
        "send_acknowledged",
    } <= set(types)
    approved = await client.get(
        "/v1/agent-actions", params={"type": "approval_approved"}, headers=headers(world.scope)
    )
    [entry] = approved.json()["items"]
    assert entry["details"]["edited"] is True and "My edit" not in str(entry)


async def test_reject_over_http(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_client(world)
    item = await pending_approval(session, client, world)
    resp = await client.post(
        f"/v1/approvals/{item['id']}/reject",
        json={"reason": "not my style"},
        headers=headers(world.scope),
    )
    assert resp.status_code == 200
    assert_contract("ApprovalDecisionResult", resp.json())
    assert resp.json()["message"] is None
    assert resp.json()["approval"]["rejection_reason"] == "not my style"
    assert world.connector is not None and world.connector.transport_log == []
    problem(
        await client.post(
            f"/v1/approvals/{item['id']}/reject", json={"bogus": 1}, headers=headers(world.scope)
        ),
        422,
        "validation_error",
    )


async def test_global_pause_resume(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_client(world)
    state = await client.get("/v1/automation", headers=headers(world.scope))
    assert_contract("GlobalAutomationState", state.json())
    assert state.json() == {"status": "active", "changed_at": None}
    paused = await client.post(
        "/v1/automation/pause", json={"reason": "busy"}, headers=headers(world.scope)
    )
    assert paused.status_code == 200
    assert_contract("GlobalAutomationState", paused.json())
    assert paused.json()["status"] == "paused"
    resumed = await client.post("/v1/automation/resume", json={}, headers=headers(world.scope))
    assert resumed.json()["status"] == "active"
    problem(
        await client.post(
            "/v1/automation/pause", json={}, headers={"X-Test-User": str(world.scope.user_id)}
        ),
        400,
        "idempotency_key_required",
    )


async def test_contact_pause_resume_takeover(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_client(world)
    item = await pending_approval(session, client, world)
    contact = item["contact_id"]
    state = await client.get(f"/v1/contacts/{contact}/automation", headers=headers(world.scope))
    assert_contract("ContactAutomationState", state.json())
    assert state.json()["status"] == "active" and state.json()["effective_mode"] == "assisted"
    paused = await client.post(
        f"/v1/contacts/{contact}/automation/pause", json={}, headers=headers(world.scope)
    )
    assert paused.json()["status"] == "paused" and paused.json()["canceled_approval_count"] == 0
    taken = await client.post(
        f"/v1/contacts/{contact}/automation/takeover", json={}, headers=headers(world.scope)
    )
    assert_contract("ContactAutomationState", taken.json())
    assert taken.json()["status"] == "taken_over" and taken.json()["canceled_approval_count"] == 1
    got = await client.get(f"/v1/approvals/{item['id']}", headers=headers(world.scope))
    assert got.json()["status"] == "canceled"
    resumed = await client.post(
        f"/v1/contacts/{contact}/automation/resume", json={}, headers=headers(world.scope)
    )
    assert resumed.json()["status"] == "active"
    # Resume does not resurrect the canceled approval.
    got = await client.get(f"/v1/approvals/{item['id']}", headers=headers(world.scope))
    assert got.json()["status"] == "canceled"


async def test_send_message_route(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_client(world)
    resp = await post_event(client, world, world.event())
    conv = (await session.get(Message, uuid.UUID(resp.json()["message_id"]))).conversation_id  # type: ignore[union-attr]
    key = "send-key-00001"
    sent = await client.post(
        f"/v1/conversations/{conv}/messages",
        json={"text": "hi!"},
        headers=headers(world.scope, key),
    )
    assert sent.status_code == 202, sent.text
    assert_contract("Message", sent.json())
    assert sent.json()["delivery_state"] == "queued" and sent.json()["origin"] == "user_manual"
    replay = await client.post(
        f"/v1/conversations/{conv}/messages",
        json={"text": "hi!"},
        headers=headers(world.scope, key),
    )
    assert replay.status_code == 202 and replay.headers["Idempotent-Replayed"] == "true"
    assert replay.json() == sent.json()
    assert world.connector is not None and [r.text for r in world.connector.transport_log] == [
        "hi!"
    ]
    problem(
        await client.post(
            f"/v1/conversations/{conv}/messages",
            json={"text": "other"},
            headers=headers(world.scope, key),
        ),
        422,
        "idempotency_key_mismatch",
    )
    problem(
        await client.post(
            f"/v1/conversations/{conv}/messages", json={"text": ""}, headers=headers(world.scope)
        ),
        422,
        "validation_error",
    )


async def test_send_message_allowed_while_taken_over(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_client(world)
    resp = await post_event(client, world, world.event())
    msg = await session.get(Message, uuid.UUID(resp.json()["message_id"]))
    assert msg is not None
    from app.db.models import Conversation

    conv = await session.get(Conversation, msg.conversation_id)
    assert conv is not None
    await client.post(
        f"/v1/contacts/{conv.contact_id}/automation/takeover", json={}, headers=headers(world.scope)
    )
    sent = await client.post(
        f"/v1/conversations/{conv.id}/messages", json={"text": "me"}, headers=headers(world.scope)
    )
    assert sent.status_code == 202
    assert world.connector is not None and [r.text for r in world.connector.transport_log] == ["me"]


async def test_send_message_unsupported_is_typed_422(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual", send="UNSUPPORTED")
    client = make_client(world)
    resp = await post_event(client, world, world.event())
    conv = (await session.get(Message, uuid.UUID(resp.json()["message_id"]))).conversation_id  # type: ignore[union-attr]
    body = problem(
        await client.post(
            f"/v1/conversations/{conv}/messages", json={"text": "x"}, headers=headers(world.scope)
        ),
        422,
        "capability_unsupported",
    )
    assert body["status"] == 422
    assert world.connector is not None and world.connector.transport_log == []


@pytest.mark.parametrize(
    ("method", "path", "json"),
    [
        ("GET", "/v1/approvals/{approval}", None),
        ("POST", "/v1/approvals/{approval}/approve", {}),
        ("POST", "/v1/approvals/{approval}/reject", {}),
        ("GET", "/v1/contacts/{contact}/automation", None),
        ("POST", "/v1/contacts/{contact}/automation/pause", {}),
        ("POST", "/v1/contacts/{contact}/automation/resume", {}),
        ("POST", "/v1/contacts/{contact}/automation/takeover", {}),
        ("POST", "/v1/conversations/{conversation}/messages", {"text": "x"}),
        ("GET", "/v1/connections/{connection}/outbox", None),
        (
            "POST",
            "/v1/connections/{connection}/outbox/claim",
            {"device_install_id": "00000000-0000-4000-8000-000000000001"},
        ),
    ],
)
async def test_cross_user_is_404_everywhere(
    session: AsyncSession, make_client, method, path, json
) -> None:  # type: ignore[no-untyped-def]
    owner = await make_world(session, mode="assisted")
    client = make_client(owner)
    item = await pending_approval(session, client, owner)
    ids = {
        "approval": item["id"],
        "contact": item["contact_id"],
        "conversation": item["conversation_id"],
        "connection": str(owner.connection_id),
    }
    intruder = await make_world(session)
    resp = await client.request(
        method, path.format(**ids), json=json, headers=headers(intruder.scope)
    )
    problem(resp, 404, "not_found")
    # The intruder's lists show nothing of the owner's.
    for listing in ("/v1/approvals", "/v1/agent-actions"):
        assert (await client.get(listing, headers=headers(intruder.scope))).json()["items"] == []
    # The owner's state is untouched.
    got = await client.get(f"/v1/approvals/{item['id']}", headers=headers(owner.scope))
    assert got.json()["status"] == "pending"


async def test_routes_require_auth(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session)
    client = make_client(world)
    for path in ("/v1/approvals", "/v1/agent-actions", "/v1/automation"):
        problem(await client.get(path), 401, "unauthenticated")


async def test_pagination_cursors(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="manual")
    client = make_client(world)
    for i in range(3):
        await post_event(client, world, world.event(f"m{i}", thread=f"t{i}", sender=f"s{i}"))
    page1 = await client.get("/v1/agent-actions", params={"limit": 2}, headers=headers(world.scope))
    assert_contract("AgentActionPage", page1.json())
    assert len(page1.json()["items"]) == 2 and page1.json()["next_cursor"]
    page2 = await client.get(
        "/v1/agent-actions",
        params={"limit": 2, "cursor": page1.json()["next_cursor"]},
        headers=headers(world.scope),
    )
    ids1 = {a["id"] for a in page1.json()["items"]}
    assert ids1.isdisjoint(a["id"] for a in page2.json()["items"])
    problem(
        await client.get(
            "/v1/agent-actions", params={"cursor": "garbage"}, headers=headers(world.scope)
        ),
        422,
        "validation_error",
    )
    _ = set_mode
