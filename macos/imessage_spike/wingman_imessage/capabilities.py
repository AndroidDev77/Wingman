"""The ``connector_capabilities.v1`` document the companion would claim when pairing.

Nothing is VERIFIED: no Mac was available (WING-020). Per ADR 0007 the server would
store a client ``VERIFIED`` as ``UNVERIFIED`` anyway.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from . import CONNECTOR_VERSION, PLATFORM
from .timeconv import to_contract_timestamp


def capabilities_document(
    assessed_at: datetime, *, full_disk_access: bool | None = None, automation_granted: bool | None = None
) -> dict[str, Any]:
    read: dict[str, Any] = {
        "status": "UNVERIFIED",
        "notes": "Reads ~/Library/Messages/chat.db read-only; needs Full Disk Access. Not tested on a Mac (WING-020).",
    }
    send: dict[str, Any] = {
        "status": "UNVERIFIED",
        "reports_delivery_receipts": False,
        "notes": "Messages AppleScript 'send' into an existing chat; needs Automation consent for Messages. "
        "Ack only after the sent row appears in chat.db. Not tested on a Mac (WING-020).",
    }
    if full_disk_access is False:
        read = {"status": "UNSUPPORTED", "notes": "Full Disk Access not granted."}
    if automation_granted is False:
        send = {"status": "UNSUPPORTED", "notes": "Automation permission for Messages denied."}
    return {
        "schema_version": "connector_capabilities.v1",
        "platform": PLATFORM,
        "connector_version": CONNECTOR_VERSION,
        "capabilities": {
            "read": read,
            "draft": {
                "status": "UNSUPPORTED",
                "notes": "Messages has no scriptable compose field; GUI scripting is out of scope.",
            },
            "send": send,
            "swipe": {"status": "UNSUPPORTED"},
            "calendar_write": {"status": "UNSUPPORTED"},
        },
        "assessed_at": to_contract_timestamp(assessed_at),
    }
