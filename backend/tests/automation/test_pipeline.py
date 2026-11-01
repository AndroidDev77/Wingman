"""The vertical slice in-process: inbound event -> memory -> analysis -> draft -> policy
-> approval or dispatch -> audit. Acceptance criteria of WING-011, each a named test."""

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.events import ConnectorEvent
from app.db.enums import (
    AgentActionType,
    ApprovalReason,
    ApprovalStatus,
    ContactAutomationStatus,
    DeliveryState,
    MessageDirection,
    MessageOrigin,
)
from app.db.models import AgentAction, Approval, Draft, Message
from app.db.repositories import ConnectionRepository
from app.services.automation import PipelineOutcome
from app.services.ingest import IngestOutcome
from tests.automation.conftest import World, make_world, reply


async def ingest(
    session: AsyncSession, world: World, raw: dict
) -> tuple[IngestOutcome, uuid.UUID | None]:
    connection = await ConnectionRepository(session, world.scope).require(world.connection_id)
    result = await world.services.ingest.ingest(
        session,
        world.scope,
        connection,
        ConnectorEvent.model_validate(raw),
        request_hash=world.services.ingest.request_hash(raw),
    )
    return result.outcome, result.new_inbound_message_id


async def inbound(session: AsyncSession, world: World, text: str = "Back from Lisbon!", **kw):
    outcome, message_id = await ingest(session, world, world.event(text, **kw))
    assert outcome is IngestOutcome.ACCEPTED and message_id is not None
    return await world.controller.handle_inbound_message(session, world.scope, message_id)


async def outbound(session: AsyncSession, world: World) -> list[Message]:
    rows = await session.scalars(
        select(Message)
        .where(
            Message.user_id == world.scope.user_id, Message.direction == MessageDirection.OUTBOUND
        )
        .order_by(Message.created_at)
    )
    return list(rows.all())


async def audit_types(session: AsyncSession, world: World) -> list[str]:
    rows = await session.scalars(
        select(AgentAction.type)
        .where(AgentAction.user_id == world.scope.user_id)
        .order_by(AgentAction.occurred_at, AgentAction.id)
    )
    return [t.value for t in rows.all()]


async def prime_outbound(session: AsyncSession, world: World) -> None:
    """One earlier sent message so drafts are not ``first_message``."""
    await inbound(session, world, "hi")
    await session.flush()


# --------------------------------------------------------------------------- acceptance


async def test_mock_message_flows_to_outbound_send_in_autopilot(session: AsyncSession) -> None:
    world = await make_world(session, mode="autopilot")
    world.llm.replies.extend([reply("Hey!"), reply("Welcome back! How was it?")])
    first = await inbound(session, world, "hi there")
    # First reply in a conversation needs approval (first_message), even in autopilot.
    assert first.outcome is PipelineOutcome.APPROVAL
    approval = await session.get(Approval, first.approval_id)
    assert approval is not None and ApprovalReason.FIRST_MESSAGE in approval.reasons
    a, m = await world.controller.approve(session, world.scope, approval.id, edited_text=None)
    await world.dispatcher.dispatch(session, world.scope, m.id)

    result = await inbound(session, world, "Just got back from Lisbon!")

    assert result.outcome is PipelineOutcome.DISPATCHED, result
    sent = await session.get(Message, result.outbound_message_id)
    assert sent is not None
    assert sent.origin is MessageOrigin.AUTOPILOT
    # Acknowledged by the simulated transport, then a receipt: delivered, never early.
    assert sent.delivery_state in (DeliveryState.SENT, DeliveryState.DELIVERED)
    assert sent.sent_at is not None and sent.external_message_id is not None
    assert world.connector is not None
    assert world.connector.send_count(f"send:{sent.id}:1") == 1
    assert [r.text for r in world.connector.transport_log] == ["Hey!", "Welcome back! How was it?"]
    types = await audit_types(session, world)
    for expected in (
        "message_received",
        "analysis_updated",
        "draft_generated",
        "send_queued",
        "send_attempted",
        "send_acknowledged",
    ):
        assert expected in types
    # Audit details never contain message or draft text.
    details = await session.scalars(
        select(AgentAction.details).where(AgentAction.user_id == world.scope.user_id)
    )
    blob = str(list(details.all()))
    assert "Lisbon" not in blob and "Welcome back" not in blob


async def test_assisted_mode_never_sends_before_approval_then_exactly_one(
    session: AsyncSession,
) -> None:
    world = await make_world(session, mode="assisted")
    result = await inbound(session, world)
    assert result.outcome is PipelineOutcome.APPROVAL
    assert await outbound(session, world) == []
    assert world.connector is not None and world.connector.transport_log == []
    approval = await session.get(Approval, result.approval_id)
    assert approval is not None
    assert ApprovalReason.ASSISTED_MODE in approval.reasons

    approved, message = await world.controller.approve(
        session, world.scope, approval.id, edited_text="Edited by me"
    )
    assert message.delivery_state is DeliveryState.QUEUED  # the response never says sent
    await world.dispatcher.dispatch(session, world.scope, message.id)
    await world.dispatcher.dispatch(session, world.scope, message.id)  # repeat: no-op

    assert [r.text for r in world.connector.transport_log] == ["Edited by me"]
    msgs = await outbound(session, world)
    assert len(msgs) == 1 and msgs[0].origin is MessageOrigin.APPROVED_DRAFT
    assert msgs[0].approval_id == approval.id and msgs[0].text == "Edited by me"
    assert approved.final_text == "Edited by me"
    edited = await session.scalar(
        select(AgentAction.details).where(
            AgentAction.approval_id == approval.id,
            AgentAction.type == AgentActionType.APPROVAL_APPROVED,
        )
    )
    assert edited is not None and edited["edited"] is True


async def test_reject_sends_nothing(session: AsyncSession) -> None:
    world = await make_world(session, mode="assisted")
    result = await inbound(session, world)
    assert result.approval_id is not None
    rejected = await world.controller.reject(session, world.scope, result.approval_id, reason="no")
    assert rejected.status is ApprovalStatus.REJECTED
    assert await outbound(session, world) == []
    assert world.connector is not None and world.connector.transport_log == []


async def test_takeover_disables_future_automated_sends_immediately(
    session: AsyncSession,
) -> None:
    world = await make_world(session, mode="assisted")
    pending = await inbound(session, world)
    assert pending.approval_id is not None
    conversation_contact = (await session.get(Approval, pending.approval_id)).contact_id  # type: ignore[union-attr]

    contact, canceled_approvals, _ = await world.controller.set_contact_status(
        session, world.scope, conversation_contact, ContactAutomationStatus.TAKEN_OVER
    )
    assert contact.automation_status is ContactAutomationStatus.TAKEN_OVER
    assert canceled_approvals == 1
    approval = await session.get(Approval, pending.approval_id)
    assert approval is not None and approval.status is ApprovalStatus.CANCELED

    # The in-flight approval can no longer be approved.
    from app.services.automation import ApprovalNotPending

    try:
        await world.controller.approve(session, world.scope, pending.approval_id, edited_text=None)
        raise AssertionError("approve after takeover must fail")
    except ApprovalNotPending:
        pass

    # New inbound messages: no draft, no approval, no send, even in autopilot.
    from tests.automation.conftest import set_mode

    await set_mode(session, world.scope, "autopilot")
    later = await inbound(session, world, "are you there?")
    assert later.outcome is PipelineOutcome.TAKEN_OVER
    assert await outbound(session, world) == []
    assert world.connector is not None and world.connector.transport_log == []
    drafts = await session.scalar(
        select(func.count()).select_from(Draft).where(Draft.user_id == world.scope.user_id)
    )
    assert drafts == 1  # only the one from before the takeover


async def test_unsupported_send_capability_fails_cleanly(session: AsyncSession) -> None:
    world = await make_world(session, mode="autopilot", send="UNSUPPORTED")
    result = await inbound(session, world)
    assert result.outcome is PipelineOutcome.BLOCKED
    assert result.decision is not None and result.decision.reason_code == "capability_unsupported"
    assert await outbound(session, world) == []
    blocked = await session.scalar(
        select(AgentAction).where(
            AgentAction.user_id == world.scope.user_id,
            AgentAction.type == AgentActionType.POLICY_BLOCKED,
        )
    )
    assert blocked is not None and blocked.reason_code == "capability_unsupported"
    approvals = await session.scalar(
        select(func.count()).select_from(Approval).where(Approval.user_id == world.scope.user_id)
    )
    assert approvals == 0


async def test_unverified_send_in_autopilot_goes_to_approval(session: AsyncSession) -> None:
    world = await make_world(session, mode="autopilot", send="UNVERIFIED")
    result = await inbound(session, world)
    assert result.outcome is PipelineOutcome.APPROVAL
    approval = await session.get(Approval, result.approval_id)
    assert approval is not None
    assert ApprovalReason.CAPABILITY_UNVERIFIED in approval.reasons
    assert await outbound(session, world) == []


async def test_no_send_and_ask_user_drafts_never_send(session: AsyncSession) -> None:
    world = await make_world(session, mode="autopilot")
    world.llm.replies.extend([reply(decision="no_send"), reply(decision="ask_user")])
    first = await inbound(session, world, "hmm")
    second = await inbound(session, world, "so?")
    assert first.outcome is PipelineOutcome.NO_SEND
    assert second.outcome is PipelineOutcome.NO_SEND
    assert await outbound(session, world) == []
    assert (
        await session.scalar(
            select(func.count())
            .select_from(Approval)
            .where(Approval.user_id == world.scope.user_id)
        )
        == 0
    )


async def test_model_failure_degrades_to_no_send(session: AsyncSession) -> None:
    from app.ai.providers import LLMServerError

    world = await make_world(session, mode="autopilot")
    world.llm.default_reply = LLMServerError("boom", status_code=503)
    result = await inbound(session, world)
    assert result.outcome is PipelineOutcome.NO_SEND
    assert await outbound(session, world) == []


async def test_manual_mode_stores_draft_but_never_sends(session: AsyncSession) -> None:
    world = await make_world(session, mode="manual")
    result = await inbound(session, world)
    assert result.outcome is PipelineOutcome.MANUAL
    assert result.draft_id is not None
    assert await outbound(session, world) == []


async def test_duplicate_event_does_not_duplicate_anything(session: AsyncSession) -> None:
    world = await make_world(session, mode="assisted")
    raw = world.event("hello", external_message_id="dup-1")
    outcome, message_id = await ingest(session, world, raw)
    assert outcome is IngestOutcome.ACCEPTED and message_id is not None
    await world.controller.handle_inbound_message(session, world.scope, message_id)
    # Same envelope again, then a re-delivery under a new event_id.
    again, _ = await ingest(session, world, raw)
    redelivered, _ = await ingest(session, world, world.event("hello", external_message_id="dup-1"))
    assert again is IngestOutcome.DUPLICATE_EVENT
    assert redelivered is IngestOutcome.DUPLICATE_MESSAGE
    # Running the pipeline again for the same message changes nothing.
    rerun = await world.controller.handle_inbound_message(session, world.scope, message_id)
    assert rerun.outcome is PipelineOutcome.DUPLICATE
    assert (
        await session.scalar(
            select(func.count())
            .select_from(Approval)
            .where(Approval.user_id == world.scope.user_id)
        )
        == 1
    )
    received = await session.scalar(
        select(func.count())
        .select_from(AgentAction)
        .where(
            AgentAction.user_id == world.scope.user_id,
            AgentAction.type == AgentActionType.MESSAGE_RECEIVED,
        )
    )
    assert received == 1
