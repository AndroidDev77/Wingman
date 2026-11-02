"""Normalize chat.db rows into ``connector_event.v1`` envelopes (platform ``imessage``).

Identity scheme (stable across restarts, reinstalls and chat.db rebuilds, because it
uses Apple's GUIDs and never the ROWID):

* ``external_message_id``: ``imessage:`` + ``message.guid``
* ``external_thread_id``: ``imessage:chat:`` + ``chat.guid``. It keeps the
  ``iMessage;-;`` / ``SMS;-;`` / ``any;-;`` prefix, so the transport stays visible.
* ``external_contact_id``: ``imessage:`` + normalized ``handle.id`` (phone digits with
  ``+``, or a lower-cased e-mail)
* ``event_id``: UUIDv5 of ``(user_id, type, external_message_id or idempotency_key)``

Contract note: ``connector_event.v1`` accepts any ``platform`` matching its pattern, so
these envelopes validate today. The backend only accepts them once contract v1.3 makes
``imessage`` pairable (``ConnectionCreateRequest.platform``). The inbound payload has
``additionalProperties: false``: there is no field for the transport (iMessage vs SMS
vs RCS), group flag or reactions, so those live only in the thread ID or are dropped.
"""

from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from . import PLATFORM
from .chatdb import MessageRow
from .timeconv import to_contract_timestamp

# Fixed namespace for this connector's name-based event IDs.
EVENT_NAMESPACE = uuid.UUID("6f0e4a52-6d0b-5d4e-9a38-1f5d2c0e2002")
MAX_TEXT = 20_000
OBJECT_REPLACEMENT = "￼"  # placeholder Messages puts in text for each attachment
_PHONE_CHARS = re.compile(r"[\s().\-]")


@dataclass(frozen=True, slots=True)
class Scope:
    user_id: str
    connection_id: str


class SkipReason:
    FROM_ME = "from_me"
    REACTION = "reaction"
    SYSTEM_ITEM = "system_item"
    RETRACTED = "retracted"
    NO_CONTENT = "no_content"
    NO_SENDER = "no_sender"
    NO_CHAT = "no_chat"
    BEFORE_BASELINE = "before_baseline"
    NO_DATE = "no_date"


def normalize_handle(handle: str) -> str:
    h = handle.strip()
    if "@" in h:
        return h.lower()
    cleaned = _PHONE_CHARS.sub("", h)
    return cleaned


def external_message_id(guid: str) -> str:
    return f"{PLATFORM}:{guid}"


def external_thread_id(chat_guid: str) -> str:
    return f"{PLATFORM}:chat:{chat_guid}"


def chat_guid_from_thread_id(thread_id: str) -> str | None:
    prefix = f"{PLATFORM}:chat:"
    if not thread_id.startswith(prefix):
        return None
    guid = thread_id[len(prefix) :]
    return guid or None


def transport_of(chat_guid: str | None, service: str | None) -> str:
    """'imessage' | 'sms' | 'rcs' | 'unknown' from the row's service or the chat prefix."""
    for value in (service, (chat_guid or "").split(";", 1)[0]):
        v = (value or "").lower()
        if v == "imessage":
            return "imessage"
        if v in ("sms", "mms"):
            return "sms"
        if v == "rcs":
            return "rcs"
    return "unknown"


def event_id(user_id: str, event_type: str, key: str) -> str:
    return str(uuid.uuid5(EVENT_NAMESPACE, f"{user_id}|{event_type}|{key}"))


def clean_text(text: str | None) -> str:
    if text is None:
        return ""
    t = text.replace(OBJECT_REPLACEMENT, "").strip()
    return t[:MAX_TEXT]


def classify(row: MessageRow, baseline: datetime | None = None) -> str | None:
    """Return a SkipReason, or None when the row becomes ``message.received``."""
    if row.is_from_me:
        return SkipReason.FROM_ME
    if row.is_reaction:
        return SkipReason.REACTION
    if row.is_system_item:
        return SkipReason.SYSTEM_ITEM
    if row.retracted:
        return SkipReason.RETRACTED
    if row.occurred_at is None:
        return SkipReason.NO_DATE
    if baseline is not None and row.occurred_at < baseline:
        return SkipReason.BEFORE_BASELINE
    if not row.sender_handle:
        return SkipReason.NO_SENDER
    if not row.chat_guid:
        return SkipReason.NO_CHAT
    if not clean_text(row.text) and row.attachment_count == 0:
        return SkipReason.NO_CONTENT
    return None


def received_envelope(row: MessageRow, scope: Scope, observed_at: datetime | None = None) -> dict[str, Any]:
    """Build ``message.received``. Caller must have checked ``classify(row) is None``."""
    assert row.chat_guid and row.sender_handle and row.occurred_at
    ext_id = external_message_id(row.guid)
    payload: dict[str, Any] = {
        "sender": {
            "external_contact_id": f"{PLATFORM}:{normalize_handle(row.sender_handle)}",
            # Contacts access would be another TCC grant; not requested in the spike.
            "display_name": None,
        },
        "text": clean_text(row.text),
    }
    if row.attachment_count:
        payload["attachment_count"] = row.attachment_count
    return {
        "schema_version": "connector_event.v1",
        "event_id": event_id(scope.user_id, "message.received", ext_id),
        "type": "message.received",
        "platform": PLATFORM,
        "connection_id": scope.connection_id,
        "user_id": scope.user_id,
        "external_message_id": ext_id,
        "external_thread_id": external_thread_id(row.chat_guid),
        "occurred_at": to_contract_timestamp(row.occurred_at),
        "observed_at": to_contract_timestamp(observed_at or datetime.now(UTC)),
        "payload": payload,
    }


def send_acknowledged_envelope(
    *,
    scope: Scope,
    sent_guid: str,
    chat_guid: str,
    outbound_message_id: str,
    idempotency_key: str,
    occurred_at: datetime,
) -> dict[str, Any]:
    ext_id = external_message_id(sent_guid)
    return {
        "schema_version": "connector_event.v1",
        "event_id": event_id(scope.user_id, "message.send_acknowledged", idempotency_key),
        "type": "message.send_acknowledged",
        "platform": PLATFORM,
        "connection_id": scope.connection_id,
        "user_id": scope.user_id,
        "external_message_id": ext_id,
        "external_thread_id": external_thread_id(chat_guid),
        "occurred_at": to_contract_timestamp(occurred_at),
        "observed_at": to_contract_timestamp(datetime.now(UTC)),
        "payload": {"outbound_message_id": outbound_message_id, "idempotency_key": idempotency_key},
    }


def send_failed_envelope(
    *,
    scope: Scope,
    chat_guid: str | None,
    outbound_message_id: str,
    idempotency_key: str,
    code: str,
    retryable: bool,
    detail: str | None,
    occurred_at: datetime,
) -> dict[str, Any]:
    return {
        "schema_version": "connector_event.v1",
        "event_id": event_id(scope.user_id, "message.send_failed", idempotency_key),
        "type": "message.send_failed",
        "platform": PLATFORM,
        "connection_id": scope.connection_id,
        "user_id": scope.user_id,
        "external_message_id": None,
        "external_thread_id": external_thread_id(chat_guid) if chat_guid else None,
        "occurred_at": to_contract_timestamp(occurred_at),
        "observed_at": to_contract_timestamp(datetime.now(UTC)),
        "payload": {
            "outbound_message_id": outbound_message_id,
            "idempotency_key": idempotency_key,
            "failure": {"code": code, "retryable": retryable, "detail": detail},
        },
    }


def delivery_receipt_envelope(
    *, scope: Scope, sent_guid: str, chat_guid: str, outbound_message_id: str, occurred_at: datetime
) -> dict[str, Any]:
    ext_id = external_message_id(sent_guid)
    return {
        "schema_version": "connector_event.v1",
        "event_id": event_id(scope.user_id, "message.delivery_receipt", ext_id),
        "type": "message.delivery_receipt",
        "platform": PLATFORM,
        "connection_id": scope.connection_id,
        "user_id": scope.user_id,
        "external_message_id": ext_id,
        "external_thread_id": external_thread_id(chat_guid),
        "occurred_at": to_contract_timestamp(occurred_at),
        "observed_at": to_contract_timestamp(datetime.now(UTC)),
        "payload": {"outbound_message_id": outbound_message_id},
    }
