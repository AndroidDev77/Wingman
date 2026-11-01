"""Durable ``Idempotency-Key`` store (ADR 0004).

Flow for a POST handler (the HTTP middleware/dependency is built by the API tickets)::

    repo = IdempotencyRepository(session, scope)
    claim = await repo.claim(key=k, method="POST", route=route_template,
                             request_hash=fingerprint(body))
    match claim.outcome:
        case ClaimOutcome.REPLAY:      -> return claim.record.response_status/body,
                                          header Idempotent-Replayed: true
        case ClaimOutcome.MISMATCH:    -> 422 idempotency_key_mismatch
        case ClaimOutcome.IN_PROGRESS: -> 409 idempotency_key_in_progress + Retry-After
        case ClaimOutcome.CLAIMED:     -> do the work in the same transaction, then
                                          await repo.complete(claim.record.id, status, body)
                                          (2xx/4xx) or await repo.release(...) (5xx)

``claim`` commits nothing itself; run the claim, the side effect and ``complete`` in one
transaction so a crash rolls all three back and the client may retry. Concurrent claims
of the same key serialise on the unique index: the loser blocks until the winner's
transaction ends, then sees the completed row (REPLAY) or claims a released key.
"""

import hashlib
import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import StrEnum
from typing import Any

from sqlalchemy import delete
from sqlalchemy.dialects.postgresql import insert

from app.db.base import utc_now
from app.db.models import IdempotencyKey
from app.db.repositories.base import ScopedRepository

DEFAULT_TTL = timedelta(hours=24)


def fingerprint(body: Any) -> str:
    """SHA-256 hex of the canonical JSON form of a request body."""
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


class ClaimOutcome(StrEnum):
    CLAIMED = "claimed"
    REPLAY = "replay"
    MISMATCH = "mismatch"
    IN_PROGRESS = "in_progress"


@dataclass(frozen=True, slots=True)
class Claim:
    outcome: ClaimOutcome
    record: IdempotencyKey


class IdempotencyRepository(ScopedRepository[IdempotencyKey]):
    model = IdempotencyKey

    async def claim(
        self,
        *,
        key: str,
        method: str,
        route: str,
        request_hash: str,
        ttl: timedelta = DEFAULT_TTL,
        now: datetime | None = None,
    ) -> Claim:
        at = now or utc_now()
        # An expired record no longer protects anything: drop it so the key can be reused.
        await self.session.execute(
            delete(IdempotencyKey).where(
                IdempotencyKey.user_id == self.scope.user_id,
                IdempotencyKey.method == method,
                IdempotencyKey.route == route,
                IdempotencyKey.key == key,
                IdempotencyKey.expires_at <= at,
            )
        )
        stmt = (
            insert(IdempotencyKey)
            .values(
                user_id=self.scope.user_id,
                key=key,
                method=method,
                route=route,
                request_hash=request_hash,
                state="in_progress",
                created_at=at,
                updated_at=at,
                expires_at=at + ttl,
            )
            .on_conflict_do_nothing(
                index_elements=[
                    IdempotencyKey.user_id,
                    IdempotencyKey.method,
                    IdempotencyKey.route,
                    IdempotencyKey.key,
                ]
            )
            .returning(IdempotencyKey)
        )
        created: IdempotencyKey | None = await self.session.scalar(stmt)
        if created is not None:
            return Claim(ClaimOutcome.CLAIMED, created)
        existing = await self.lookup(key=key, method=method, route=route)
        if existing is None:  # pragma: no cover - released concurrently; caller may retry
            raise LookupError("idempotency key vanished during claim")
        if existing.request_hash != request_hash:
            return Claim(ClaimOutcome.MISMATCH, existing)
        if existing.state == "completed":
            return Claim(ClaimOutcome.REPLAY, existing)
        return Claim(ClaimOutcome.IN_PROGRESS, existing)

    async def lookup(self, *, key: str, method: str, route: str) -> IdempotencyKey | None:
        row: IdempotencyKey | None = await self.session.scalar(
            self._select().where(
                IdempotencyKey.method == method,
                IdempotencyKey.route == route,
                IdempotencyKey.key == key,
            )
        )
        return row

    async def complete(self, record_id: uuid.UUID, *, status: int, body: Any) -> IdempotencyKey:
        """Store the final 2xx/4xx response for replay. 5xx must use :meth:`release`."""
        if not 200 <= status <= 499:
            raise ValueError("only 2xx/4xx responses are stored; release the key on 5xx")
        now = utc_now()
        return await self._update(
            record_id,
            state="completed",
            response_status=status,
            response_body=body,
            completed_at=now,
            updated_at=now,
        )

    async def release(self, record_id: uuid.UUID) -> None:
        """Forget an in-progress claim (5xx) so the client can retry with the same key."""
        await self.session.execute(
            delete(IdempotencyKey).where(
                IdempotencyKey.user_id == self.scope.user_id,
                IdempotencyKey.id == record_id,
                IdempotencyKey.state == "in_progress",
            )
        )

    async def purge_expired(self, *, now: datetime | None = None) -> int:
        """Retention: delete this user's expired records. Returns the number removed."""
        at = now or utc_now()
        result = await self.session.execute(
            delete(IdempotencyKey)
            .where(
                IdempotencyKey.user_id == self.scope.user_id,
                IdempotencyKey.expires_at <= at,
            )
            .returning(IdempotencyKey.id)
        )
        return len(result.all())
