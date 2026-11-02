"""WING-031 rate limits for profile decisions and swipe execution requests.

Two independent limits, both counted from stored rows (no in-memory state, so they hold
across workers and restarts):

* **Decisions** (``recordProfileDecision``): at most :attr:`DiscoveryLimits.decisions_per_minute`
  per rolling minute and :attr:`DiscoveryLimits.decisions_per_day` per rolling 24 hours, per
  user. Over the limit the route answers ``429 rate_limited`` with ``Retry-After``. The
  check runs only for a **new** ``Idempotency-Key``: a replay of an earlier request is
  never limited, and a limited request is not stored, so a retry with the same key after
  the window succeeds.
* **Execution requests** (``requestProfileActionExecution``): at most
  :attr:`DiscoveryLimits.executions_per_day` actions queued per connection per rolling 24
  hours, and at least :attr:`DiscoveryLimits.min_execution_interval` between two queued
  actions on one connection. Over the limit the gate answers ``422
  swipe_execution_disabled`` naming the limit. (No connection has a VERIFIED swipe today,
  so in practice the gate refuses earlier; the limits are there for the day one does.)

The numbers are deliberately conservative (D-021: conservative limits, stop on
enforcement). They can only be tightened by configuration, never by a client.
"""

import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import ProfileActionExecution
from app.db.models import ProfileAction
from app.db.scope import UserScope

__all__ = [
    "DEFAULT_LIMITS",
    "DecisionRateLimited",
    "DiscoveryLimits",
    "decision_rate_retry_after",
    "execution_rate_failure",
]


@dataclass(frozen=True)
class DiscoveryLimits:
    decisions_per_minute: int = 30
    decisions_per_day: int = 1000
    executions_per_day: int = 25
    min_execution_interval: timedelta = timedelta(seconds=30)

    def __post_init__(self) -> None:
        if min(self.decisions_per_minute, self.decisions_per_day, self.executions_per_day) < 1:
            raise ValueError("limits must be at least 1")
        if self.min_execution_interval < timedelta(0):
            raise ValueError("min_execution_interval must not be negative")


DEFAULT_LIMITS = DiscoveryLimits()

_MINUTE = timedelta(minutes=1)
_DAY = timedelta(days=1)


class DecisionRateLimited(Exception):  # noqa: N818 - maps to 429 rate_limited
    def __init__(self, retry_after_s: int, window: str) -> None:
        super().__init__(f"decision limit reached ({window})")
        self.retry_after_s = max(1, retry_after_s)
        self.window = window


async def _decisions_since(
    session: AsyncSession, scope: UserScope, since: datetime
) -> tuple[int, datetime | None]:
    row = (
        await session.execute(
            select(func.count(), func.min(ProfileAction.decided_at)).where(
                ProfileAction.user_id == scope.user_id, ProfileAction.decided_at > since
            )
        )
    ).one()
    return int(row[0]), row[1]


async def decision_rate_retry_after(
    session: AsyncSession, scope: UserScope, now: datetime, limits: DiscoveryLimits
) -> None:
    """Raise :class:`DecisionRateLimited` when one more decision would exceed a limit."""
    for window, span, cap in (
        ("minute", _MINUTE, limits.decisions_per_minute),
        ("day", _DAY, limits.decisions_per_day),
    ):
        count, oldest = await _decisions_since(session, scope, now - span)
        if count >= cap:
            # The oldest decision in the window leaves it first.
            wait = (oldest + span - now) if oldest is not None else span
            raise DecisionRateLimited(int(wait.total_seconds()) + 1, window)


async def execution_rate_failure(
    session: AsyncSession,
    scope: UserScope,
    connection_id: uuid.UUID,
    now: datetime,
    limits: DiscoveryLimits,
) -> tuple[str, str] | None:
    """``(pointer, message)`` when queuing one more action on this connection would exceed
    a limit; ``None`` otherwise. Counts every action that passed the gate (whatever its
    later outcome), so a failed or unknown execution still uses up the allowance."""
    requested = ProfileAction.execution_requested_at
    row = (
        await session.execute(
            select(func.count(), func.max(requested)).where(
                ProfileAction.user_id == scope.user_id,
                ProfileAction.connection_id == connection_id,
                ProfileAction.execution != ProfileActionExecution.NOT_EXECUTABLE,
                requested > now - _DAY,
            )
        )
    ).one()
    count, latest = int(row[0]), row[1]
    if count >= limits.executions_per_day:
        return "/connection_id", "daily swipe limit reached for this connection"
    if latest is not None and now - latest < limits.min_execution_interval:
        return "/connection_id", "too soon after the previous swipe on this connection"
    return None
