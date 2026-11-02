"""Contract 1.3.0 (ADR 0008): only linked, complete chat messages reach the pipeline;
everything else is a notice and is never replied to or merged. Experimental and
UNVERIFIED connections are approval-only; UNSUPPORTED dating connections get an
assisted (draft-only) delivery, and the user's report is ``user_reported_sent``,
never ``sent``/``delivered``."""

import dataclasses
import uuid
from typing import Any

import httpx
import pytest
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    ApprovalReason,
    AssistedDeliveryStatus,
    AutomationMode,
    CapabilityVerification,
    ContactAutomationStatus,
    DeliveryState,
    GlobalAutomationStatus,
    MessageDirection,
    MessageOrigin,
)
from app.db.models import (
    AgentAction,
    Approval,
    AssistedDelivery,
    Contact,
    Conversation,
    Draft,
    InboundNotice,
    Message,
    OutboxInstruction,
)
from app.services.automation import PolicyOutcome, evaluate_policy
from app.services.dispatcher import RecheckReason, recheck_send
from tests.automation.conftest import (
    World,
    assert_contract,
    headers,
    make_world,
    problem,
)
from tests.automation.test_policy import BASE
from tests.db.conftest import make_user
from tests.integrations.conftest import inbound13
from tests.integrations.helpers import experimental_world


async def post(client: httpx.AsyncClient, world: World, raw: dict[str, Any]) -> httpx.Response:
    return await client.post(
        f"/v1/connections/{world.connection_id}/events",
        json=raw,
        headers=headers(world.scope, raw["event_id"]),
    )


async def count(session: AsyncSession, model: Any, world: World, *where: Any) -> int:
    return int(
        await session.scalar(
            select(func.count())
            .select_from(model)
            .where(model.user_id == world.scope.user_id, *where)
        )
        or 0
    )


async def notification_world(session: AsyncSession, *, send: str = "UNSUPPORTED") -> World:
    """Hinge through the notification listener (non-experimental), autopilot on."""
    return await make_world(
        session, platform="hinge", send=send, device_side=True, mode="autopilot"
    )


# --------------------------------------------------------------------------- notices

NON_PIPELINE = [
    pytest.param({"kind": "match"}, id="match"),
    pytest.param({"kind": "like_comment"}, id="like_comment"),
    pytest.param({"kind": "expiry"}, id="expiry"),
    pytest.param({"kind": "system"}, id="system"),
    pytest.param({"kind": "unknown"}, id="unknown"),
    pytest.param({"identity": "unlinked"}, id="unlinked"),
    pytest.param({"identity": "ambiguous"}, id="ambiguous"),
    pytest.param({"truncated": True}, id="truncated"),
    pytest.param({"content_hidden": True}, id="hidden"),
]


@pytest.mark.parametrize("fields", NON_PIPELINE)
@pytest.mark.parametrize("send", ["VERIFIED", "UNSUPPORTED"])
async def test_non_chat_or_uncertain_events_never_draft_or_send(
    session: AsyncSession, make_v13_client, fields: dict[str, Any], send: str
) -> None:  # type: ignore[no-untyped-def]
    world = await notification_world(session, send=send)
    client = make_v13_client(world)
    raw = inbound13(world, source="notification", **fields)
    resp = await post(client, world, raw)
    assert resp.status_code == 202, resp.text
    body = resp.json()
    assert_contract("ConnectorEventIngestResult", body)
    assert body["message_id"] is None and body["notice_id"] is not None
    # Stored and shown; nothing else exists.
    for model in (
        Message,
        Contact,
        Conversation,
        Draft,
        Approval,
        AssistedDelivery,
        OutboxInstruction,
    ):
        assert await count(session, model, world) == 0, model.__name__
    assert await count(session, InboundNotice, world) == 1
    assert world.llm.calls == []  # no memory, analysis or reply model call at all
    page = await client.get("/v1/inbound-notices", headers=headers(world.scope))
    assert page.status_code == 200
    assert_contract("InboundNoticePage", page.json())
    assert page.json()["items"][0]["id"] == body["notice_id"]
    # A repeat is deduplicated like a message.
    again = dict(raw, event_id=str(uuid.uuid4()))
    dup = await post(client, world, again)
    assert dup.status_code == 200 and dup.json()["outcome"] == "duplicate_message"
    assert await count(session, InboundNotice, world) == 1


async def test_two_ambiguous_senders_with_one_name_are_never_merged(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await notification_world(session)
    client = make_v13_client(world)
    for _ in range(2):
        raw = inbound13(world, identity="ambiguous", label="Alex", sender="label:Alex", thread=None)
        assert (await post(client, world, raw)).status_code == 202
    assert await count(session, Contact, world) == 0
    assert await count(session, Conversation, world) == 0
    assert await count(session, InboundNotice, world) == 2


async def test_source_must_match_transport(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await notification_world(session)
    client = make_v13_client(world)
    body = problem(
        await post(client, world, inbound13(world, source="unofficial_api")),
        422,
        "validation_error",
    )
    assert body["errors"][0]["pointer"] == "/payload/source"


async def test_hidden_content_with_text_is_rejected(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world = await notification_world(session)
    client = make_v13_client(world)
    raw = inbound13(world)
    raw["payload"]["content_hidden"] = True
    raw["payload"]["text"] = "leaked"
    problem(await post(client, world, raw), 422, "validation_error")


async def test_linked_chat_message_still_enters_the_pipeline(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await notification_world(session, send="UNVERIFIED")
    client = make_v13_client(world)
    resp = await post(
        client,
        world,
        inbound13(world, kind="chat_message", identity="linked", source="notification"),
    )
    assert resp.status_code == 202, resp.text
    assert resp.json()["message_id"] is not None and resp.json()["notice_id"] is None
    assert await count(session, InboundNotice, world) == 0
    assert "ReplyOutput" in world.llm.calls  # the control: a message is drafted
    approvals = (
        await session.scalars(select(Approval).where(Approval.user_id == world.scope.user_id))
    ).all()
    assert len(approvals) == 1  # UNVERIFIED send: approval, never autopilot
    assert ApprovalReason.CAPABILITY_UNVERIFIED in approvals[0].reasons


# --------------------------------------------------------------------------- policy


def test_policy_experimental_unverified_is_approval_only() -> None:
    d = evaluate_policy(
        dataclasses.replace(
            BASE,
            mode=AutomationMode.AUTOPILOT,
            send_capability=CapabilityVerification.VERIFIED,
            experimental_unverified=True,
        )
    )
    assert d.outcome is PolicyOutcome.APPROVAL
    assert ApprovalReason.CAPABILITY_UNVERIFIED in d.reasons
    assert d.checks["experimental_unverified"] is True


def test_policy_assisted_only_when_unsupported_and_available() -> None:
    unsupported = dataclasses.replace(
        BASE, send_capability=CapabilityVerification.UNSUPPORTED, assisted_available=True
    )
    assert evaluate_policy(unsupported).outcome is PolicyOutcome.ASSISTED
    # Without an assisted path (e.g. SMS) it is still a block.
    blocked = dataclasses.replace(unsupported, assisted_available=False)
    assert evaluate_policy(blocked).outcome is PolicyOutcome.STOP
    # Manual mode, takeover and block rules still stop first.
    for p in (
        dataclasses.replace(unsupported, mode=AutomationMode.MANUAL),
        dataclasses.replace(unsupported, contact_status=ContactAutomationStatus.TAKEN_OVER),
        dataclasses.replace(unsupported, rule_actions=("block_send",)),
    ):
        assert evaluate_policy(p).outcome is PolicyOutcome.STOP
    # Paused: still a draft-only delivery (nothing automated is ever sent by it).
    paused = dataclasses.replace(unsupported, global_status=GlobalAutomationStatus.PAUSED)
    assert evaluate_policy(paused).outcome is PolicyOutcome.ASSISTED


def test_recheck_cancels_autopilot_on_experimental_unverified() -> None:
    kw: dict[str, Any] = {
        "send_capability": CapabilityVerification.VERIFIED,
        "global_status": GlobalAutomationStatus.ACTIVE,
        "contact_status": ContactAutomationStatus.ACTIVE,
    }
    assert (
        recheck_send(origin=MessageOrigin.AUTOPILOT, experimental_unverified=True, **kw)
        is RecheckReason.CAPABILITY_UNVERIFIED
    )
    assert (
        recheck_send(origin=MessageOrigin.APPROVED_DRAFT, experimental_unverified=True, **kw)
        is None
    )
    assert recheck_send(origin=MessageOrigin.AUTOPILOT, **kw) is None
    assert recheck_send(origin=MessageOrigin.ASSISTED_DRAFT, **kw) is RecheckReason.NOT_QUEUED


@pytest.mark.parametrize("send", ["VERIFIED", "UNVERIFIED"])
async def test_autopilot_on_experimental_connection_never_sends(
    session: AsyncSession, make_v13_client, send: str
) -> None:  # type: ignore[no-untyped-def]
    world = await experimental_world(session, send=send)
    client = make_v13_client(world)
    for text in ("hey", "how are you?", "free saturday?"):
        resp = await post(client, world, inbound13(world, text=text, source="unofficial_api"))
        assert resp.status_code == 202, resp.text
    automated = await count(session, Message, world, Message.direction == MessageDirection.OUTBOUND)
    assert automated == 0
    assert await count(session, OutboxInstruction, world) == 0
    pending = (
        await session.scalars(select(Approval).where(Approval.user_id == world.scope.user_id))
    ).all()
    assert pending and all(ApprovalReason.CAPABILITY_UNVERIFIED in a.reasons for a in pending)


async def test_autopilot_on_unsupported_send_yields_assisted_never_a_send(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await notification_world(session, send="UNSUPPORTED")
    client = make_v13_client(world)
    resp = await post(client, world, inbound13(world, source="notification"))
    assert resp.status_code == 202, resp.text
    assert await count(session, Message, world, Message.direction == MessageDirection.OUTBOUND) == 0
    assert await count(session, OutboxInstruction, world) == 0
    assert await count(session, Approval, world) == 0
    page = await client.get("/v1/assisted-deliveries", headers=headers(world.scope))
    assert page.status_code == 200
    assert_contract("AssistedDeliveryPage", page.json())
    [item] = page.json()["items"]
    assert item["status"] == "pending" and item["platform"] == "hinge"
    assert item["external_thread_id"] == "match-thread-77"
    actions = (
        await session.scalars(
            select(AgentAction.type).where(AgentAction.user_id == world.scope.user_id)
        )
    ).all()
    assert "assisted_delivery_queued" in [a.value for a in actions]
    assert "policy_blocked" not in [a.value for a in actions]
    # A newer inbound supersedes it.
    await post(client, world, inbound13(world, text="hello?", source="notification"))
    statuses = sorted(
        a.status.value
        for a in (
            await session.scalars(
                select(AssistedDelivery).where(AssistedDelivery.user_id == world.scope.user_id)
            )
        ).all()
    )
    assert statuses == ["canceled", "pending"]


async def test_sms_unsupported_send_still_blocks(session: AsyncSession) -> None:
    world = await make_world(
        session, platform="sms", send="UNSUPPORTED", device_side=True, mode="autopilot"
    )
    from tests.automation.test_pipeline import inbound

    result = await inbound(session, world, "hi")
    assert result.outcome.value == "blocked"
    assert await count(session, AssistedDelivery, world) == 0


# --------------------------------------------------------------------------- user_reported_sent


async def _assisted(session: AsyncSession, make_v13_client) -> tuple[World, httpx.AsyncClient, str]:  # type: ignore[no-untyped-def]
    world = await notification_world(session)
    client = make_v13_client(world)
    await post(client, world, inbound13(world, source="notification"))
    page = await client.get("/v1/assisted-deliveries", headers=headers(world.scope))
    return world, client, page.json()["items"][0]["id"]


async def test_confirm_is_user_reported_sent_never_delivered(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world, client, aid = await _assisted(session, make_v13_client)
    resp = await client.post(
        f"/v1/assisted-deliveries/{aid}/confirm",
        json={"sent_text": "Sure, Saturday works"},
        headers=headers(world.scope, "confirm-key-0001"),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert_contract("AssistedDeliveryResult", body)
    msg = body["message"]
    assert msg["origin"] == "assisted_draft"
    assert msg["delivery_state"] == "user_reported_sent"
    assert msg["sent_at"] is None and msg["delivered_at"] is None
    assert body["assisted_delivery"]["status"] == "user_reported_sent"
    assert body["assisted_delivery"]["final_text"] == "Sure, Saturday works"
    # Listed in the thread exactly as stored: never "sent" or "delivered".
    conv = msg["conversation_id"]
    thread = await client.get(f"/v1/conversations/{conv}/messages", headers=headers(world.scope))
    assert_contract("MessagePage", thread.json())
    states = {m["delivery_state"] for m in thread.json()["items"] if m["direction"] == "outbound"}
    assert states == {"user_reported_sent"}
    row = await session.get(Message, uuid.UUID(msg["id"]))
    assert row is not None and row.delivery_state is DeliveryState.USER_REPORTED_SENT
    # The dispatcher never transmits it.
    result = await world.dispatcher.dispatch(session, world.scope, row.id)
    assert result.outcome.value == "noop"
    assert await count(session, OutboxInstruction, world) == 0
    # Replay returns the same; a second confirm with a new key is 409.
    replay = await client.post(
        f"/v1/assisted-deliveries/{aid}/confirm",
        json={"sent_text": "Sure, Saturday works"},
        headers=headers(world.scope, "confirm-key-0001"),
    )
    assert replay.status_code == 200 and replay.headers["Idempotent-Replayed"] == "true"
    problem(
        await client.post(
            f"/v1/assisted-deliveries/{aid}/confirm", json={}, headers=headers(world.scope)
        ),
        409,
        "assisted_delivery_not_pending",
    )


async def test_db_forbids_user_reported_sent_as_sent(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    from sqlalchemy.exc import IntegrityError

    world, client, aid = await _assisted(session, make_v13_client)
    resp = await client.post(
        f"/v1/assisted-deliveries/{aid}/confirm", json={}, headers=headers(world.scope)
    )
    mid = uuid.UUID(resp.json()["message"]["id"])
    from app.db.repositories import MessageRepository

    with pytest.raises(IntegrityError):
        async with session.begin_nested():
            await MessageRepository(session, world.scope).update_delivery(
                mid,
                delivery_state=DeliveryState.DELIVERED,
                sent_at=world.clock.now,
                delivered_at=world.clock.now,
            )


async def test_dismiss_and_cross_user(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world, client, aid = await _assisted(session, make_v13_client)
    stranger = await make_user(session)
    for path in (
        f"/v1/assisted-deliveries/{aid}/confirm",
        f"/v1/assisted-deliveries/{aid}/dismiss",
    ):
        problem(await client.post(path, json={}, headers=headers(stranger)), 404, "not_found")
    other = await client.get("/v1/assisted-deliveries", headers=headers(stranger))
    assert other.json()["items"] == []
    other_notices = await client.get("/v1/inbound-notices", headers=headers(stranger))
    assert other_notices.json()["items"] == []
    resp = await client.post(
        f"/v1/assisted-deliveries/{aid}/dismiss", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    assert_contract("AssistedDeliveryResult", resp.json())
    assert (
        resp.json()["assisted_delivery"]["status"] == "dismissed" and resp.json()["message"] is None
    )
    assert await count(session, Message, world, Message.direction == MessageDirection.OUTBOUND) == 0


async def test_takeover_cancels_pending_assisted(session: AsyncSession, make_v13_client) -> None:  # type: ignore[no-untyped-def]
    world, client, aid = await _assisted(session, make_v13_client)
    row = await session.get(AssistedDelivery, uuid.UUID(aid))
    assert row is not None
    resp = await client.post(
        f"/v1/contacts/{row.contact_id}/automation/takeover", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    await session.refresh(row)
    assert (
        row.status is AssistedDeliveryStatus.CANCELED and row.cancel_reason == "automation_takeover"
    )
