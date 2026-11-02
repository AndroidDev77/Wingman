"""Platform connections and contacts."""

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy.dialects.postgresql import insert

from app.db.base import utc_now
from app.db.enums import (
    CapabilityVerification,
    ConnectionStatus,
    ConnectionTransport,
    ContactAutomationStatus,
)
from app.db.models import Contact, PlatformConnection
from app.db.pagination import Page
from app.db.repositories.base import ScopedRepository

CAPABILITY_NAMES = ("read", "draft", "send", "swipe", "calendar_write")
# Contract 1.3.0: optional in the document; absent means UNSUPPORTED (ADR 0008).
OPTIONAL_CAPABILITY_NAMES = ("profile_read",)

# The transport a platform implies when it has only one (contract 1.3.0). Dating apps
# have two (notification, unofficial_api), so pairing must say which.
DEFAULT_TRANSPORT: dict[str, ConnectionTransport] = {
    "mock": ConnectionTransport.SIMULATOR,
    "sms": ConnectionTransport.SMS_PROVIDER,
    "android_notification": ConnectionTransport.NOTIFICATION,
    "imessage": ConnectionTransport.COMPANION,
}


def default_transport(platform: str) -> ConnectionTransport:
    """The transport for a platform with a single one; ``notification`` otherwise."""
    return DEFAULT_TRANSPORT.get(platform, ConnectionTransport.NOTIFICATION)


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
    for name in OPTIONAL_CAPABILITY_NAMES:
        entry = caps.get(name)
        status = entry.get("status") if isinstance(entry, dict) else None
        out[f"{name}_capability"] = CapabilityVerification(status or "UNSUPPORTED")
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
        device_install_id: uuid.UUID | None = None,
        paired_at: datetime | None = None,
        transport: ConnectionTransport | None = None,
        consent_text_version: str | None = None,
        consent_accepted_at: datetime | None = None,
    ) -> PlatformConnection:
        """``transport`` defaults from the platform. ``unofficial_api`` makes the
        connection experimental and needs the consent (DB CHECK, ADR 0008)."""
        chosen = transport or default_transport(platform)
        experimental = chosen is ConnectionTransport.UNOFFICIAL_API
        return await self._add(
            platform=platform,
            display_name=display_name,
            status=status,
            capabilities=capabilities,
            device_install_id=device_install_id,
            paired_at=paired_at,
            transport=chosen,
            experimental=experimental,
            consent_text_version=consent_text_version if experimental else None,
            consent_accepted_at=consent_accepted_at if experimental else None,
            consent_recorded_at=paired_at if experimental else None,
            **capability_columns(capabilities),
        )

    async def paired_for_install(
        self, platform: str, device_install_id: uuid.UUID
    ) -> PlatformConnection | None:
        """The still-paired connection of this install for ``platform`` (contract 1.2.0)."""
        row: PlatformConnection | None = await self.session.scalar(
            self._select().where(
                PlatformConnection.platform == platform,
                PlatformConnection.device_install_id == device_install_id,
                PlatformConnection.unpaired_at.is_(None),
            )
        )
        return row

    async def lock_for_update(self, connection_id: uuid.UUID) -> PlatformConnection | None:
        row: PlatformConnection | None = await self.session.scalar(
            self._select()
            .where(PlatformConnection.id == connection_id)
            .with_for_update(key_share=True)  # FOR NO KEY UPDATE: see UserRepository.lock
            .execution_options(populate_existing=True)
        )
        return row

    async def mark_unpaired(
        self, connection_id: uuid.UUID, *, capabilities: dict[str, Any], at: datetime
    ) -> PlatformConnection:
        """``unpairConnection``: disconnected, every capability as given, ``unpaired_at``.
        Contract 1.3.0: auto-swipe off, and an experimental consent is revoked."""
        current = await self.get(connection_id)
        extra: dict[str, Any] = {}
        if current is not None and current.experimental and current.consent_revoked_at is None:
            extra["consent_revoked_at"] = at
        return await self._update(
            connection_id,
            status=ConnectionStatus.DISCONNECTED,
            unpaired_at=at,
            capabilities=capabilities,
            updated_at=at,
            auto_swipe_enabled=False,
            **extra,
            **capability_columns(capabilities),
        )

    async def set_auto_swipe(self, connection_id: uuid.UUID, enabled: bool) -> PlatformConnection:
        return await self._update(connection_id, auto_swipe_enabled=enabled, updated_at=utc_now())

    async def mark_experimental_verified(
        self, connection_id: uuid.UUID, *, at: datetime
    ) -> PlatformConnection:
        """Operator-only (no route): an experimental connection passed its live test."""
        return await self._update(connection_id, experimental_verified_at=at, updated_at=at)

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

    async def lock_for_update(self, contact_id: uuid.UUID) -> Contact | None:
        row: Contact | None = await self.session.scalar(
            self._select()
            .where(Contact.id == contact_id)
            .with_for_update(key_share=True)  # FOR NO KEY UPDATE: see UserRepository.lock
            .execution_options(populate_existing=True)
        )
        return row

    async def lock_shared(self, contact_id: uuid.UUID) -> Contact | None:
        """The contact, ``FOR SHARE``-locked and refreshed: a concurrent takeover or pause
        (which updates this row) waits until the caller's decision is committed."""
        row: Contact | None = await self.session.scalar(
            self._select()
            .where(Contact.id == contact_id)
            .with_for_update(read=True)
            .execution_options(populate_existing=True)
        )
        return row

    async def list(self, *, cursor: str | None = None, limit: int | None = None) -> Page[Contact]:
        return await self._page(
            self._select(),
            listing="contacts",
            sort_column=Contact.created_at,
            descending=False,
            cursor=cursor,
            limit=limit,
        )
