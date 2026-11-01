"""Subscriptions and usage events (WING-022 / WING-024 build on these)."""

import uuid
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy import func
from sqlalchemy.dialects.postgresql import insert

from app.db.base import utc_now
from app.db.models import Subscription, UsageEvent
from app.db.repositories.base import ScopedRepository

LIVE_STATUSES = ("trialing", "active", "past_due")


class SubscriptionRepository(ScopedRepository[Subscription]):
    model = Subscription

    async def create(
        self,
        *,
        plan: str,
        status: str,
        provider: str = "none",
        external_subscription_id: str | None = None,
        current_period_start: datetime | None = None,
        current_period_end: datetime | None = None,
        trial_ends_at: datetime | None = None,
    ) -> Subscription:
        """At most one live (trialing/active/past_due) subscription per user (unique index)."""
        return await self._add(
            plan=plan,
            status=status,
            provider=provider,
            external_subscription_id=external_subscription_id,
            current_period_start=current_period_start,
            current_period_end=current_period_end,
            trial_ends_at=trial_ends_at,
        )

    async def current(self) -> Subscription | None:
        row: Subscription | None = await self.session.scalar(
            self._select().where(Subscription.status.in_(LIVE_STATUSES)).limit(1)
        )
        return row

    async def update(self, subscription_id: uuid.UUID, **values: Any) -> Subscription:
        return await self._update(subscription_id, updated_at=utc_now(), **values)


@dataclass(frozen=True, slots=True)
class UsageRecordResult:
    event: UsageEvent
    created: bool


class UsageEventRepository(ScopedRepository[UsageEvent]):
    model = UsageEvent

    async def record(
        self,
        *,
        event_type: str,
        dedup_key: str,
        occurred_at: datetime,
        quantity: int = 1,
        unit: str | None = None,
        cost_micros: int | None = None,
        conversation_id: uuid.UUID | None = None,
        connection_id: uuid.UUID | None = None,
        message_id: uuid.UUID | None = None,
        attributes: dict[str, Any] | None = None,
    ) -> UsageRecordResult:
        """Idempotent on ``(user, event_type, dedup_key)``: retried events count once."""
        stmt = (
            insert(UsageEvent)
            .values(
                user_id=self.scope.user_id,
                event_type=event_type,
                dedup_key=dedup_key,
                occurred_at=occurred_at,
                quantity=quantity,
                unit=unit,
                cost_micros=cost_micros,
                conversation_id=conversation_id,
                connection_id=connection_id,
                message_id=message_id,
                attributes=dict(attributes or {}),
            )
            .on_conflict_do_nothing(
                index_elements=[UsageEvent.user_id, UsageEvent.event_type, UsageEvent.dedup_key]
            )
            .returning(UsageEvent)
        )
        created: UsageEvent | None = await self.session.scalar(stmt)
        if created is not None:
            return UsageRecordResult(event=created, created=True)
        existing: UsageEvent | None = await self.session.scalar(
            self._select().where(
                UsageEvent.event_type == event_type, UsageEvent.dedup_key == dedup_key
            )
        )
        if existing is None:  # pragma: no cover - only if deleted concurrently
            raise LookupError("usage event vanished during record")
        return UsageRecordResult(event=existing, created=False)

    async def total(self, event_type: str, *, since: datetime, until: datetime) -> int:
        """Sum of ``quantity`` for ``event_type`` in ``[since, until)``."""
        value = await self.session.scalar(
            self._select()
            .with_only_columns(func.coalesce(func.sum(UsageEvent.quantity), 0))
            .where(
                UsageEvent.event_type == event_type,
                UsageEvent.occurred_at >= since,
                UsageEvent.occurred_at < until,
            )
        )
        return int(value or 0)
