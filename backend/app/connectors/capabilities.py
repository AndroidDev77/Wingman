"""`ConnectorCapabilities`: mirrors `contracts/schemas/connector_capabilities.v1.json`.

The five capabilities are independent (ADR 0005). Nothing here infers one capability
from another; callers check exactly the capability they need.
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Annotated, ClassVar, Literal

from pydantic import Field, StringConstraints, model_validator

from app.connectors._types import ContractModel, PlatformKey, UtcTimestamp
from app.connectors.errors import UnsupportedCapability

CAPABILITIES_SCHEMA_VERSION: Literal["connector_capabilities.v1"] = "connector_capabilities.v1"


class VerificationStatus(StrEnum):
    VERIFIED = "VERIFIED"
    UNVERIFIED = "UNVERIFIED"
    UNSUPPORTED = "UNSUPPORTED"


class CapabilityName(StrEnum):
    READ = "read"
    DRAFT = "draft"
    SEND = "send"
    SWIPE = "swipe"
    CALENDAR_WRITE = "calendar_write"


class Capability(ContractModel):
    """One capability with its verification label (`$defs/capability`)."""

    _omit_if_none: ClassVar[frozenset[str]] = frozenset(
        {"evidence", "verified_at", "notes", "reports_delivery_receipts"}
    )

    status: VerificationStatus
    evidence: Annotated[str, StringConstraints(max_length=500)] | None = None
    verified_at: UtcTimestamp | None = None
    notes: Annotated[str, StringConstraints(max_length=1000)] | None = None
    reports_delivery_receipts: bool | None = None

    @model_validator(mode="after")
    def _verified_needs_evidence(self) -> Capability:
        if self.status is VerificationStatus.VERIFIED and (
            not self.evidence or self.verified_at is None
        ):
            raise ValueError("a VERIFIED capability requires non-empty evidence and verified_at")
        return self

    @property
    def available(self) -> bool:
        """True unless UNSUPPORTED. UNVERIFIED is available but not autopilot-eligible."""
        return self.status is not VerificationStatus.UNSUPPORTED

    @classmethod
    def unsupported(cls, notes: str | None = None) -> Capability:
        return cls(status=VerificationStatus.UNSUPPORTED, notes=notes)

    @classmethod
    def unverified(
        cls, notes: str | None = None, *, reports_delivery_receipts: bool | None = None
    ) -> Capability:
        return cls(
            status=VerificationStatus.UNVERIFIED,
            notes=notes,
            reports_delivery_receipts=reports_delivery_receipts,
        )

    @classmethod
    def verified(
        cls,
        *,
        evidence: str,
        verified_at: datetime,
        notes: str | None = None,
        reports_delivery_receipts: bool | None = None,
    ) -> Capability:
        return cls(
            status=VerificationStatus.VERIFIED,
            evidence=evidence,
            verified_at=verified_at,
            notes=notes,
            reports_delivery_receipts=reports_delivery_receipts,
        )


class CapabilitySet(ContractModel):
    """All five capabilities, each stated explicitly (none may be omitted)."""

    read: Capability
    draft: Capability
    send: Capability
    swipe: Capability
    calendar_write: Capability

    def get(self, name: CapabilityName) -> Capability:
        capability: Capability = getattr(self, name.value)
        return capability


class ConnectorCapabilities(ContractModel):
    """What one connector instance can do (`connector_capabilities.v1`)."""

    schema_version: Literal["connector_capabilities.v1"] = CAPABILITIES_SCHEMA_VERSION
    platform: PlatformKey
    connector_version: Annotated[str, Field(min_length=1, max_length=64)]
    capabilities: CapabilitySet
    assessed_at: UtcTimestamp

    def status(self, name: CapabilityName) -> VerificationStatus:
        return self.capabilities.get(name).status

    def supports(self, name: CapabilityName) -> bool:
        """True if the capability is VERIFIED or UNVERIFIED."""
        return self.capabilities.get(name).available

    def require(self, name: CapabilityName, *, operation: str) -> Capability:
        """Return the capability, or raise `UnsupportedCapability` if it is UNSUPPORTED.

        This only answers "can the connector do it at all". Whether an UNVERIFIED send
        may be used without approval is policy, decided by the automation controller.
        """
        capability = self.capabilities.get(name)
        if not capability.available:
            raise UnsupportedCapability(name, platform=self.platform, operation=operation)
        return capability
