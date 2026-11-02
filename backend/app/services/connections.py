"""Connection pairing, unpairing and capability trust (contract 1.2.0, ADR 0007, I-017).

**Capabilities sent by a client are a claim, never evidence** (ADR 0005). Every path
that stores a capability document that came from a client (``createConnection``, a
``connection.capabilities_changed`` event) goes through :func:`trust_claimed_capabilities`:

* claimed ``UNSUPPORTED`` / ``UNVERIFIED``: stored as claimed (a device may always
  *lower* what it can do), with any client ``evidence``/``verified_at`` dropped;
* claimed ``VERIFIED``: stored as ``UNVERIFIED`` (with a note), unless the server already
  holds ``VERIFIED`` for that capability *for the same* ``connector_version``, which is
  kept unchanged (the client cannot raise it, only keep what the server recorded).

The only way to get ``VERIFIED`` on a real platform is
:meth:`ConnectionService.record_verification`: an operator-only, server-side call (no
HTTP route) that records a reference to a reproduced device run (the G5 device
checklist, ``docs/integrations/android_sms.md``). The backend ``mock`` connector is
created with the simulator's own document (``VERIFIED`` against the deterministic
simulator, ADR 0005), and only when ``WINGMAN_ENV != prod``.

**Unpair** (``unpairConnection``) is effective before it returns, in one transaction,
with the global lock order (user -> approval -> instruction -> message): the connection
becomes ``disconnected`` with every capability ``UNSUPPORTED``; pending approvals for its
conversations, its unclaimed (``pending``) outbox instructions and its ``queued``
outbound messages are canceled (reason ``connection_unavailable``) and audited. A
message a device already leased (``sending``) cannot be recalled; its outcome is still
accepted (ADR 0006).

**Contract 1.3.0 (ADR 0008).** Dating and iMessage platforms pair as device-side
connections with a ``transport``. ``unofficial_api`` (D-021) makes the connection
*experimental*: pairing needs the user's recorded ban-risk consent
(``experimental_consent_required`` otherwise), the policy treats its ``send`` as
unverified and swipe execution is refused until an operator records a live test
(:meth:`ConnectionService.record_experimental_verification`, no HTTP route), and
:meth:`ConnectionService.revoke_consent` works exactly like unpair. No platform
credential or session token is ever accepted or stored here.
"""

import argparse
import logging
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.connectors.capabilities import (
    CapabilityName,
    ConnectorCapabilities,
    VerificationStatus,
)
from app.connectors.mock import default_mock_capabilities
from app.db.base import utc_now
from app.db.enums import (
    Actor,
    AgentActionType,
    CapabilityVerification,
    ConnectionStatus,
    ConnectionTransport,
    DeliveryState,
)
from app.db.errors import NotFoundError
from app.db.models import PlatformConnection
from app.db.repositories import (
    AgentActionRepository,
    ApprovalRepository,
    AssistedDeliveryRepository,
    ConnectionRepository,
    ConversationRepository,
    MessageRepository,
    OutboxRepository,
    UserRepository,
)
from app.db.repositories.connections import CAPABILITY_NAMES, OPTIONAL_CAPABILITY_NAMES
from app.db.scope import UserScope

__all__ = [
    "ASSISTED_PLATFORMS",
    "CLIENT_VERIFIED_NOTE",
    "DEVICE_PAIRABLE_PLATFORMS",
    "DATING_PLATFORMS",
    "ExperimentalConsentInput",
    "experimental_unverified",
    "swipe_gate_failure",
    "MOCK_PLATFORM",
    "UNAVAILABLE_REASON",
    "ConnectionService",
    "PairingError",
    "SwipeGateClosed",
    "PairResult",
    "UnpairResult",
    "trust_claimed_capabilities",
    "unpaired_capabilities",
]

logger = logging.getLogger(__name__)

MOCK_PLATFORM = "mock"
DATING_PLATFORMS = frozenset({"tinder", "bumble", "hinge"})
# Contract 1.3.0: platforms whose drafts can be delivered by hand when `send` is
# UNSUPPORTED (assisted delivery, ADR 0008).
ASSISTED_PLATFORMS = DATING_PLATFORMS | {"imessage"}
DEVICE_PAIRABLE_PLATFORMS = frozenset({"sms", "android_notification"}) | ASSISTED_PLATFORMS
# Which transports each platform may pair with (contract 1.3.0 ConnectionCreateRequest).
ALLOWED_TRANSPORTS: dict[str, frozenset[ConnectionTransport]] = {
    "mock": frozenset({ConnectionTransport.SIMULATOR}),
    "sms": frozenset({ConnectionTransport.SMS_PROVIDER}),
    "android_notification": frozenset({ConnectionTransport.NOTIFICATION}),
    "imessage": frozenset({ConnectionTransport.COMPANION}),
    **{
        p: frozenset({ConnectionTransport.NOTIFICATION, ConnectionTransport.UNOFFICIAL_API})
        for p in DATING_PLATFORMS
    },
}
UNAVAILABLE_REASON = "connection_unavailable"
CLIENT_VERIFIED_NOTE = (
    "Claimed VERIFIED by the client; stored as UNVERIFIED until server-side evidence "
    "of a reproduced device run is recorded (ADR 0005, ADR 0007)."
)
UNPAIRED_NOTE = "Connection unpaired (ADR 0007)."


# --------------------------------------------------------------------------- trust


def trust_claimed_capabilities(
    claimed: ConnectorCapabilities, current: dict[str, Any] | None = None
) -> dict[str, Any]:
    """The ``connector_capabilities.v1`` document the server stores for a client claim.

    Never returns ``VERIFIED`` for a capability unless ``current`` (the server's stored
    document) already has it ``VERIFIED`` for the same ``connector_version``.
    """
    doc = claimed.to_contract_dict()
    same_version = (
        current is not None and current.get("connector_version") == doc["connector_version"]
    )
    current_caps: dict[str, Any] = (current or {}).get("capabilities") or {}
    out: dict[str, Any] = {}
    present = [n for n in OPTIONAL_CAPABILITY_NAMES if n in doc["capabilities"]]
    for name in (*CAPABILITY_NAMES, *present):
        entry: dict[str, Any] = dict(doc["capabilities"][name])
        status = entry["status"]
        if status == VerificationStatus.VERIFIED.value:
            held = current_caps.get(name)
            if same_version and isinstance(held, dict) and held.get("status") == "VERIFIED":
                out[name] = dict(held)
                continue
            entry = {"status": VerificationStatus.UNVERIFIED.value, "notes": CLIENT_VERIFIED_NOTE}
            if "reports_delivery_receipts" in doc["capabilities"][name]:
                entry["reports_delivery_receipts"] = doc["capabilities"][name][
                    "reports_delivery_receipts"
                ]
        else:
            entry.pop("evidence", None)
            entry.pop("verified_at", None)
        out[name] = entry
    doc["capabilities"] = out
    return doc


def unpaired_capabilities(current: dict[str, Any]) -> dict[str, Any]:
    """Every capability ``UNSUPPORTED``: nothing can be sent or read any more."""
    doc = dict(current)
    doc["capabilities"] = {
        name: {"status": "UNSUPPORTED", "notes": UNPAIRED_NOTE} for name in CAPABILITY_NAMES
    }
    if "profile_read" in (current.get("capabilities") or {}):
        doc["capabilities"]["profile_read"] = {"status": "UNSUPPORTED", "notes": UNPAIRED_NOTE}
    return doc


def experimental_unverified(connection: PlatformConnection) -> bool:
    """An experimental (unofficial API) connection without an operator-recorded live
    test: approval-only and never swipes, whatever its capability document says."""
    return connection.experimental and connection.experimental_verified_at is None


def swipe_gate_failure(connection: PlatformConnection) -> tuple[str, str] | None:
    """The connection-level part of the swipe execution gate (ADR 0008), shared by
    ``putConnectionSettings`` and ``requestProfileActionExecution``. ``None`` = passes;
    otherwise ``(pointer, message)`` naming the failed check."""
    if connection.unpaired_at is not None or connection.status is not ConnectionStatus.CONNECTED:
        return "/connection_id", "connection is not paired and connected"
    if connection.swipe_capability is not CapabilityVerification.VERIFIED:
        return "/connection_id", "swipe capability is not VERIFIED"
    if experimental_unverified(connection):
        return "/connection_id", "experimental connection is not operator-verified"
    return None


# --------------------------------------------------------------------------- service


@dataclass(frozen=True)
class ExperimentalConsentInput:
    """The user's ban-risk consent as received (contract ``ExperimentalConsentInput``)."""

    platform: str
    consent_text_version: str
    accepted_at: datetime


class PairingError(Exception):
    """A rejected pairing request: ``422`` (``validation_error`` unless ``code`` says
    otherwise, e.g. ``experimental_consent_required``) at ``pointer``."""

    def __init__(
        self, detail: str, *, pointer: str, message: str, code: str = "validation_error"
    ) -> None:
        super().__init__(detail)
        self.detail = detail
        self.pointer = pointer
        self.message = message
        self.code = code


class SwipeGateClosed(Exception):  # noqa: N818 - maps to the contract error code
    """The swipe execution gate refused (``422 swipe_execution_disabled``, ADR 0008)."""

    def __init__(self, pointer: str, message: str) -> None:
        super().__init__(message)
        self.pointer = pointer
        self.message = message


@dataclass(frozen=True)
class PairResult:
    connection: PlatformConnection
    created: bool


@dataclass(frozen=True)
class UnpairResult:
    connection: PlatformConnection
    canceled_message_count: int
    canceled_instruction_count: int
    canceled_approval_count: int
    in_flight_message_count: int


class ConnectionService:
    def __init__(self, *, env: str, now: Callable[[], datetime] = utc_now) -> None:
        self.env = env
        self._now = now

    async def pair(
        self,
        session: AsyncSession,
        scope: UserScope,
        *,
        platform: str,
        display_name: str,
        device_install_id: uuid.UUID | None,
        capabilities: ConnectorCapabilities | None,
        transport: ConnectionTransport | None = None,
        experimental: bool | None = None,
        consent: ExperimentalConsentInput | None = None,
    ) -> PairResult:
        """``createConnection``. Capabilities are stored per :func:`trust_claimed_capabilities`.

        Contract 1.3.0: ``transport`` (defaulted for single-transport platforms) and, for
        ``unofficial_api``, the experimental flag and the user's consent (ADR 0008).
        """
        repo = ConnectionRepository(session, scope)
        now = self._now()
        chosen = self._check_transport(platform, transport, experimental, consent)
        if platform == MOCK_PLATFORM:
            if self.env == "prod":
                raise PairingError(
                    "the mock platform cannot be paired in production",
                    pointer="/platform",
                    message="'mock' is not available in prod",
                )
            if device_install_id is not None or capabilities is not None:
                raise PairingError(
                    "mock takes neither device_install_id nor capabilities",
                    pointer="/capabilities" if capabilities is not None else "/device_install_id",
                    message="must be absent for platform 'mock'",
                )
            stored = default_mock_capabilities().to_contract_dict()
        elif platform in DEVICE_PAIRABLE_PLATFORMS:
            if device_install_id is None:
                raise PairingError(
                    "device-side platforms need device_install_id",
                    pointer="/device_install_id",
                    message="required",
                )
            if capabilities is None:
                raise PairingError(
                    "device-side platforms need capabilities",
                    pointer="/capabilities",
                    message="required",
                )
            if capabilities.platform != platform:
                raise PairingError(
                    "capabilities platform does not match",
                    pointer="/capabilities/platform",
                    message="must equal platform",
                )
            # Serialize concurrent pairings of one install (the unique index backs this).
            await UserRepository(session).lock(scope)
            existing = await repo.paired_for_install(platform, device_install_id)
            if existing is not None:
                if existing.transport is not chosen:
                    raise PairingError(
                        "this install already has a paired connection for the platform with"
                        " another transport; unpair it first",
                        pointer="/transport",
                        message=f"already paired with transport '{existing.transport.value}'",
                    )
                return PairResult(existing, created=False)
            stored = trust_claimed_capabilities(capabilities)
        else:  # pragma: no cover - the request schema enumerates platforms
            raise PairingError(
                "platform is not pairable", pointer="/platform", message="unsupported"
            )
        is_experimental = chosen is ConnectionTransport.UNOFFICIAL_API
        connection = await repo.create(
            platform=platform,
            display_name=display_name,
            capabilities=stored,
            device_install_id=device_install_id,
            paired_at=now,
            transport=chosen,
            consent_text_version=consent.consent_text_version if consent else None,
            consent_accepted_at=consent.accepted_at if consent else None,
        )
        audit = AgentActionRepository(session, scope)
        if is_experimental and consent is not None:
            await audit.record(
                type=AgentActionType.EXPERIMENTAL_CONSENT_RECORDED,
                actor=Actor.USER,
                occurred_at=now,
                details={
                    "connection_id": str(connection.id),
                    "platform": platform,
                    "consent_text_version": consent.consent_text_version,
                    "accepted_at": consent.accepted_at.isoformat().replace("+00:00", "Z"),
                },
            )
        await audit.record(
            type=AgentActionType.CONNECTION_PAIRED,
            actor=Actor.USER,
            occurred_at=now,
            details={
                "connection_id": str(connection.id),
                "platform": platform,
                "transport": chosen.value,
                "experimental": is_experimental,
                "device_side": device_install_id is not None,
                "send_capability": connection.send_capability.value,
                "client_claimed_verified": sorted(
                    name
                    for name in CAPABILITY_NAMES
                    if capabilities is not None
                    and capabilities.status(CapabilityName(name)) is VerificationStatus.VERIFIED
                ),
            },
        )
        return PairResult(connection, created=True)

    @staticmethod
    def _check_transport(
        platform: str,
        transport: ConnectionTransport | None,
        experimental: bool | None,
        consent: ExperimentalConsentInput | None,
    ) -> ConnectionTransport:
        allowed = ALLOWED_TRANSPORTS.get(platform)
        if allowed is None:  # pragma: no cover - the request schema enumerates platforms
            raise PairingError(
                "platform is not pairable", pointer="/platform", message="unsupported"
            )
        if transport is None:
            if len(allowed) != 1:
                raise PairingError(
                    "this platform needs a transport",
                    pointer="/transport",
                    message="required: notification or unofficial_api",
                )
            (transport,) = allowed
        if transport not in allowed:
            raise PairingError(
                "transport not available for this platform",
                pointer="/transport",
                message=f"must be one of {sorted(t.value for t in allowed)}",
            )
        is_experimental = transport is ConnectionTransport.UNOFFICIAL_API
        if bool(experimental) is not is_experimental:
            raise PairingError(
                "experimental must be true exactly for transport unofficial_api",
                pointer="/experimental",
                message="must be true" if is_experimental else "must be false or absent",
            )
        if not is_experimental:
            if consent is not None:
                raise PairingError(
                    "an experimental consent is only for transport unofficial_api",
                    pointer="/experimental_consent",
                    message="must be absent",
                )
            return transport
        if consent is None:
            raise PairingError(
                "pairing an unofficial-API connector needs the user's recorded ban-risk consent",
                pointer="/experimental_consent",
                message="required for transport unofficial_api",
                code="experimental_consent_required",
            )
        if consent.platform != platform:
            raise PairingError(
                "the consent was given for another platform",
                pointer="/experimental_consent/platform",
                message="must equal platform",
            )
        return transport

    async def revoke_consent(
        self, session: AsyncSession, scope: UserScope, connection_id: uuid.UUID
    ) -> UnpairResult:
        """``revokeExperimentalConsent``: unpair (ADR 0007) plus the consent revocation
        audit. Only for experimental connections; idempotent."""
        connection = await ConnectionRepository(session, scope).require(connection_id)
        if not connection.experimental:
            raise PairingError(
                "only an experimental connection has a consent to revoke",
                pointer="/connection_id",
                message="connection is not experimental",
            )
        was_paired = connection.unpaired_at is None
        result = await self.unpair(session, scope, connection_id)
        if was_paired:
            await AgentActionRepository(session, scope).record(
                type=AgentActionType.EXPERIMENTAL_CONSENT_REVOKED,
                actor=Actor.USER,
                occurred_at=self._now(),
                reason_code=UNAVAILABLE_REASON,
                details={
                    "connection_id": str(connection_id),
                    "platform": result.connection.platform,
                    "canceled_messages": result.canceled_message_count,
                    "canceled_instructions": result.canceled_instruction_count,
                    "canceled_approvals": result.canceled_approval_count,
                },
            )
        return result

    async def set_auto_swipe(
        self, session: AsyncSession, scope: UserScope, connection_id: uuid.UUID, enabled: bool
    ) -> PlatformConnection:
        """``putConnectionSettings``. Enabling needs the connection-level swipe gate;
        disabling always works."""
        repo = ConnectionRepository(session, scope)
        await repo.require(connection_id)
        connection = await repo.lock_for_update(connection_id)
        if connection is None:  # pragma: no cover
            raise NotFoundError("connection not found")
        if enabled:
            failure = swipe_gate_failure(connection)
            if failure is not None:
                raise SwipeGateClosed(*failure)
        return await repo.set_auto_swipe(connection_id, enabled)

    async def unpair(
        self, session: AsyncSession, scope: UserScope, connection_id: uuid.UUID
    ) -> UnpairResult:
        """``unpairConnection``: effective before returning. Idempotent on state."""
        repo = ConnectionRepository(session, scope)
        await repo.require(connection_id)
        # Lock order: user first (serializes with policy decisions and device claims).
        if await UserRepository(session).lock(scope) is None:  # pragma: no cover
            raise NotFoundError("user not found")
        connection = await repo.lock_for_update(connection_id)
        if connection is None:  # pragma: no cover - deleted concurrently
            raise NotFoundError("connection not found")
        if connection.unpaired_at is not None:
            return UnpairResult(connection, 0, 0, 0, 0)
        now = self._now()
        connection = await repo.mark_unpaired(
            connection_id, capabilities=unpaired_capabilities(connection.capabilities), at=now
        )
        audit = AgentActionRepository(session, scope)

        approvals = ApprovalRepository(session, scope)
        canceled_approvals = 0
        for conversation in await ConversationRepository(session, scope).for_connection(
            connection_id
        ):
            conversation_id, contact_id = conversation.id, conversation.contact_id
            for approval in await approvals.cancel_pending(at=now, conversation_id=conversation_id):
                canceled_approvals += 1
                await audit.record(
                    type=AgentActionType.APPROVAL_CANCELED,
                    actor=Actor.USER,
                    conversation_id=conversation_id,
                    contact_id=contact_id,
                    approval_id=approval.id,
                    reason_code=UNAVAILABLE_REASON,
                    occurred_at=now,
                    details={"draft_id": str(approval.draft_id), "stage": "unpair"},
                )

        # Contract 1.3.0: pending assisted deliveries go too (counted with approvals).
        for assisted in await AssistedDeliveryRepository(session, scope).cancel_pending(
            at=now, reason=UNAVAILABLE_REASON, connection_id=connection_id
        ):
            canceled_approvals += 1
            await audit.record(
                type=AgentActionType.ASSISTED_DELIVERY_CANCELED,
                actor=Actor.USER,
                conversation_id=assisted.conversation_id,
                contact_id=assisted.contact_id,
                reason_code=UNAVAILABLE_REASON,
                occurred_at=now,
                details={"assisted_delivery_id": str(assisted.id), "stage": "unpair"},
            )

        canceled_instructions = await OutboxRepository(
            session, scope
        ).cancel_pending_for_connection(connection_id, at=now)
        messages = MessageRepository(session, scope)
        canceled_messages = 0
        for message, contact_id in await messages.outbound_for_connection(
            connection_id, states=(DeliveryState.QUEUED,)
        ):
            locked = await messages.lock(message.id)
            if locked is None or locked.delivery_state is not DeliveryState.QUEUED:
                continue  # leased meanwhile: its real outcome is recorded
            await messages.update_delivery(locked.id, delivery_state=DeliveryState.CANCELED)
            canceled_messages += 1
            await audit.record(
                type=AgentActionType.SEND_CANCELED,
                actor=Actor.USER,
                conversation_id=locked.conversation_id,
                contact_id=contact_id,
                message_id=locked.id,
                approval_id=locked.approval_id,
                reason_code=UNAVAILABLE_REASON,
                occurred_at=now,
                details={"origin": locked.origin.value, "stage": "unpair"},
            )
        in_flight = len(
            await messages.outbound_for_connection(connection_id, states=(DeliveryState.SENDING,))
        )
        await audit.record(
            type=AgentActionType.CONNECTION_UNPAIRED,
            actor=Actor.USER,
            occurred_at=now,
            reason_code=UNAVAILABLE_REASON,
            details={
                "connection_id": str(connection_id),
                "platform": connection.platform,
                "canceled_messages": canceled_messages,
                "canceled_instructions": canceled_instructions,
                "canceled_approvals": canceled_approvals,
                "in_flight_messages": in_flight,
            },
        )
        return UnpairResult(
            connection, canceled_messages, canceled_instructions, canceled_approvals, in_flight
        )

    async def record_experimental_verification(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection_id: uuid.UUID,
        *,
        evidence: str,
        verified_at: datetime,
    ) -> PlatformConnection:
        """Operator-only (no HTTP route, D-016, ADR 0008): the owner's live test of an
        experimental connection passed. Lifts the experimental-unverified gate only;
        each capability still needs :meth:`record_verification` to become VERIFIED."""
        if not evidence.strip():
            raise ValueError("evidence is required to record an experimental verification")
        if verified_at.tzinfo is None:
            raise ValueError("verified_at must be timezone-aware")
        repo = ConnectionRepository(session, scope)
        connection = await repo.require(connection_id)
        if not connection.experimental:
            raise ValueError("only an experimental connection needs this verification")
        if connection.unpaired_at is not None:
            raise ValueError("an unpaired connection cannot be verified")
        updated = await repo.mark_experimental_verified(connection_id, at=verified_at)
        logger.warning(
            "experimental connection verified by operator user=%s connection=%s",
            scope.user_id,
            connection_id,
        )
        return updated

    async def record_verification(
        self,
        session: AsyncSession,
        scope: UserScope,
        connection_id: uuid.UUID,
        capability: CapabilityName,
        *,
        evidence: str,
        verified_at: datetime,
    ) -> PlatformConnection:
        """Operator-only (no HTTP route, ADR 0007): mark one capability ``VERIFIED``.

        ``evidence`` must reference a reproduced device run recorded outside the client
        (e.g. a G5 checklist entry in ``docs/integrations/android_sms.md`` or a CI device
        job ID). It is the only way a real platform's capability becomes ``VERIFIED``.
        """
        evidence = evidence.strip()
        if not evidence:
            raise ValueError("evidence is required to mark a capability VERIFIED")
        if verified_at.tzinfo is None:
            raise ValueError("verified_at must be timezone-aware")
        repo = ConnectionRepository(session, scope)
        connection = await repo.require(connection_id)
        if connection.unpaired_at is not None:
            raise ValueError("an unpaired connection cannot be verified")
        doc = dict(connection.capabilities)
        caps = dict(doc["capabilities"])
        entry = dict(caps[capability.value])
        entry.update(
            status="VERIFIED",
            evidence=evidence[:500],
            verified_at=verified_at.isoformat().replace("+00:00", "Z"),
        )
        caps[capability.value] = entry
        doc["capabilities"] = caps
        ConnectorCapabilities.model_validate(doc)  # still a valid v1 document
        updated = await repo.set_capabilities(connection_id, doc)
        logger.warning(
            "capability verified by operator user=%s connection=%s capability=%s",
            scope.user_id,
            connection_id,
            capability.value,
        )
        return updated


def _main() -> None:  # pragma: no cover - thin operator CLI
    """``python -m app.services.connections verify --user-id U --connection-id C
    --capability send --evidence REF``: the operator path that sets VERIFIED."""
    import asyncio

    from app.db.session import dispose_engine, session_scope

    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    verify = sub.add_parser("verify")
    verify.add_argument("--user-id", type=uuid.UUID, required=True)
    verify.add_argument("--connection-id", type=uuid.UUID, required=True)
    verify.add_argument("--capability", choices=[c.value for c in CapabilityName], required=True)
    verify.add_argument("--evidence", required=True)
    exp = sub.add_parser("verify-experimental")
    exp.add_argument("--user-id", type=uuid.UUID, required=True)
    exp.add_argument("--connection-id", type=uuid.UUID, required=True)
    exp.add_argument("--evidence", required=True)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)

    async def run() -> None:
        try:
            async with session_scope() as session:
                service = ConnectionService(env="operator")
                if args.cmd == "verify-experimental":
                    await service.record_experimental_verification(
                        session,
                        UserScope(args.user_id),
                        args.connection_id,
                        evidence=args.evidence,
                        verified_at=utc_now(),
                    )
                    return
                await service.record_verification(
                    session,
                    UserScope(args.user_id),
                    args.connection_id,
                    CapabilityName(args.capability),
                    evidence=args.evidence,
                    verified_at=utc_now(),
                )
        finally:
            await dispose_engine()

    asyncio.run(run())


if __name__ == "__main__":  # pragma: no cover
    _main()
