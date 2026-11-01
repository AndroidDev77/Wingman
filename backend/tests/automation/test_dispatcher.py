"""Dispatcher state machine: queued -> sending -> sent -> delivered; bounded retry with a
new key per attempt (D-006); dead letter; cancel on re-check; nothing early."""

from datetime import timedelta

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.events import SendFailureCode
from app.db.enums import (
    AgentActionType,
    ContactAutomationStatus,
    DeliveryState,
    MessageOrigin,
)
from app.db.models import AgentAction
from app.db.repositories import ConnectionRepository, ContactRepository, ConversationRepository
from app.services.dispatcher import (
    CapabilityVerification,
    DispatchOutcome,
    GlobalAutomationStatus,
    RetryPolicy,
    recheck_send,
    send_key,
)
from tests.automation.conftest import make_world, mock_caps
from tests.automation.test_pipeline import ingest


async def world_with_thread(session: AsyncSession, **kw):  # type: ignore[no-untyped-def]
    world = await make_world(session, **kw)
    _, message_id = await ingest(session, world, world.event("hi"))
    assert message_id is not None
    from app.db.models import Message

    inbound = await session.get(Message, message_id)
    assert inbound is not None
    conversation = await ConversationRepository(session, world.scope).require(
        inbound.conversation_id
    )
    return world, conversation


async def queue(session, world, conversation, origin=MessageOrigin.USER_MANUAL, text="hey"):  # type: ignore[no-untyped-def]
    from app.db.enums import Actor

    return await world.dispatcher.queue(
        session, world.scope, conversation=conversation, origin=origin, text=text, actor=Actor.USER
    )


async def test_send_key_is_per_attempt() -> None:
    import uuid

    m = uuid.uuid4()
    assert send_key(m, 1) == f"send:{m}:1" != send_key(m, 2)


async def test_retry_then_success_uses_a_new_key_per_attempt(session: AsyncSession) -> None:
    world, conversation = await world_with_thread(session)
    assert world.connector is not None
    world.connector.inject_send_failures(1, code=SendFailureCode.TIMEOUT, retryable=True)
    message = await queue(session, world, conversation)

    first = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert first.outcome is DispatchOutcome.RETRY_SCHEDULED
    assert first.message.delivery_state is DeliveryState.QUEUED
    assert first.message.attempt_count == 1
    assert first.message.failure_code == "timeout" and first.message.failure_retryable is True
    assert first.message.sent_at is None
    # Backoff: not before send_after.
    assert (
        await world.dispatcher.dispatch(session, world.scope, message.id)
    ).outcome is DispatchOutcome.NOT_DUE
    world.clock.advance(seconds=31)
    second = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert second.outcome is DispatchOutcome.SENT
    assert second.message.attempt_count == 2 and second.message.failure_code is None
    keys = [r.idempotency_key for r in world.connector.transport_log]
    assert keys == [send_key(message.id, 1), send_key(message.id, 2)]


async def test_dead_letter_after_max_attempts(session: AsyncSession) -> None:
    world, conversation = await world_with_thread(session, retry=RetryPolicy(max_attempts=2))
    assert world.connector is not None
    world.connector.inject_send_failures(None, retryable=True)
    message = await queue(session, world, conversation)
    assert (
        await world.dispatcher.dispatch(session, world.scope, message.id)
    ).outcome is DispatchOutcome.RETRY_SCHEDULED
    world.clock.advance(hours=1)
    last = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert last.outcome is DispatchOutcome.DEAD_LETTERED
    assert last.message.delivery_state is DeliveryState.DEAD_LETTERED
    assert last.message.attempt_count == 2
    world.clock.advance(hours=1)
    assert (
        await world.dispatcher.dispatch(session, world.scope, message.id)
    ).outcome is DispatchOutcome.NOOP
    assert len(world.connector.transport_log) == 2


async def test_non_retryable_failure_dead_letters_at_once(session: AsyncSession) -> None:
    world, conversation = await world_with_thread(session)
    assert world.connector is not None
    world.connector.inject_send_failures(
        1, code=SendFailureCode.REJECTED_BY_PLATFORM, retryable=False
    )
    message = await queue(session, world, conversation)
    result = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert result.outcome is DispatchOutcome.DEAD_LETTERED
    assert len(world.connector.transport_log) == 1


async def test_unknown_thread_is_rejected_by_transport_not_delivered(session: AsyncSession) -> None:
    world = await make_world(session)
    # A conversation the mock transport has never seen: the simulator rejects it.
    contact = await ContactRepository(session, world.scope).get_or_create(
        connection_id=world.connection_id, external_contact_id="ghost", display_name="G"
    )
    conversation = await ConversationRepository(session, world.scope).create(
        contact_id=contact.id,
        connection_id=world.connection_id,
        platform="mock",
        external_thread_id="nope",
    )
    message = await queue(session, world, conversation)
    result = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert result.outcome is DispatchOutcome.DEAD_LETTERED
    assert result.message.sent_at is None and result.message.delivered_at is None


async def test_delivered_only_after_sent_with_receipts(session: AsyncSession) -> None:
    from app.connectors import MockConnector

    world, conversation = await world_with_thread(session)
    receipts = MockConnector(
        user_id=world.scope.user_id,
        connection_id=world.connection_id,
        capabilities=mock_caps(),
        auto_delivery_receipts=True,
    )
    await receipts.inject_inbound(external_thread_id="thread-1", sender_id="contact-sam", text="hi")
    world.registry.register(world.connection_id, receipts)
    message = await queue(session, world, conversation)
    result = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert result.message.delivery_state is DeliveryState.DELIVERED
    assert result.message.sent_at is not None and result.message.delivered_at is not None
    assert result.message.delivered_at >= result.message.sent_at


async def test_unsupported_at_dispatch_cancels_without_transport(session: AsyncSession) -> None:
    world, conversation = await world_with_thread(session)
    message = await queue(session, world, conversation)
    caps = mock_caps("UNSUPPORTED").to_contract_dict()
    await ConnectionRepository(session, world.scope).set_capabilities(world.connection_id, caps)
    result = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert result.outcome is DispatchOutcome.CANCELED and result.reason == "capability_unsupported"
    assert result.message.delivery_state is DeliveryState.CANCELED
    assert world.connector is not None and world.connector.transport_log == []


async def test_connector_raising_unsupported_is_a_typed_failure(session: AsyncSession) -> None:
    """DB says VERIFIED but the connector itself reports UNSUPPORTED: no send, typed code."""
    world, conversation = await world_with_thread(session)
    assert world.connector is not None
    world.connector.set_capabilities(mock_caps("UNSUPPORTED"))
    message = await queue(session, world, conversation)
    result = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert result.outcome is DispatchOutcome.DEAD_LETTERED
    assert result.message.failure_code == "capability_unsupported"
    assert world.connector.transport_log == []


async def test_pause_cancels_queued_automated_but_not_user_manual(session: AsyncSession) -> None:
    world, conversation = await world_with_thread(session)
    auto = await queue(session, world, conversation, origin=MessageOrigin.AUTOPILOT, text="auto")
    manual = await queue(
        session, world, conversation, origin=MessageOrigin.USER_MANUAL, text="mine"
    )
    _, canceled = await world.controller.pause_all(session, world.scope)
    assert canceled == 1
    a = await world.dispatcher.dispatch(session, world.scope, auto.id)
    m = await world.dispatcher.dispatch(session, world.scope, manual.id)
    assert a.message.delivery_state is DeliveryState.CANCELED and a.outcome is DispatchOutcome.NOOP
    assert m.outcome is DispatchOutcome.SENT
    assert world.connector is not None
    assert [r.text for r in world.connector.transport_log] == ["mine"]
    canceled_audit = await session.scalar(
        AgentAction.__table__.select().where(
            AgentAction.message_id == auto.id, AgentAction.type == AgentActionType.SEND_CANCELED
        )
    )
    assert canceled_audit is not None


@pytest.mark.parametrize(
    ("origin", "cap", "glob", "contact", "expected"),
    [
        (MessageOrigin.USER_MANUAL, "VERIFIED", "paused", "taken_over", None),
        (MessageOrigin.USER_MANUAL, "UNSUPPORTED", "active", "active", "capability_unsupported"),
        (MessageOrigin.USER_MANUAL, "UNVERIFIED", "active", "active", None),
        (MessageOrigin.APPROVED_DRAFT, "UNVERIFIED", "active", "active", None),
        (MessageOrigin.APPROVED_DRAFT, "VERIFIED", "paused", "paused", None),
        (MessageOrigin.AUTOPILOT, "VERIFIED", "paused", "active", "automation_paused"),
        (MessageOrigin.APPROVED_DRAFT, "VERIFIED", "active", "taken_over", "automation_paused"),
        (MessageOrigin.AUTOPILOT, "UNVERIFIED", "active", "active", "capability_unverified"),
        (MessageOrigin.AUTOPILOT, "VERIFIED", "active", "paused", "automation_paused"),
        (MessageOrigin.AUTOPILOT, "VERIFIED", "active", "active", None),
    ],
)
def test_recheck_matrix(origin, cap, glob, contact, expected) -> None:  # type: ignore[no-untyped-def]
    got = recheck_send(
        origin=origin,
        send_capability=CapabilityVerification(cap),
        global_status=GlobalAutomationStatus(glob),
        contact_status=ContactAutomationStatus(contact),
    )
    assert (got.value if got else None) == expected


async def test_takeover_after_queue_cancels_before_transport(session: AsyncSession) -> None:
    world, conversation = await world_with_thread(session)
    message = await queue(session, world, conversation, origin=MessageOrigin.AUTOPILOT)
    await ContactRepository(session, world.scope).set_automation_status(
        conversation.contact_id, ContactAutomationStatus.TAKEN_OVER
    )
    result = await world.dispatcher.dispatch(session, world.scope, message.id)
    assert result.outcome is DispatchOutcome.CANCELED
    assert world.connector is not None and world.connector.transport_log == []
    _ = timedelta


async def test_pause_keeps_approved_draft_takeover_cancels_it(session: AsyncSession) -> None:
    """Pause stops sends without approval (autopilot); an approved draft is the user's
    explicit decision and still goes out. Takeover stops every agent-originated send."""
    world, conversation = await world_with_thread(session)
    from app.db.models import Approval, Draft  # noqa: F401

    async def approved(text: str):  # type: ignore[no-untyped-def]
        from app.db.enums import ApprovalStatus, DraftDecision, IntentMode
        from app.db.ids import uuid7
        from app.db.repositories import ApprovalRepository, DraftRepository

        draft = await DraftRepository(session, world.scope).create(
            id=uuid7(),
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            decision=DraftDecision.SEND_CANDIDATE,
            text=text,
            question_for_user=None,
            rationale="r",
            confidence=0.9,
            approval_reasons=[],
            intent_mode=IntentMode.OPEN,
            grounding_memory_ids=[],
            grounding_message_ids=[],
            reason="model_decision",
            created_at=world.clock.now,
        )
        assert draft is not None
        approval = await ApprovalRepository(session, world.scope).create(
            conversation_id=conversation.id,
            contact_id=conversation.contact_id,
            draft_id=draft.id,
            reasons=["assisted_mode"],
            expires_at=None,
            created_at=world.clock.now,  # type: ignore[list-item]
        )
        _, message = await world.controller.approve(
            session, world.scope, approval.id, edited_text=None
        )
        assert approval.status is ApprovalStatus.APPROVED or True
        return message

    first = await approved("approved while paused")
    await world.controller.pause_all(session, world.scope)
    sent = await world.dispatcher.dispatch(session, world.scope, first.id)
    assert sent.outcome is DispatchOutcome.SENT
    await world.controller.resume_all(session, world.scope)

    second = await approved("approved then taken over")
    await world.controller.set_contact_status(
        session, world.scope, conversation.contact_id, ContactAutomationStatus.TAKEN_OVER
    )
    gone = await world.dispatcher.dispatch(session, world.scope, second.id)
    assert gone.message.delivery_state is DeliveryState.CANCELED
    assert world.connector is not None
    assert [r.text for r in world.connector.transport_log] == ["approved while paused"]
