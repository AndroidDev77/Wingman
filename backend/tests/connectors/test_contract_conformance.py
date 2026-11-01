"""Our models serialize to, and parse from, the frozen v1 contract schemas."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from app.connectors import (
    Capability,
    CapabilitySet,
    ConnectorCapabilities,
    ConnectorEvent,
    ConnectorEventType,
    MockConnector,
    SendFailureCode,
    SendRequest,
    default_mock_capabilities,
)
from tests.connectors.conftest import EXAMPLES, load_example

CAPABILITY_EXAMPLES = [
    "capabilities.mock.json",
    "capabilities.sms.json",
    "capabilities.android_notification.json",
]
EVENT_EXAMPLES = sorted(p.name for p in EXAMPLES.glob("event.*.json"))
INVALID_EVENT_EXAMPLES = sorted(p.name for p in (EXAMPLES / "invalid").glob("event.*.json"))
INVALID_CAPABILITY_EXAMPLES = sorted(
    p.name for p in (EXAMPLES / "invalid").glob("capabilities.*.json")
)


# ------------------------------------------------------------------ capabilities


def test_default_mock_capabilities_validate_against_schema(
    capabilities_validator: Draft202012Validator,
) -> None:
    doc = json.loads(default_mock_capabilities().model_dump_json())
    capabilities_validator.validate(doc)
    assert doc["platform"] == "mock"
    assert set(doc["capabilities"]) == {"read", "draft", "send", "swipe", "calendar_write"}


def test_every_label_combination_serializes_valid(
    capabilities_validator: Draft202012Validator,
) -> None:
    when = datetime(2026, 11, 1, 12, 30, 0, 123456, tzinfo=UTC)
    verified = Capability.verified(evidence="device-log-1", verified_at=when)
    unverified = Capability.unverified("pending device test", reports_delivery_receipts=False)
    unsupported = Capability.unsupported()
    caps = ConnectorCapabilities(
        platform="sms",
        connector_version="0.1.0",
        capabilities=CapabilitySet(
            read=verified,
            draft=unsupported,
            send=unverified,
            swipe=unsupported,
            calendar_write=unsupported,
        ),
        assessed_at=when,
    )
    doc = caps.to_contract_dict()
    capabilities_validator.validate(doc)
    # Optional, non-nullable fields are omitted rather than serialized as null.
    assert doc["capabilities"]["draft"] == {"status": "UNSUPPORTED"}
    assert doc["assessed_at"] == "2026-11-01T12:30:00.123456Z"


@pytest.mark.parametrize("name", CAPABILITY_EXAMPLES)
def test_contract_capability_examples_round_trip(
    name: str, capabilities_validator: Draft202012Validator
) -> None:
    raw = load_example(name)
    model = ConnectorCapabilities.model_validate(raw)
    dumped = model.to_contract_dict()
    capabilities_validator.validate(dumped)
    assert dumped == raw


@pytest.mark.parametrize("name", INVALID_CAPABILITY_EXAMPLES)
def test_invalid_capability_examples_rejected_by_model(name: str) -> None:
    raw = json.loads((EXAMPLES / "invalid" / name).read_text())
    raw.pop("why_invalid", None)
    with pytest.raises(ValidationError):
        ConnectorCapabilities.model_validate(raw)


def test_verified_requires_evidence_and_timestamp() -> None:
    with pytest.raises(ValidationError):
        Capability(status="VERIFIED")  # type: ignore[arg-type]
    with pytest.raises(ValidationError):
        Capability(status="VERIFIED", evidence="x")  # type: ignore[arg-type]


def test_offset_timestamp_rejected() -> None:
    raw = load_example("capabilities.sms.json")
    raw["assessed_at"] = "2026-10-31T18:00:00+00:00"
    with pytest.raises(ValidationError):
        ConnectorCapabilities.model_validate(raw)


# ------------------------------------------------------------------ events


@pytest.mark.parametrize("name", EVENT_EXAMPLES)
def test_contract_event_examples_parse_and_round_trip(
    name: str, event_validator: Draft202012Validator
) -> None:
    raw = load_example(name)
    event = ConnectorEvent.model_validate(raw)
    assert event.type == ConnectorEventType(raw["type"])
    dumped = event.to_contract_dict()
    event_validator.validate(dumped)
    assert dumped == raw


@pytest.mark.parametrize("name", INVALID_EVENT_EXAMPLES)
def test_invalid_event_examples_rejected_by_model(name: str) -> None:
    raw = json.loads((EXAMPLES / "invalid" / name).read_text())
    raw.pop("why_invalid", None)
    with pytest.raises(ValidationError):
        ConnectorEvent.model_validate(raw)


def test_mock_type_requires_mock_block() -> None:
    raw = load_example("event.mock_inbound_message_injected.json")
    del raw["payload"]["mock"]
    with pytest.raises(ValidationError):
        ConnectorEvent.model_validate(raw)


def test_live_type_rejects_mock_block() -> None:
    raw = load_example("event.sms_message_received.json")
    raw["payload"]["mock"] = {"scenario_id": "s", "sequence": 0}
    with pytest.raises(ValidationError):
        ConnectorEvent.model_validate(raw)


def test_connection_event_rejects_external_message_id() -> None:
    raw = load_example("event.connection_capabilities_changed.json")
    raw["external_message_id"] = "x"
    with pytest.raises(ValidationError):
        ConnectorEvent.model_validate(raw)


def test_payload_shape_must_match_type() -> None:
    raw = load_example("event.mock_outbound_send_acknowledged.json")
    raw["type"] = "mock.inbound_message_injected"
    with pytest.raises(ValidationError):
        ConnectorEvent.model_validate(raw)


def test_unknown_fields_rejected() -> None:
    raw: dict[str, Any] = load_example("event.sms_message_received.json")
    raw["surprise"] = True
    with pytest.raises(ValidationError):
        ConnectorEvent.model_validate(raw)


def test_inbound_text_is_redacted_in_repr() -> None:
    event = ConnectorEvent.model_validate(load_example("event.mock_inbound_message_injected.json"))
    assert "Lisbon" not in repr(event)
    assert "Lisbon" not in str(event)


async def test_every_event_the_mock_emits_validates(
    mock: MockConnector, event_validator: Draft202012Validator
) -> None:
    mock_with_receipts = mock
    await mock_with_receipts.inject_inbound(
        external_thread_id="mock-thread-1", sender_id="sam", text="hi", display_name="Sam"
    )
    ok = await mock_with_receipts.send_message(
        SendRequest(
            outbound_message_id=UUID("0192f3a0-0000-7000-8000-0000000000d3"),
            idempotency_key="send:0192f3a0-0000-7000-8000-0000000000d3",
            external_thread_id="mock-thread-1",
            text="hello",
        )
    )
    await mock_with_receipts.simulate_delivery_receipt(ok.outbound_message_id)
    mock_with_receipts.inject_send_failures(1, code=SendFailureCode.TIMEOUT)
    await mock_with_receipts.send_message(
        SendRequest(
            outbound_message_id=UUID("0192f3a0-0000-7000-8000-0000000000d4"),
            idempotency_key="send:0192f3a0-0000-7000-8000-0000000000d4",
            external_thread_id="mock-thread-1",
            text="again",
        )
    )

    types = [e.type for e in mock_with_receipts.events]
    assert types == [
        ConnectorEventType.MOCK_INBOUND_MESSAGE_INJECTED,
        ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED,
        ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED,
        ConnectorEventType.MOCK_OUTBOUND_SEND_FAILED,
    ]
    for event in mock_with_receipts.events:
        doc = json.loads(event.model_dump_json())
        event_validator.validate(doc)
        assert doc["platform"] == "mock"
        assert doc["type"].startswith("mock.")
        # And it parses back to an equal model.
        assert ConnectorEvent.model_validate(doc) == event
