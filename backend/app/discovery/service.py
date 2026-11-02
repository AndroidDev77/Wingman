"""WING-031: the profile review workflow on top of contract 1.3.1 (no contract change).

:class:`DiscoveryService` is the :class:`~app.services.integrations.ProfileService` the
profile routes use. It keeps import and decision recording as they are and adds:

* **Decision rate limits** (:mod:`app.discovery.limits`), checked by the route before an
  ``Idempotency-Key`` is claimed (``429 rate_limited``; replays are never limited).
* **The execute gate with audit.** :meth:`DiscoveryService.request_execution` runs every
  ADR 0008 check, then two more: the decision was made on the profile's **current**
  content (an import after the decision supersedes it, so the user must decide again),
  and the per-connection execution limits. Every refusal is audited as
  ``policy_blocked`` with ``reason_code: swipe_execution_disabled`` and the failed check
  (IDs and the check only, never profile content). The refusal is committed with the
  stored idempotent ``422`` (ADR 0004), so the audit survives the error response.
* **Invalidation** (:meth:`DiscoveryService.invalidate_profile`): the provider update
  path. A source that reports a profile changed or gone sets ``profiles.invalidated_at``;
  the profile turns ``stale`` and is never executed. A later import of the profile
  clears it again (fresh content), and any decision made before that import is
  superseded for execution.

**There is no swipe executor.** No connector has a VERIFIED ``swipe`` (matrix in
``docs/integrations/swiping.md``), Hinge's rating and like endpoints are excluded by
D-021/D-023, and contract 1.3.1 has no route for a device to report ``executed``,
``failed`` or ``unknown``. So ``queued`` is reachable only after an operator recorded a
VERIFIED swipe for a connection and the user turned auto-swipe on, and nothing moves an
action past ``queued``. No code here sets ``executed``.
"""

import logging
import uuid
from collections.abc import Callable
from datetime import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    GlobalAutomationStatus,
    ProfileActionExecution,
    ProfileDecision,
)
from app.db.errors import NotFoundError
from app.db.models import Profile, ProfileAction
from app.db.repositories import (
    AgentActionRepository,
    ConnectionRepository,
    ProfileActionRepository,
    ProfileRepository,
    UserRepository,
)
from app.db.scope import UserScope
from app.discovery.limits import (
    DEFAULT_LIMITS,
    DiscoveryLimits,
    decision_rate_retry_after,
    execution_rate_failure,
)
from app.services.connections import swipe_gate_failure
from app.services.integrations import ProfileService, SwipeExecutionDisabled, is_stale

__all__ = ["DENIAL_REASON_CODE", "DiscoveryService"]

logger = logging.getLogger(__name__)

DENIAL_REASON_CODE = "swipe_execution_disabled"


class DiscoveryService(ProfileService):
    def __init__(
        self,
        *,
        now: Callable[[], datetime] = utc_now,
        limits: DiscoveryLimits = DEFAULT_LIMITS,
    ) -> None:
        super().__init__(now=now)
        self.limits = limits

    # ------------------------------------------------------------------ decisions

    async def check_decision_rate(self, session: AsyncSession, scope: UserScope) -> None:
        """Raise :class:`app.discovery.limits.DecisionRateLimited` when over a limit."""
        await decision_rate_retry_after(session, scope, self._now(), self.limits)

    # ------------------------------------------------------------------ invalidation

    async def invalidate_profile(
        self, session: AsyncSession, scope: UserScope, profile_id: uuid.UUID
    ) -> Profile:
        """The source says this profile changed or vanished: mark it stale (idempotent;
        the first invalidation time is kept). Another user's profile: NotFoundError."""
        repo = ProfileRepository(session, scope)
        profile = await repo.require(profile_id)
        if profile.invalidated_at is not None:
            return profile
        now = self._now()
        updated: Profile = await repo._update(  # noqa: SLF001 - scoped update
            profile.id, invalidated_at=now, updated_at=now
        )
        logger.info("profile invalidated user=%s profile=%s", scope.user_id, profile.id)
        return updated

    # ------------------------------------------------------------------ execute gate

    async def request_execution(
        self, session: AsyncSession, scope: UserScope, action_id: uuid.UUID
    ) -> ProfileAction:
        """``requestProfileActionExecution``: ADR 0008's gate plus WING-031's checks.

        ``queued`` at most (accepted, NOT executed). Raises
        :class:`SwipeExecutionDisabled` naming the first failed check, after auditing it.
        """
        actions = ProfileActionRepository(session, scope)
        await actions.require(action_id)
        user = await UserRepository(session).lock(scope)  # serialize gate decisions
        if user is None:  # pragma: no cover
            raise NotFoundError("user not found")
        action = await actions.lock(action_id)
        if action is None:  # pragma: no cover
            raise NotFoundError("profile action not found")
        if action.execution is ProfileActionExecution.QUEUED:
            return action  # already passed the gate: unchanged
        now = self._now()
        failure = await self._gate_failure(session, scope, action, user.global_automation_status)
        if failure is not None:
            await self._audit_denial(session, scope, action, failure, now)
            raise SwipeExecutionDisabled(*failure)
        assert action.connection_id is not None  # noqa: S101 - the gate checked it
        queued = await actions.mark_queued(action.id, at=now)
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.PROFILE_ACTION_QUEUED,
            actor=Actor.USER,
            occurred_at=now,
            details={
                "profile_id": str(action.profile_id),
                "profile_action_id": str(action.id),
                "connection_id": str(action.connection_id),
                "decision": action.decision.value,
            },
        )
        return queued

    async def _gate_failure(
        self,
        session: AsyncSession,
        scope: UserScope,
        action: ProfileAction,
        automation: GlobalAutomationStatus,
    ) -> tuple[str, str] | None:
        if action.execution is not ProfileActionExecution.NOT_EXECUTABLE:
            return "/profile_action_id", "this action was already executed or attempted"
        if action.decision is ProfileDecision.SKIP:
            return "/decision", "skip is never executed"
        if action.connection_id is None:
            return "/connection_id", "the decision names no connection"
        connection = await ConnectionRepository(session, scope).lock_for_update(
            action.connection_id
        )
        if connection is None:
            return "/connection_id", "the connection no longer exists"
        failure = swipe_gate_failure(connection)
        if failure is not None:
            return failure
        if not connection.auto_swipe_enabled:
            return "/connection_id", "auto-swipe is not enabled for this connection"
        if automation is GlobalAutomationStatus.PAUSED:
            return "/connection_id", "automation is paused"
        profile = await ProfileRepository(session, scope).require(action.profile_id)
        now = self._now()
        if profile.provider.value != connection.platform:
            return "/connection_id", "the profile is on another platform"
        if is_stale(profile, now):
            return "/profile_id", "the profile is stale"
        if action.decided_at < profile.imported_at:
            return (
                "/profile_id",
                "the profile was imported again after this decision; decide again",
            )
        if await ProfileActionRepository(session, scope).live_for_profile(profile.id) is not None:
            return "/profile_id", "another action for this profile is already queued or executed"
        return await execution_rate_failure(session, scope, connection.id, now, self.limits)

    async def _audit_denial(
        self,
        session: AsyncSession,
        scope: UserScope,
        action: ProfileAction,
        failure: tuple[str, str],
        now: datetime,
    ) -> None:
        pointer, message = failure
        await AgentActionRepository(session, scope).record(
            type=AgentActionType.POLICY_BLOCKED,
            actor=Actor.USER,
            occurred_at=now,
            reason_code=DENIAL_REASON_CODE,
            details={
                "profile_id": str(action.profile_id),
                "profile_action_id": str(action.id),
                "connection_id": str(action.connection_id) if action.connection_id else None,
                "decision": action.decision.value,
                "check": pointer,
                "reason": message,
            },
        )
        logger.info(
            "swipe execution refused user=%s action=%s check=%s",
            scope.user_id,
            action.id,
            pointer,
        )
