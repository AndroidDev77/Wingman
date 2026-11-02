"""Contract 1.3.0 (ADR 0008), integrator check: an ``autopilot`` send on an experimental
connection without an operator-recorded live test is canceled by the last-moment
re-checks, both in the dispatcher (before any transport or outbox instruction) and at
device claim time, even when the capability document says ``send: VERIFIED``.
The user's own sends on the same connection still go."""

import httpx
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import (
    Actor,
    DeliveryState,
    MessageOrigin,
    OutboxInstructionState,
)
from app.db.models import Message, OutboxInstruction, PlatformConnection
from app.db.repositories import ConversationRepository
from app.services.connections import ConnectionService
from app.services.dispatcher import DispatchOutcome
from tests.automation.conftest import World, assert_contract, headers
from tests.automation.test_pipeline import ingest
from tests.integrations.helpers import EXPERIMENTAL_INSTALL, experimental_world


async def _thread(session: AsyncSession, world: World):  # type: ignore[no-untyped-def]
    _, message_id = await ingest(session, world, world.event("hi"))
    assert message_id is not None
    inbound = await session.get(Message, message_id)
    assert inbound is not None
    return await ConversationRepository(session, world.scope).require(inbound.conversation_id)


async def _queue(session: AsyncSession, world: World, conversation, origin: MessageOrigin):  # type: ignore[no-untyped-def]
    return await world.dispatcher.queue(
        session,
        world.scope,
        conversation=conversation,
        origin=origin,
        text="hey",
        actor=Actor.USER,
    )


async def _claim(client: httpx.AsyncClient, world: World) -> list[dict[str, object]]:
    resp = await client.post(
        f"/v1/connections/{world.connection_id}/outbox/claim",
        json={"device_install_id": str(EXPERIMENTAL_INSTALL), "max_instructions": 5},
        headers=headers(world.scope),
    )
    assert resp.status_code == 200, resp.text
    assert_contract("OutboxClaimResult", resp.json())
    instructions: list[dict[str, object]] = resp.json()["instructions"]
    return instructions


async def test_dispatcher_cancels_autopilot_on_unverified_experimental(
    session: AsyncSession,
) -> None:
    world = await experimental_world(session, send="VERIFIED")
    conversation = await _thread(session, world)
    autopilot = await _queue(session, world, conversation, MessageOrigin.AUTOPILOT)
    result = await world.dispatcher.dispatch(session, world.scope, autopilot.id)
    assert result.outcome is DispatchOutcome.CANCELED
    assert result.reason == "capability_unverified"
    assert result.message.delivery_state is DeliveryState.CANCELED
    no_instruction = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == autopilot.id)
    )
    assert no_instruction is None

    # The user's own send on the same connection still goes to the device outbox.
    manual = await _queue(session, world, conversation, MessageOrigin.USER_MANUAL)
    result = await world.dispatcher.dispatch(session, world.scope, manual.id)
    assert result.outcome is not DispatchOutcome.CANCELED


async def test_claim_cancels_autopilot_when_experimental_is_not_verified(
    session: AsyncSession, make_v13_client
) -> None:  # type: ignore[no-untyped-def]
    world = await experimental_world(session, send="VERIFIED")
    await ConnectionService(env="test", now=world.clock).record_experimental_verification(
        session,
        world.scope,
        world.connection_id,
        evidence="integrator test run",
        verified_at=world.clock.now,
    )
    conversation = await _thread(session, world)
    autopilot = await _queue(session, world, conversation, MessageOrigin.AUTOPILOT)
    result = await world.dispatcher.dispatch(session, world.scope, autopilot.id)
    assert result.outcome is not DispatchOutcome.CANCELED  # verified: instruction placed
    instruction = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == autopilot.id)
    )
    assert instruction is not None and instruction.state is OutboxInstructionState.PENDING

    # The verification is gone by the time the device claims (the invariant the claim
    # re-check guards): the autopilot instruction is canceled, never leased.
    await session.execute(
        update(PlatformConnection)
        .where(PlatformConnection.id == world.connection_id)
        .values(experimental_verified_at=None)
    )
    session.expire_all()
    client = make_v13_client(world)
    assert await _claim(client, world) == []
    msg = await session.get(Message, autopilot.id)
    assert msg is not None and msg.delivery_state is DeliveryState.CANCELED
    ins = await session.get(OutboxInstruction, instruction.id)
    assert ins is not None and ins.state is OutboxInstructionState.CANCELED
