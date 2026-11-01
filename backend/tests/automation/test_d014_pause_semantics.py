"""D-014 (integrator, owner-side decision): pause stops AUTOMATED sends only; a send the
user explicitly approved, or the user's own ``sendMessage``, still goes during a pause.
Takeover cancels everything automated for that contact, including approved-but-unsent
drafts, pending approvals and unclaimed instructions.

Also: a queued send is committed before any transport call (D-006 replay), so a failure
after transmitting can never roll back the record of a real send.
"""

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.api._automation_common import commit_then_dispatch
from app.connectors import MockConnector
from app.connectors.models import SendRequest, SendResult
from app.db.enums import (
    ApprovalStatus,
    ContactAutomationStatus,
    DeliveryState,
    MessageDirection,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import Approval, Conversation, Message, OutboxInstruction
from app.db.repositories import ConversationRepository
from tests.automation.conftest import assert_contract, headers, make_world, set_mode
from tests.automation.test_concurrency import ingest_and_run, setup, tx
from tests.automation.test_device_routes import (
    claim,
    post_event,
    queued_device_send,
    sms_world,
)


async def _outbound(session: AsyncSession, user_id: uuid.UUID) -> list[Message]:
    return list(
        (
            await session.scalars(
                select(Message)
                .where(Message.user_id == user_id, Message.direction == MessageDirection.OUTBOUND)
                .order_by(Message.created_at, Message.id)
            )
        ).all()
    )


async def test_approve_during_global_and_contact_pause_sends_once_over_http(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="assisted")
    client = make_client(world)
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    [item] = (await client.get("/v1/approvals", headers=headers(world.scope))).json()["items"]

    pause = await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    assert pause.status_code == 200 and pause.json()["status"] == "paused"
    contact_pause = await client.post(
        f"/v1/contacts/{item['contact_id']}/automation/pause", json={}, headers=headers(world.scope)
    )
    assert contact_pause.status_code == 200
    # A pause does not cancel the pending approval: the user may still approve it.
    assert contact_pause.json()["canceled_approval_count"] == 0

    resp = await client.post(
        f"/v1/approvals/{item['id']}/approve", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    assert_contract("ApprovalDecisionResult", resp.json())
    assert world.connector is not None
    assert [r.text for r in world.connector.transport_log] == [item["draft"]["text"]]
    [msg] = await _outbound(session, world.scope.user_id)
    assert msg.origin is MessageOrigin.APPROVED_DRAFT
    assert msg.delivery_state in (DeliveryState.SENT, DeliveryState.DELIVERED)


async def test_send_message_during_pause_goes_out(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    client = make_client(world)
    resp = await post_event(client, world, world.event("hi"))
    conv_id = (await session.get(Message, uuid.UUID(resp.json()["message_id"]))).conversation_id  # type: ignore[union-attr]
    conv = await session.get(Conversation, conv_id)
    assert conv is not None
    await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    await client.post(
        f"/v1/contacts/{conv.contact_id}/automation/pause", json={}, headers=headers(world.scope)
    )
    sent = await client.post(
        f"/v1/conversations/{conv_id}/messages", json={"text": "mine"}, headers=headers(world.scope)
    )
    assert sent.status_code == 202
    assert world.connector is not None
    assert [r.text for r in world.connector.transport_log] == ["mine"]


async def test_pause_leaves_approved_and_manual_instructions_cancels_autopilot(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    """Device outbox: during a pause the claim leases the user-approved and user-authored
    sends and cancels the queued autopilot one."""
    world = await sms_world(session, mode="autopilot")
    client = make_client(world)
    auto = await queued_device_send(session, client, world)  # queued autopilot reply
    assert auto.origin is MessageOrigin.AUTOPILOT
    # Dispatch it to an instruction (no lease yet), then add a manual and an approved one.
    await world.dispatcher.dispatch(session, world.scope, auto.id)
    conv = await ConversationRepository(session, world.scope).require(auto.conversation_id)
    manual = await client.post(
        f"/v1/conversations/{conv.id}/messages", json={"text": "mine"}, headers=headers(world.scope)
    )
    assert manual.status_code == 202
    await set_mode(session, world.scope, "assisted")
    assert (await post_event(client, world, world.event("again?"))).status_code == 202
    [item] = (await client.get("/v1/approvals", headers=headers(world.scope))).json()["items"]
    approved = await client.post(
        f"/v1/approvals/{item['id']}/approve", json={}, headers=headers(world.scope)
    )
    assert approved.status_code == 200

    pause = await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    assert pause.status_code == 200
    leased = (await claim(client, world)).json()["instructions"]
    origins = sorted(i["authorization"]["origin"] for i in leased)
    assert origins == ["approved_draft", "user_manual"]
    await session.refresh(auto)
    assert auto.delivery_state is DeliveryState.CANCELED


async def test_takeover_cancels_approved_but_unsent_and_pending_and_unclaimed(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session, mode="assisted")
    client = make_client(world)
    # 1. Approved but unsent: approved, instruction pending, never claimed.
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    [first] = (await client.get("/v1/approvals", headers=headers(world.scope))).json()["items"]
    ok = await client.post(
        f"/v1/approvals/{first['id']}/approve", json={}, headers=headers(world.scope)
    )
    approved_msg_id = uuid.UUID(ok.json()["message"]["id"])
    # 2. A pending approval for a newer inbound message (it supersedes nothing approved).
    assert (await post_event(client, world, world.event("still there?"))).status_code == 202
    [pending] = (await client.get("/v1/approvals", headers=headers(world.scope))).json()["items"]
    # 3. The user's own unsent message must survive the takeover.
    manual = await client.post(
        f"/v1/conversations/{first['conversation_id']}/messages",
        json={"text": "mine"},
        headers=headers(world.scope),
    )
    manual_id = uuid.UUID(manual.json()["id"])

    resp = await client.post(
        f"/v1/contacts/{first['contact_id']}/automation/takeover",
        json={},
        headers=headers(world.scope),
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["status"] == "taken_over"
    assert body["canceled_approval_count"] == 1 and body["canceled_message_count"] == 1

    approval = await session.get(Approval, uuid.UUID(pending["id"]))
    assert approval is not None and approval.status is ApprovalStatus.CANCELED
    approved_msg = await session.get(Message, approved_msg_id)
    assert approved_msg is not None and approved_msg.delivery_state is DeliveryState.CANCELED
    ins = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == approved_msg_id)
    )
    assert ins is not None and ins.state is OutboxInstructionState.CANCELED
    # Only the user's own message is still leasable.
    [left] = (await claim(client, world)).json()["instructions"]
    assert left["outbound_message_id"] == str(manual_id)
    assert left["authorization"]["origin"] == "user_manual"


def test_takeover_cancels_every_automated_origin_pause_only_autopilot() -> None:
    from app.services.dispatcher import AUTOMATED_ORIGINS, PAUSE_CANCELS

    assert set(PAUSE_CANCELS) == {MessageOrigin.AUTOPILOT}
    assert set(AUTOMATED_ORIGINS) == {MessageOrigin.AUTOPILOT, MessageOrigin.APPROVED_DRAFT}
    assert MessageOrigin.USER_MANUAL not in AUTOMATED_ORIGINS
    _ = ContactAutomationStatus


# --------------------------------------------------------------------------- commit first


class _CrashAfterTransmit(MockConnector):
    """Transmits, then fails once before the caller can record the result."""

    crashed = False

    async def send_message(self, request: SendRequest) -> SendResult:
        result = await super().send_message(request)
        if not self.crashed:
            self.crashed = True
            raise RuntimeError("process died after the transport call")
        return result


async def test_send_is_committed_before_transport_and_replays_after_a_crash(
    committed: async_sessionmaker[AsyncSession],
) -> None:
    world = await setup(committed, mode="manual")
    assert world.connector is not None
    crashing = _CrashAfterTransmit(
        user_id=world.scope.user_id,
        connection_id=world.connection_id,
        capabilities=world.connector.capabilities(),
    )
    world.registry.register(world.connection_id, crashing)
    await ingest_and_run(committed, world, world.event("hi", thread="thread-1"))
    # (the registry hands the inbound event to ``crashing``, so it knows the thread)

    async with committed() as s:
        conv = await s.scalar(
            select(Conversation).where(Conversation.user_id == world.scope.user_id)
        )
        assert conv is not None
        message = await world.controller.send_manual(s, world.scope, conv.id, "hello")
        message_id = message.id
        await commit_then_dispatch(s, world.services, world.scope, message_id)
        await s.commit()

    # The queued message survived the crash (committed before the transport call).
    async with committed() as s:
        row = await s.get(Message, message_id)
        assert row is not None and row.delivery_state is DeliveryState.QUEUED
    assert len(crashing.transport_log) == 1

    # The retry derives the same per-attempt key: the connector replays, no second send.
    async def again(s: AsyncSession) -> None:
        await world.dispatcher.dispatch(s, world.scope, message_id)

    await tx(committed, again)
    async with committed() as s:
        row = await s.get(Message, message_id)
        assert row is not None and row.delivery_state in (
            DeliveryState.SENT,
            DeliveryState.DELIVERED,
        )
        assert row.attempt_count == 1
        n = await s.scalar(
            select(func.count()).select_from(Message).where(Message.user_id == world.scope.user_id)
        )
        assert n == 2  # one inbound, one outbound
    assert len(crashing.transport_log) == 1
