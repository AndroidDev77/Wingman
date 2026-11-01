"""Transport-level data shapes exchanged with a `Connector`.

These are connector-side views (platform IDs, raw text), not the `/v1` HTTP
resources. Mapping them onto `Conversation` / `Message` rows is the job of the
ingest and persistence layers (WING-003, WING-011).
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

from app.connectors._types import ExternalId, IdempotencyKey, UtcTimestamp, WingmanId
from app.connectors.events import ConnectorEvent, SendFailure

DEFAULT_PAGE_LIMIT = 50
MAX_PAGE_LIMIT = 100

PageLimit = Annotated[int, Field(ge=1, le=MAX_PAGE_LIMIT)]


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class _RedactedText(_Model):
    """Keeps `text` out of reprs so it can't leak into logs by accident."""

    def __repr_args__(self) -> Any:
        for name, value in super().__repr_args__():
            yield name, ("<redacted>" if name == "text" else value)


class Page[T](_Model):
    """Cursor page (ADR 0003). `next_cursor is None` marks the end."""

    items: list[T]
    next_cursor: str | None = None


class MessageDirection(StrEnum):
    INBOUND = "inbound"
    OUTBOUND = "outbound"


class ConnectorContact(_Model):
    external_contact_id: ExternalId
    display_name: str | None = None


class ConnectorConversation(_Model):
    external_thread_id: ExternalId
    participants: list[ConnectorContact]
    last_activity_at: UtcTimestamp


class ConnectorMessage(_RedactedText):
    """One message as the transport sees it. `text` is private: never log it."""

    external_message_id: ExternalId
    external_thread_id: ExternalId
    direction: MessageDirection
    sender: ConnectorContact | None = Field(
        default=None, description="Set for inbound messages; None for our own outbound."
    )
    text: str
    occurred_at: UtcTimestamp
    outbound_message_id: WingmanId | None = Field(
        default=None, description="Wingman message ID, for outbound messages we sent."
    )


class SendRequest(_RedactedText):
    """What the dispatcher asks a connector to transmit.

    `idempotency_key` is the dispatcher's send key (e.g. `send:<message_id>`). A
    connector performs at most one transport send per key (ADR 0004), so a retry
    after a failure needs a new key (e.g. `send:<message_id>:2`).
    """

    outbound_message_id: WingmanId
    idempotency_key: IdempotencyKey
    external_thread_id: ExternalId
    text: Annotated[str, StringConstraints(min_length=1, max_length=20000)]

    def fingerprint(self) -> tuple[UUID, str, str]:
        """Identity of the request body; a reused key with a different body is a conflict."""
        return (self.outbound_message_id, self.external_thread_id, self.text)


class SendStatus(StrEnum):
    """Outcome of one send call, as reported by the transport.

    `acknowledged` means the transport accepted the message (drives `sent`), never
    `delivered`: delivery needs a separate receipt event.
    """

    ACKNOWLEDGED = "acknowledged"
    FAILED = "failed"


class SendResult(_Model):
    status: SendStatus
    outbound_message_id: WingmanId
    idempotency_key: IdempotencyKey
    external_message_id: ExternalId | None = None
    failure: SendFailure | None = None
    replayed: bool = Field(
        default=False,
        description="True when this result was returned for a repeated idempotency key "
        "without performing another transport send.",
    )
    event: ConnectorEvent = Field(description="The acknowledgement or failure event emitted.")

    @property
    def acknowledged(self) -> bool:
        return self.status is SendStatus.ACKNOWLEDGED


class ConnectorProfile(_Model):
    """A discoverable profile (only on connectors with the `swipe` capability)."""

    external_profile_id: ExternalId
    display_name: str | None = None


class SwipeDecision(StrEnum):
    LIKE = "like"
    PASS = "pass"  # noqa: S105 - swipe decision, not a secret


class SwipeRequest(_Model):
    external_profile_id: ExternalId
    decision: SwipeDecision
    idempotency_key: IdempotencyKey


class SwipeResult(_Model):
    external_profile_id: ExternalId
    decision: SwipeDecision
    matched: bool | None = None
    replayed: bool = False


__all__ = [
    "DEFAULT_PAGE_LIMIT",
    "MAX_PAGE_LIMIT",
    "ConnectorContact",
    "ConnectorConversation",
    "ConnectorMessage",
    "ConnectorProfile",
    "MessageDirection",
    "Page",
    "PageLimit",
    "SendRequest",
    "SendResult",
    "SendStatus",
    "SwipeDecision",
    "SwipeRequest",
    "SwipeResult",
]
