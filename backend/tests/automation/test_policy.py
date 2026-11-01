"""Policy engine: every check, in order, from settings, state and capabilities only."""

import dataclasses
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    ApprovalReason,
    AutomationMode,
    CapabilityVerification,
    ContactAutomationStatus,
    DeliveryState,
    DraftDecision,
    GlobalAutomationStatus,
    MessageDirection,
)
from app.db.models import Approval, Message
from app.services.automation import (
    PipelineOutcome,
    PolicyInput,
    PolicyOutcome,
    StopReason,
    evaluate_policy,
    within_active_hours,
)
from app.services.preferences import ActiveHours
from tests.automation.conftest import NOW, make_world, reply, set_mode
from tests.automation.test_pipeline import inbound, outbound

BASE = PolicyInput(
    draft_decision=DraftDecision.SEND_CANDIDATE,
    content_reasons=(),
    mode=AutomationMode.AUTOPILOT,
    global_status=GlobalAutomationStatus.ACTIVE,
    contact_status=ContactAutomationStatus.ACTIVE,
    send_capability=CapabilityVerification.VERIFIED,
    now=NOW,
    active_hours=None,
    daily_limit=50,
    sends_today=0,
    approval_required_for=(),
)


def decide(**changes: object):  # type: ignore[no-untyped-def]
    return evaluate_policy(dataclasses.replace(BASE, **changes))  # type: ignore[arg-type]


def test_autopilot_all_clear_dispatches() -> None:
    assert decide().outcome is PolicyOutcome.DISPATCH


@pytest.mark.parametrize("decision", [DraftDecision.NO_SEND, DraftDecision.ASK_USER])
def test_non_send_candidate_is_a_stop_in_every_mode(decision: DraftDecision) -> None:
    for mode in AutomationMode:
        d = decide(draft_decision=decision, mode=mode)
        assert d.outcome is PolicyOutcome.STOP and d.stop is StopReason.DRAFT_NOT_SENDABLE
        assert d.reasons == ()


def test_stop_order() -> None:
    # Takeover beats everything after the draft decision; unsupported beats mode.
    d = decide(
        contact_status=ContactAutomationStatus.TAKEN_OVER,
        send_capability=CapabilityVerification.UNSUPPORTED,
    )
    assert d.stop is StopReason.TAKEN_OVER
    d = decide(send_capability=CapabilityVerification.UNSUPPORTED, mode=AutomationMode.MANUAL)
    assert d.stop is StopReason.CAPABILITY_UNSUPPORTED
    assert decide(mode=AutomationMode.MANUAL).stop is StopReason.MANUAL_MODE
    assert decide(rule_actions=("block_send",)).stop is StopReason.USER_RULE


def test_assisted_always_needs_approval() -> None:
    d = decide(mode=AutomationMode.ASSISTED)
    assert d.outcome is PolicyOutcome.APPROVAL and d.reasons == (ApprovalReason.ASSISTED_MODE,)


def test_unverified_send_needs_approval_even_in_autopilot() -> None:
    d = decide(send_capability=CapabilityVerification.UNVERIFIED)
    assert d.outcome is PolicyOutcome.APPROVAL
    assert ApprovalReason.CAPABILITY_UNVERIFIED in d.reasons


@pytest.mark.parametrize(
    "reason",
    [
        ApprovalReason.DATE_OR_COMMITMENT,
        ApprovalReason.SENSITIVE_CONTENT,
        ApprovalReason.LOW_CONFIDENCE,
        ApprovalReason.FIRST_MESSAGE,
    ],
)
def test_content_reasons_need_approval_in_autopilot(reason: ApprovalReason) -> None:
    d = decide(content_reasons=(reason,))
    assert d.outcome is PolicyOutcome.APPROVAL and reason in d.reasons


def test_policy_reasons_from_the_draft_are_ignored() -> None:
    """Only content reasons count from the draft; it cannot inject or suppress policy."""
    d = decide(content_reasons=(ApprovalReason.ASSISTED_MODE,))
    assert d.outcome is PolicyOutcome.DISPATCH


def test_daily_limit() -> None:
    assert decide(sends_today=49, daily_limit=50).outcome is PolicyOutcome.DISPATCH
    d = decide(sends_today=50, daily_limit=50)
    assert d.outcome is PolicyOutcome.APPROVAL and ApprovalReason.SEND_LIMIT_REACHED in d.reasons
    assert decide(sends_today=0, daily_limit=0).outcome is PolicyOutcome.APPROVAL


def test_approval_required_for_marks_user_rule() -> None:
    d = decide(
        content_reasons=(ApprovalReason.LOW_CONFIDENCE,),
        approval_required_for=(ApprovalReason.LOW_CONFIDENCE,),
    )
    assert d.reasons == (ApprovalReason.LOW_CONFIDENCE, ApprovalReason.USER_RULE)
    d = decide(rule_actions=("require_approval",))
    assert d.outcome is PolicyOutcome.APPROVAL and ApprovalReason.USER_RULE in d.reasons


def test_pause_routes_to_approval_never_dispatch() -> None:
    for kw in (
        {"global_status": GlobalAutomationStatus.PAUSED},
        {"contact_status": ContactAutomationStatus.PAUSED},
    ):
        d = decide(**kw)
        assert d.outcome is PolicyOutcome.APPROVAL and d.checks["paused"] is True


# --------------------------------------------------------------------------- active hours


def hours(tz: str, *windows: tuple[list[str], str, str]) -> ActiveHours:
    return ActiveHours.model_validate(
        {"timezone": tz, "windows": [{"days": d, "start": s, "end": e} for d, s, e in windows]}
    )


def test_active_hours_windows() -> None:
    monday_15_utc = NOW  # 2026-11-02 is a Monday
    h = hours("UTC", (["mon"], "09:00", "17:00"))
    assert within_active_hours(h, monday_15_utc)
    assert not within_active_hours(h, monday_15_utc.replace(hour=17))  # end exclusive
    assert within_active_hours(h, monday_15_utc.replace(hour=9))  # start inclusive
    assert not within_active_hours(h, monday_15_utc + timedelta(days=1))  # tuesday
    # Time zone: 15:00 UTC is 09:00 in Chicago (CST, after DST ended 2026-11-01).
    chicago = hours("America/Chicago", (["mon"], "09:00", "10:00"))
    assert within_active_hours(chicago, monday_15_utc)
    # Wrap past midnight belongs to the start day.
    night = hours("UTC", (["mon"], "22:00", "02:00"))
    assert within_active_hours(night, datetime(2026, 11, 2, 23, 0, tzinfo=UTC))
    assert within_active_hours(night, datetime(2026, 11, 3, 1, 0, tzinfo=UTC))  # tue 01:00
    assert not within_active_hours(night, datetime(2026, 11, 3, 23, 0, tzinfo=UTC))
    assert within_active_hours(None, monday_15_utc)


def test_outside_active_hours_needs_approval() -> None:
    d = decide(active_hours=hours("UTC", (["tue"], "09:00", "17:00")))
    assert d.outcome is PolicyOutcome.APPROVAL
    assert ApprovalReason.OUTSIDE_ACTIVE_HOURS in d.reasons


# --------------------------------------------------------------------------- integrated


async def _warm(session: AsyncSession, world) -> None:  # type: ignore[no-untyped-def]
    """Get past first_message: approve the first reply."""
    first = await inbound(session, world, "hi")
    approval, message = await world.controller.approve(
        session, world.scope, first.approval_id, edited_text=None
    )
    await world.dispatcher.dispatch(session, world.scope, message.id)


async def test_date_or_commitment_needs_approval_in_autopilot(session: AsyncSession) -> None:
    world = await make_world(session, mode="autopilot")
    await _warm(session, world)
    world.llm.replies.append(reply("Want to grab coffee Saturday at 3pm?", commitment="proposal"))
    result = await inbound(session, world, "we should hang out")
    assert result.outcome is PipelineOutcome.APPROVAL
    approval = await session.get(Approval, result.approval_id)
    assert approval is not None and ApprovalReason.DATE_OR_COMMITMENT in approval.reasons
    assert len(await outbound(session, world)) == 1  # only the warm-up reply


async def test_daily_limit_integrated(session: AsyncSession) -> None:
    world = await make_world(session)
    await set_mode(session, world.scope, "autopilot", daily_send_limit=1)
    await _warm(session, world)  # approved_draft: does not count toward the limit
    ok = await inbound(session, world, "one")
    assert ok.outcome is PipelineOutcome.DISPATCHED
    limited = await inbound(session, world, "two")
    assert limited.outcome is PipelineOutcome.APPROVAL
    approval = await session.get(Approval, limited.approval_id)
    assert approval is not None and approval.reasons == [ApprovalReason.SEND_LIMIT_REACHED]


async def test_active_hours_integrated(session: AsyncSession) -> None:
    world = await make_world(session)
    await set_mode(
        session,
        world.scope,
        "autopilot",
        active_hours={
            "timezone": "UTC",
            "windows": [{"days": ["sun"], "start": "09:00", "end": "10:00"}],
        },
    )
    await _warm(session, world)
    result = await inbound(session, world, "late night?")
    assert result.outcome is PipelineOutcome.APPROVAL
    approval = await session.get(Approval, result.approval_id)
    assert approval is not None and ApprovalReason.OUTSIDE_ACTIVE_HOURS in approval.reasons


async def test_reply_delay_holds_the_send_until_due(session: AsyncSession) -> None:
    world = await make_world(session)
    await set_mode(session, world.scope, "autopilot", reply_delay={"min": 60, "max": 60})
    await _warm(session, world)
    result = await inbound(session, world, "hello again")
    assert result.outcome is PipelineOutcome.DISPATCHED
    assert result.dispatch is not None and result.dispatch.outcome.value == "not_due"
    message = await session.get(Message, result.outbound_message_id)
    assert message is not None and message.delivery_state is DeliveryState.QUEUED
    assert message.send_after == NOW + timedelta(seconds=60)
    assert world.connector is not None and len(world.connector.transport_log) == 1

    world.clock.advance(seconds=30)
    assert (
        await world.dispatcher.dispatch(session, world.scope, message.id)
    ).outcome.value == "not_due"
    world.clock.advance(seconds=31)
    results = await world.dispatcher.dispatch_due(session, world.scope)
    assert [r.outcome.value for r in results] == ["sent"]
    assert len(world.connector.transport_log) == 2


async def test_newer_inbound_cancels_a_delayed_reply_and_stale_approval(
    session: AsyncSession,
) -> None:
    world = await make_world(session)
    await set_mode(session, world.scope, "autopilot", reply_delay={"min": 600, "max": 600})
    await _warm(session, world)
    delayed = await inbound(session, world, "first")
    newer = await inbound(session, world, "second")
    old = await session.get(Message, delayed.outbound_message_id)
    assert old is not None and old.delivery_state is DeliveryState.CANCELED
    assert newer.outcome is PipelineOutcome.DISPATCHED
    queued = (
        await session.scalars(
            select(Message).where(
                Message.user_id == world.scope.user_id,
                Message.direction == MessageDirection.OUTBOUND,
                Message.delivery_state == DeliveryState.QUEUED,
            )
        )
    ).all()
    assert [m.id for m in queued] == [newer.outbound_message_id]


async def test_global_pause_then_inbound_goes_to_approval(session: AsyncSession) -> None:
    world = await make_world(session, mode="autopilot")
    await _warm(session, world)
    await world.controller.pause_all(session, world.scope)
    result = await inbound(session, world, "hey")
    assert result.outcome is PipelineOutcome.APPROVAL
    await world.controller.resume_all(session, world.scope)
    assert (await inbound(session, world, "hey again")).outcome is PipelineOutcome.DISPATCHED
