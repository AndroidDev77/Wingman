"""Platform connections and contacts."""

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy.dialects.postgresql import insert

from app.db.base import utc_now
from app.db.enums import CapabilityVerification, ConnectionStatus, ContactAutomationStatus
from app.db.models import Contact, PlatformConnection
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository

CAPABILITY_NAMES = ("read", "draft", "send", "swipe", "calendar_write")


def capability_columns(capabilities: dict[str, Any]) -> dict[str, CapabilityVerification]:
    """Extract the five verification labels from a ``connector_capabilities.v1`` document.

    Raises ``ValueError`` if any capability is missing (ADR 0005: none may be omitted).
    """
    caps = capabilities.get("capabilities")
    if not isinstance(caps, dict):
        raise ValueError("capabilities document lacks a 'capabilities' object")
    out: dict[str, CapabilityVerification] = {}
    for name in CAPABILITY_NAMES:
        entry = caps.get(name)
        if not isinstance(entry, dict) or "status" not in entry:
            raise ValueError(f"capability '{name}' must be stated explicitly")
        out[f"{name}_capability"] = CapabilityVerification(entry["status"])
    return out


class ConnectionRepository(ScopedRepository[PlatformConnection]):
    model = PlatformConnection

    async def create(
        self,
        *,
        platform: str,
        display_name: str,
        capabilities: dict[str, Any],
        status: ConnectionStatus = ConnectionStatus.CONNECTED,
    ) -> PlatformConnection:
        return await self._add(
            platform=platform,
            display_name=display_name,
            status=status,
            capabilities=capabilities,
            **capability_columns(capabilities),
        )

    async def set_capabilities(
        self, connection_id: uuid.UUID, capabilities: dict[str, Any]
    ) -> PlatformConnection:
        """Apply a ``connection.capabilities_changed`` event."""
        return await self._update(
            connection_id,
            capabilities=capabilities,
            updated_at=utc_now(),
            **capability_columns(capabilities),
        )

    async def set_status(
        self, connection_id: uuid.UUID, status: ConnectionStatus
    ) -> PlatformConnection:
        return await self._update(connection_id, status=status, updated_at=utc_now())

    async def list(
        self, *, cursor: str | None = None, limit: int | None = None
    ) -> Page[PlatformConnection]:
        """Oldest first (``created_at``, ``id``)."""
        return await self._page(
            self._select(),
            listing="connections",
            sort_column=PlatformConnection.created_at,
            descending=False,
            cursor=cursor,
            limit=limit,
        )


class ContactRepository(ScopedRepository[Contact]):
    model = Contact

    async def get_or_create(
        self,
        *,
        connection_id: uuid.UUID,
        external_contact_id: str,
        display_name: str,
    ) -> Contact:
        """Idempotent on ``(user, connection, external_contact_id)``.

        The connection must belong to this user (composite FK), otherwise
        ``IntegrityError`` is raised.
        """
        stmt = (
            insert(Contact)
            .values(
                user_id=self.scope.user_id,
                connection_id=connection_id,
                external_contact_id=external_contact_id,
                display_name=display_name,
            )
            .on_conflict_do_nothing(
                index_elements=[Contact.user_id, Contact.connection_id, Contact.external_contact_id]
            )
            .returning(Contact)
        )
        created: Contact | None = await self.session.scalar(stmt)
        if created is not None:
            return created
        existing: Contact | None = await self.session.scalar(
            self._select().where(
                Contact.connection_id == connection_id,
                Contact.external_contact_id == external_contact_id,
            )
        )
        if existing is None:  # pragma: no cover - only if deleted concurrently
            raise LookupError("contact vanished during get_or_create")
        return existing

    async def set_automation_status(
        self,
        contact_id: uuid.UUID,
        status: ContactAutomationStatus,
        *,
        at: datetime | None = None,
    ) -> Contact:
        when = at or utc_now()
        return await self._update(
            contact_id,
            automation_status=status,
            automation_status_changed_at=when,
            updated_at=when,
        )

    async def list(self, *, cursor: str | None = None, limit: int | None = None) -> Page[Contact]:
        return await self._page(
            self._select(),
            listing="contacts",
            sort_column=Contact.created_at,
            descending=False,
            cursor=cursor,
            limit=limit,
        )
