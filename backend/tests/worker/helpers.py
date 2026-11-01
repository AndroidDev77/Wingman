"""Shared steps for the worker tests (committed transactions on a scratch database)."""

import uuid
from collections.abc import Awaitable, Callable
from datetime import datetime
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.connectors.events import ConnectorEvent
from app.db.enums import Actor, MessageDirection, MessageOrigin
from app.db.models import Message
from app.db.repositories import ConnectionRepository, ConversationRepository
from tests.automation.conftest import World, make_world, set_mode

Factory = async_sessionmaker[AsyncSession]


async def tx[T](factory: Factory, fn: Callable[[AsyncSession], Awaitable[T]]) -> T:
    async with factory() as session, session.begin():
        return await fn(session)


async def setup(
    factory: Factory, mode: str = "manual", prefs: dict[str, Any] | None = None, **kw: Any
) -> World:
    async def go(s: AsyncSession) -> World:
        world = await make_world(s, **kw)
        await set_mode(s, world.scope, mode, **(prefs or {}))
        return world

    return await tx(factory, go)


async def ingest(factory: Factory, world: World, raw: dict[str, Any]) -> uuid.UUID | None:
    """Ingest one envelope (committed); returns the new inbound message id."""

    async def step(s: AsyncSession) -> uuid.UUID | None:
        connection = await ConnectionRepository(s, world.scope).require(world.connection_id)
        r = await world.services.ingest.ingest(
            s,
            world.scope,
            connection,
            ConnectorEvent.model_validate(raw),
            request_hash=world.services.ingest.request_hash(raw),
        )
        return r.new_inbound_message_id

    return await tx(factory, step)


async def run_pipeline(factory: Factory, world: World, message_id: uuid.UUID) -> Any:
    """Like the API: the pipeline only queues (``defer_dispatch``) and commits."""
    return await tx(
        factory,
        lambda s: world.controller.handle_inbound_message(
            s, world.scope, message_id, defer_dispatch=True
        ),
    )


async def queue_manual(
    factory: Factory,
    world: World,
    text: str,
    *,
    send_after: datetime | None = None,
    count: int = 1,
) -> list[uuid.UUID]:
    """Queue ``count`` ``user_manual`` sends into the world's first conversation
    (committed, not dispatched)."""

    async def go(s: AsyncSession) -> list[uuid.UUID]:
        inbound = await s.scalar(
            select(Message).where(
                Message.user_id == world.scope.user_id,
                Message.direction == MessageDirection.INBOUND,
            )
        )
        assert inbound is not None
        conv = await ConversationRepository(s, world.scope).require(inbound.conversation_id)
        ids = []
        for i in range(count):
            m = await world.dispatcher.queue(
                s,
                world.scope,
                conversation=conv,
                origin=MessageOrigin.USER_MANUAL,
                text=text if count == 1 else f"{text} {i}",
                actor=Actor.USER,
                send_after=send_after,
            )
            ids.append(m.id)
        return ids

    return await tx(factory, go)


async def get_message(factory: Factory, message_id: uuid.UUID) -> Message:
    async with factory() as s:
        m = await s.get(Message, message_id)
        assert m is not None
        return m


async def warm(factory: Factory, world: World) -> None:
    """Get the conversation past ``first_message``: ingest, approve and send the first
    reply (so later autopilot replies need no approval)."""
    from app.db.models import Approval

    message_id = await ingest(factory, world, world.event("hi", external_message_id="warm"))
    assert message_id is not None
    await run_pipeline(factory, world, message_id)

    async def approve(s: AsyncSession) -> None:
        approval = await s.scalar(select(Approval).where(Approval.user_id == world.scope.user_id))
        assert approval is not None
        _, m = await world.controller.approve(s, world.scope, approval.id, edited_text=None)
        await world.dispatcher.dispatch(s, world.scope, m.id)

    await tx(factory, approve)
