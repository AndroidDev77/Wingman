"""Postgres session-level advisory locks: one replica runs a given job at a time.

The lock is taken with ``pg_try_advisory_lock`` on a dedicated connection that is held
(outside any transaction) for the whole tick and released with ``pg_advisory_unlock``.
If the process dies, Postgres drops the connection and releases the lock, so a crashed
worker never blocks the others. A replica that does not get the lock skips that tick.

These locks are an efficiency measure, not the correctness guarantee: every job is
already safe to run concurrently because of row locks in the services (see
``app.worker`` and ``docs/deployment.md``). Session-level advisory locks need a real
session: they do not work through a transaction-pooling proxy (PgBouncer
``pool_mode=transaction``); set ``WINGMAN_WORKER_ADVISORY_LOCKS=false`` there.
"""

import hashlib
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine

__all__ = ["advisory_key", "advisory_lock"]

logger = logging.getLogger(__name__)

_TRY_LOCK = text("SELECT pg_try_advisory_lock(:key)")
_UNLOCK = text("SELECT pg_advisory_unlock(:key)")


def advisory_key(name: str) -> int:
    """A stable signed 64-bit lock key for a job name."""
    digest = hashlib.sha256(f"wingman.worker:{name}".encode()).digest()
    return int.from_bytes(digest[:8], "big", signed=True)


@asynccontextmanager
async def advisory_lock(engine: AsyncEngine, key: int) -> AsyncIterator[bool]:
    """Yield ``True`` while holding the lock, ``False`` if another session holds it."""
    async with engine.connect() as conn:
        acquired = bool(await conn.scalar(_TRY_LOCK, {"key": key}))
        await conn.commit()  # the session-level lock outlives the transaction
        try:
            yield acquired
        finally:
            if acquired:
                try:
                    await conn.execute(_UNLOCK, {"key": key})
                    await conn.commit()
                except Exception as exc:
                    # Closing the connection releases a session-level lock for certain.
                    logger.warning(
                        "advisory unlock failed; discarding the connection error_class=%s",
                        type(exc).__name__,
                    )
                    await conn.invalidate()
