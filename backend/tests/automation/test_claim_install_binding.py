"""I-018: ``claimOutboxInstructions`` honours the ``device_install_id`` binding recorded
at pairing (ADR 0007). Only the paired install may claim; a foreign install, an unpaired
connection and a device-side connection that was never paired get ``422
validation_error`` at ``/device_install_id``, and nothing is leased, canceled or
recovered for them. Leases already held stay valid for the paired install only."""

import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import DeliveryState, OutboxInstructionState
from app.db.models import AgentAction, Message, OutboxInstruction
from app.services.connections import ConnectionService
from tests.automation.conftest import (
    DEVICE_INSTALL,
    assert_contract,
    headers,
    make_world,
    problem,
)
from tests.automation.test_device_routes import claim, post_event, queued_device_send, sms_world

FOREIGN = "00000000-0000-4000-8000-0000000000ff"


async def _state(session: AsyncSession, message_id: uuid.UUID) -> DeliveryState:
    msg = await session.get(Message, message_id)
    assert msg is not None
    await session.refresh(msg)
    return msg.delivery_state


async def test_paired_install_claims_and_recovers_its_lease(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    first = await claim(client, world, device=str(DEVICE_INSTALL), key="bind-key-0001")
    assert first.status_code == 200
    [ins] = first.json()["instructions"]
    assert ins["outbound_message_id"] == str(queued.id)
    assert await _state(session, queued.id) is DeliveryState.SENDING
    # A fresh key from the paired install recovers the same, unextended lease.
    again = await claim(client, world, device=str(DEVICE_INSTALL), key="bind-key-0002")
    assert again.json()["instructions"] == [ins]


async def test_foreign_install_is_rejected_and_takes_nothing(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    queued = await queued_device_send(session, client, world)
    before = (await session.scalars(select(AgentAction.id))).all()

    body = problem(await claim(client, world, device=FOREIGN), 422, "validation_error")
    assert body["errors"][0]["pointer"] == "/device_install_id"
    # Nothing leased, canceled or audited for the foreign install.
    ins = await session.scalar(
        select(OutboxInstruction).where(OutboxInstruction.outbound_message_id == queued.id)
    )
    assert ins is not None and ins.state is OutboxInstructionState.PENDING
    assert ins.device_install_id is None
    assert await _state(session, queued.id) is DeliveryState.QUEUED
    assert (await session.scalars(select(AgentAction.id))).all() == before

    # The paired install still gets it afterwards...
    [leased] = (await claim(client, world)).json()["instructions"]
    assert leased["id"] == str(ins.id)
    # ...and the foreign install cannot recover that lease either.
    problem(await claim(client, world, device=FOREIGN), 422, "validation_error")
    # The rejection is replayed for the same key (ADR 0004 stores completed 4xx).
    key = "bind-foreign-0001"
    problem(await claim(client, world, device=FOREIGN, key=key), 422, "validation_error")
    replay = await claim(client, world, device=FOREIGN, key=key)
    problem(replay, 422, "validation_error")
    assert replay.headers["Idempotent-Replayed"] == "true"


async def test_never_paired_device_connection_cannot_claim(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await make_world(
        session, platform="sms", device_side=True, mode="manual", device_install=None
    )
    client = make_client(world)
    assert (await post_event(client, world, world.event("hi"))).status_code == 202
    for device in (str(DEVICE_INSTALL), FOREIGN):
        body = problem(await claim(client, world, device=device), 422, "validation_error")
        assert body["errors"][0]["pointer"] == "/device_install_id"


async def test_unpaired_connection_cannot_claim_even_from_its_install(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    world = await sms_world(session)
    client = make_client(world)
    await queued_device_send(session, client, world)
    await ConnectionService(env="dev").unpair(session, world.scope, world.connection_id)
    for device in (str(DEVICE_INSTALL), FOREIGN):
        body = problem(await claim(client, world, device=device), 422, "validation_error")
        assert body["errors"][0]["pointer"] == "/device_install_id"


async def test_backend_side_mock_connection_still_answers_empty(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    """A backend-side connector has no binding and no instructions: any install gets an
    empty list, as the contract says for backend-side connections."""
    world = await make_world(session, mode="manual")
    client = make_client(world)
    resp = await claim(client, world, device=FOREIGN)
    assert resp.status_code == 200
    assert_contract("OutboxClaimResult", resp.json())
    assert resp.json()["instructions"] == []


async def test_other_users_connection_is_404_before_the_binding(
    session: AsyncSession, make_client
) -> None:  # type: ignore[no-untyped-def]
    owner = await sms_world(session)
    intruder = await make_world(session)
    client = make_client(owner)
    resp = await client.post(
        f"/v1/connections/{owner.connection_id}/outbox/claim",
        json={"device_install_id": str(DEVICE_INSTALL)},
        headers=headers(intruder.scope),
    )
    problem(resp, 404, "not_found")
