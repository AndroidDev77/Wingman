"""Shared helpers for the contract 1.3.0 tests."""

import dataclasses
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import ConnectionTransport
from app.db.repositories import ConnectionRepository
from tests.automation.conftest import World, capabilities_doc, make_world

EXPERIMENTAL_INSTALL = uuid.UUID("00000000-0000-4000-8000-0000000000e1")


async def experimental_world(
    session: AsyncSession,
    *,
    platform: str = "hinge",
    mode: str | None = "autopilot",
    send: str = "UNVERIFIED",
) -> World:
    """A world whose connection is an experimental (unofficial API) one with a recorded
    consent, as ``createConnection`` would store it (capabilities as given here)."""
    world = await make_world(session, platform=platform, send=send, device_side=True, mode=mode)
    conn = await ConnectionRepository(session, world.scope).create(
        platform=platform,
        display_name=f"{platform} (experimental)",
        capabilities=capabilities_doc(platform, send=send),
        device_install_id=EXPERIMENTAL_INSTALL,
        paired_at=world.clock.now,
        transport=ConnectionTransport.UNOFFICIAL_API,
        consent_text_version="d021-v1",
        consent_accepted_at=world.clock.now,
    )
    world.registry.mark_device_side(conn.id)
    return dataclasses.replace(world, connection_id=conn.id)
