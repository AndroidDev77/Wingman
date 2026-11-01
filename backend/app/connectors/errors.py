"""Typed connector errors. Callers branch on the class (or its `code`), never on text."""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.connectors.capabilities import CapabilityName


class ConnectorError(Exception):
    """Base class. `code` matches an `ErrorCode` / send failure code in the contract."""

    code: str = "internal_error"


class UnsupportedCapability(ConnectorError):  # noqa: N818 - name fixed by the SDK interface
    """The connector does not support the capability an operation needs.

    Raised before any side effect: nothing is sent, recorded or emitted. Maps to the
    contract's `capability_unsupported` error code / send failure code.
    """

    code = "capability_unsupported"

    def __init__(self, capability: CapabilityName, *, platform: str, operation: str) -> None:
        self.capability = capability
        self.platform = platform
        self.operation = operation
        super().__init__(
            f"Connector capability '{capability.value}' is UNSUPPORTED on platform "
            f"'{platform}' (operation: {operation})."
        )


class IdempotencyKeyConflict(ConnectorError):  # noqa: N818
    """The idempotency key was already used for a different send request."""

    code = "idempotency_key_mismatch"

    def __init__(self, idempotency_key: str) -> None:
        self.idempotency_key = idempotency_key
        super().__init__("Idempotency key was already used for a different send request.")


class InvalidCursor(ConnectorError):  # noqa: N818
    """A pagination cursor was not produced by this connector."""

    code = "validation_error"
