"""Contract 1.2.0 (I-017) in WING-011: the ``paused`` approval reason replaces D-015's
``user_rule`` interim, and an expired device lease is audited as ``lease_expired``
(outcome unknown; the message stays ``sending``, never retried)."""

import dataclasses
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    AgentActionType,
    ApprovalReason,
    ContactAutomationStatus,
    DeliveryState,
    GlobalAutomationStatus,
)
from app.db.models import AgentAction, Approval, Conversation, Message
from app.services.automation import PipelineOutcome, PolicyOutcome, evaluate_policy
from tests.automation.conftest import assert_contract, headers, make_world, reply
from tests.automation.test_device_routes import claim, queued_device_send, sms_world
from tests.automation.test_pipeline import inbound, prime_outbound
from tests.automation.test_policy import BASE


def test_policy_reports_paused_not_user_rule() -> None:
    for kw in (
        {"global_status": GlobalAutomationStatus.PAUSED},
        {"contact_status": ContactAutomationStatus.PAUSED},
    ):
        d = evaluate_policy(dataclasses.replace(BASE, **kw))  # type: ignore[arg-type]
        assert d.outcome is PolicyOutcome.APPROVAL
        assert d.reasons == (ApprovalReason.PAUSED,)
        assert d.checks["paused"] is True
        assert d.reason_code == "paused"


def test_paused_is_added_alongside_other_reasons() -> None:
    d = evaluate_policy(
        dataclasses.replace(
            BASE,
            global_status=GlobalAutomationStatus.PAUSED,
            content_reasons=(ApprovalReason.LOW_CONFIDENCE,),
        )
    )
    assert d.reasons == (ApprovalReason.LOW_CONFIDENCE, ApprovalReason.PAUSED)
    assert ApprovalReason.USER_RULE not in d.reasons


def test_not_paused_never_reports_paused() -> None:
    d = evaluate_policy(dataclasses.replace(BASE, mode=BASE.mode))
    assert d.outcome is PolicyOutcome.DISPATCH and not d.reasons


async def test_approval_created_during_pause_has_paused_reason(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    await prime_outbound(session, world)
    # Approve the first-message draft so the next draft would otherwise go on autopilot.
    first = await session.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
    assert first is not None
    _, m = await world.controller.approve(session, world.scope, first.id, edited_text=None)
    await world.dispatcher.dispatch(session, world.scope, m.id)

    client = make_client(world)
    resp = await client.post("/v1/automation/pause", json={}, headers=headers(world.scope))
    assert resp.status_code == 200, resp.text

    world.llm.replies.append(reply("Welcome back!"))
    result = await inbound(session, world, "Just got back!")
    assert result.outcome is PipelineOutcome.APPROVAL
    approval = await session.get(Approval, result.approval_id)
    assert approval is not None and approval.reasons == [ApprovalReason.PAUSED]

    # Over HTTP: the approval and its audit row validate against contract 1.2.0.
    page = await client.get("/v1/approvals", headers=headers(world.scope))
    assert page.status_code == 200
    assert_contract("ApprovalPage", page.json())
    assert page.json()["items"][0]["reasons"] == ["paused"]
    audit = await client.get(
        "/v1/agent-actions",
        params={"type": "approval_requested"},
        headers=headers(world.scope),
    )
    assert_contract("AgentActionPage", audit.json())
    newest = audit.json()["items"][0]
    assert newest["reason_code"] == "paused"
    assert newest["details"]["reasons"] == ["paused"]
    assert newest["details"]["checks"]["paused"] is True


async def test_contact_pause_also_reports_paused(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(session, mode="autopilot")
    await prime_outbound(session, world)
    first = await session.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
    assert first is not None
    _, m = await world.controller.approve(session, world.scope, first.id, edited_text=None)
    await world.dispatcher.dispatch(session, world.scope, m.id)
    conv = await session.scalar(
        select(Conversation).where(Conversation.user_id == world.scope.user_id)
    )
    assert conv is not None
    client = make_client(world)
    resp = await client.post(
        f"/v1/contacts/{conv.contact_id}/automation/pause", json={}, headers=headers(world.scope)
    )
    assert resp.status_code == 200, resp.text
    result = await inbound(session, world, "Still there?")
    approval = await session.get(Approval, result.approval_id)
    assert approval is not None and approval.reasons == [ApprovalReason.PAUSED]


async def test_lease_expiry_is_audited_as_lease_expired(session: AsyncSession, make_client) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    [ins] = (await claim(client, world)).json()["instructions"]
    world.clock.advance(seconds=121)
    expired = await world.services.outbox.expire(session, world.scope)
    assert [e.id for e in expired] == [uuid.UUID(ins["id"])]

    rows = (
        await session.scalars(
            select(AgentAction).where(
                AgentAction.user_id == world.scope.user_id,
                AgentAction.type == AgentActionType.LEASE_EXPIRED,
            )
        )
    ).all()
    assert len(rows) == 1
    row = rows[0]
    assert row.message_id == queued.id and row.actor.value == "system"
    assert row.reason_code == "outcome_unknown"
    # IDs and codes only.
    assert set(row.details) == {"instruction_id", "connection_id", "attempt", "outcome", "retried"}
    assert row.details["instruction_id"] == ins["id"] and row.details["retried"] is False
    # Never claimed as a failure, never sent, never retried.
    types = {
        a.type
        for a in (
            await session.scalars(select(AgentAction).where(AgentAction.message_id == queued.id))
        ).all()
    }
    assert AgentActionType.SEND_FAILED not in types
    msg = await session.get(Message, queued.id)
    assert msg is not None and msg.delivery_state is DeliveryState.SENDING
    # A second expiry run records nothing new.
    assert await world.services.outbox.expire(session, world.scope) == []

    audit = await client.get(
        "/v1/agent-actions", params={"type": "lease_expired"}, headers=headers(world.scope)
    )
    assert audit.status_code == 200
    assert_contract("AgentActionPage", audit.json())
    assert [a["id"] for a in audit.json()["items"]] == [str(row.id)]
