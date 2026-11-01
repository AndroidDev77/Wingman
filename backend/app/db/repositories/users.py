"""Users. The ``users`` row *is* the tenant, so this repository is not scope-bound;
lookups by auth identity exist only to resolve a token into a :class:`UserScope`."""

from datetime import datetime

from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import utc_now
from app.db.enums import GlobalAutomationStatus
from app.db.errors import NotFoundError
from app.db.models import User
from app.db.scope import UserScope


class UserRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get_or_create_by_auth(
        self,
        auth_issuer: str,
        auth_subject: str,
        *,
        email: str | None = None,
        display_name: str | None = None,
    ) -> User:
        """Return the user for an OIDC ``(iss, sub)``, creating it on first login.

        Safe under concurrent first logins (``INSERT ... ON CONFLICT DO NOTHING``).
        """
        stmt = (
            insert(User)
            .values(
                auth_issuer=auth_issuer,
                auth_subject=auth_subject,
                email=email,
                display_name=display_name,
            )
            .on_conflict_do_nothing(index_elements=[User.auth_issuer, User.auth_subject])
            .returning(User)
        )
        created = await self.session.scalar(stmt)
        if created is not None:
            return created
        existing = await self.get_by_auth(auth_issuer, auth_subject)
        if existing is None:  # pragma: no cover - only if deleted concurrently
            raise NotFoundError("user vanished during get_or_create")
        return existing

    async def get_by_auth(self, auth_issuer: str, auth_subject: str) -> User | None:
        user: User | None = await self.session.scalar(
            select(User).where(User.auth_issuer == auth_issuer, User.auth_subject == auth_subject)
        )
        return user

    async def get(self, scope: UserScope) -> User | None:
        user: User | None = await self.session.get(User, scope.user_id)
        return user

    async def set_global_automation_status(
        self, scope: UserScope, status: GlobalAutomationStatus, *, at: datetime | None = None
    ) -> User:
        user: User | None = await self.session.scalar(
            update(User)
            .where(User.id == scope.user_id)
            .values(global_automation_status=status, global_automation_changed_at=at or utc_now())
            .returning(User)
            .execution_options(synchronize_session="fetch")
        )
        if user is None:
            raise NotFoundError("user not found")
        return user

    async def mark_deleted(self, scope: UserScope, *, at: datetime | None = None) -> None:
        """Retention: flag the account for purge. Hard delete cascades to all user rows."""
        await self.session.execute(
            update(User).where(User.id == scope.user_id).values(deleted_at=at or utc_now())
        )
