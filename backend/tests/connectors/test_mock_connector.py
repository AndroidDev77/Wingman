"""Behaviour of the MockConnector simulator and the capability guard."""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest

from app.connectors import (
    BaseConnector,
    Capability,
    CapabilityName,
    CapabilitySet,
    Connector,
    ConnectorCapabilities,
    ConnectorEvent,
    ConnectorEventType,
    DedupOutcome,
    EventScopeMismatch,
    IdempotencyKeyConflict,
    InboundDeduplicator,
    InMemoryDedupStore,
    InvalidCursor,
    MessageDirection,
    MockConnector,
    SendFailureCode,
    SendRequest,
    SendStatus,
    SimulatedClock,
    SwipeDecision,
    SwipeRequest,
    UnsupportedCapability,
    VerificationStatus,
    default_mock_capabilities,
)
from tests.connectors.conftest import CONNECTION_ID, OTHER_USER_ID, USER_ID, load_example

MSG_ID = UUID("0192f3a0-0000-7000-8000-0000000000d3")


def _send(
    key: str = "send:0192f3a0-0000-7000-8000-0000000000d3",
    *,
    message_id: UUID = MSG_ID,
    thread: str = "mock-thread-1",
    text: str = "Welcome back! How was it?",
) -> SendRequest:
    return SendRequest(
        outbound_message_id=message_id, idempotency_key=key, external_thread_id=thread, text=text
    )


async def _with_thread(mock: MockConnector, thread: str = "mock-thread-1") -> None:
    await mock.inject_inbound(external_thread_id=thread, sender_id="mock-contact-sam", text="hi")


# ------------------------------------------------------------------ protocol / capabilities


def test_mock_satisfies_connector_protocol(mock: MockConnector) -> None:
    assert isinstance(mock, Connector)
    assert mock.platform == "mock"


def test_mock_advertises_only_the_mock_platform(mock: MockConnector) -> None:
    caps = mock.capabilities()
    assert caps.platform == "mock"
    assert caps.status(CapabilityName.SEND) is VerificationStatus.VERIFIED
    assert caps.status(CapabilityName.READ) is VerificationStatus.VERIFIED
    for name in (CapabilityName.DRAFT, CapabilityName.SWIPE, CapabilityName.CALENDAR_WRITE):
        assert caps.status(name) is VerificationStatus.UNSUPPORTED
    assert "not a real platform" in (caps.capabilities.send.notes or "")


def test_mock_refuses_to_claim_a_real_platform() -> None:
    sms_caps = default_mock_capabilities().model_copy(update={"platform": "sms"})
    with pytest.raises(ValueError, match="only advertise platform 'mock'"):
        MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID, capabilities=sms_caps)
    mock = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID)
    with pytest.raises(ValueError):
        mock.set_capabilities(sms_caps)


async def test_swipe_and_profiles_return_typed_unsupported(mock: MockConnector) -> None:
    with pytest.raises(UnsupportedCapability) as info:
        await mock.swipe(
            SwipeRequest(
                external_profile_id="p1", decision=SwipeDecision.LIKE, idempotency_key="swipe:p1-1"
            )
        )
    assert info.value.capability is CapabilityName.SWIPE
    assert info.value.code == "capability_unsupported"
    assert info.value.platform == "mock"
    with pytest.raises(UnsupportedCapability) as info2:
        await mock.get_profiles()
    assert info2.value.operation == "get_profiles"
    assert mock.events == []


def _caps_with(**overrides: Capability) -> ConnectorCapabilities:
    base = default_mock_capabilities()
    return base.model_copy(update={"capabilities": base.capabilities.model_copy(update=overrides)})


async def test_send_unsupported_is_typed_and_has_no_side_effect(mock: MockConnector) -> None:
    await _with_thread(mock)
    mock.set_capabilities(_caps_with(send=Capability.unsupported()))
    events_before = len(mock.events)
    with pytest.raises(UnsupportedCapability) as info:
        await mock.send_message(_send())
    assert info.value.capability is CapabilityName.SEND
    assert mock.transport_log == []
    assert len(mock.events) == events_before
    assert mock.send_count(_send().idempotency_key) == 0


async def test_read_unsupported_is_typed(mock: MockConnector) -> None:
    mock.set_capabilities(_caps_with(read=Capability.unsupported()))
    with pytest.raises(UnsupportedCapability) as info:
        await mock.list_conversations()
    assert info.value.capability is CapabilityName.READ
    with pytest.raises(UnsupportedCapability):
        await mock.read_messages("mock-thread-1")


async def test_unverified_send_is_still_callable(mock: MockConnector) -> None:
    # Availability is not policy: UNVERIFIED may be used (with approval, decided by WING-011).
    await _with_thread(mock)
    mock.set_capabilities(_caps_with(send=Capability.unverified("demo")))
    result = await mock.send_message(_send())
    assert result.status is SendStatus.ACKNOWLEDGED


async def test_base_connector_optional_ops_default_to_unsupported() -> None:
    class AdvertisesSwipeButDoesNotImplement(BaseConnector):
        def capabilities(self) -> ConnectorCapabilities:
            unsupported = Capability.unsupported()
            return ConnectorCapabilities(
                platform="sample",
                connector_version="0",
                capabilities=CapabilitySet(
                    read=unsupported,
                    draft=unsupported,
                    send=unsupported,
                    swipe=Capability.unverified(),
                    calendar_write=unsupported,
                ),
                assessed_at=datetime(2026, 11, 1, tzinfo=UTC),
            )

        async def _do_list_conversations(self, *, cursor: str | None, limit: int):  # type: ignore[no-untyped-def]
            raise AssertionError

        async def _do_read_messages(
            self, external_thread_id: str, *, cursor: str | None, limit: int
        ):  # type: ignore[no-untyped-def]  # noqa: E501
            raise AssertionError

        async def _do_send_message(self, request: SendRequest):  # type: ignore[no-untyped-def]
            raise AssertionError

    connector = AdvertisesSwipeButDoesNotImplement()
    with pytest.raises(UnsupportedCapability):
        await connector.get_profiles()
    with pytest.raises(UnsupportedCapability):
        await connector.send_message(_send())


# ------------------------------------------------------------------ inbound


async def test_injected_inbound_message_can_be_retrieved(mock: MockConnector) -> None:
    result = await mock.inject_inbound(
        external_thread_id="mock-thread-1",
        sender_id="mock-contact-sam",
        display_name="Sam",
        text="Just got back from Lisbon!",
        external_message_id="mock-msg-0001",
    )
    assert result.outcome is DedupOutcome.ACCEPTED
    assert result.event.type is ConnectorEventType.MOCK_INBOUND_MESSAGE_INJECTED

    convs = await mock.list_conversations()
    assert [c.external_thread_id for c in convs.items] == ["mock-thread-1"]
    assert convs.items[0].participants[0].display_name == "Sam"

    page = await mock.read_messages("mock-thread-1")
    assert len(page.items) == 1
    msg = page.items[0]
    assert msg.external_message_id == "mock-msg-0001"
    assert msg.direction is MessageDirection.INBOUND
    assert msg.text == "Just got back from Lisbon!"
    assert msg.sender is not None and msg.sender.external_contact_id == "mock-contact-sam"
    assert "Lisbon" not in repr(msg)


async def test_injection_is_deterministic() -> None:
    async def run() -> list[str]:
        m = MockConnector(
            user_id=USER_ID, connection_id=CONNECTION_ID, scenario_id="det", clock=SimulatedClock()
        )
        await m.inject_inbound(external_thread_id="t", sender_id="s", text="a")
        await m.inject_inbound(external_thread_id="t", sender_id="s", text="b")
        await m.send_message(_send(thread="t"))
        return [e.model_dump_json() for e in m.events]

    assert await run() == await run()


async def test_duplicate_inbound_is_deduplicated(mock: MockConnector) -> None:
    sub = mock.subscribe()
    first = await mock.inject_inbound(
        external_thread_id="mock-thread-1", sender_id="c", text="hi", external_message_id="m-1"
    )
    again = await mock.inject_inbound(
        external_thread_id="mock-thread-1", sender_id="c", text="hi", external_message_id="m-1"
    )
    assert first.outcome is DedupOutcome.ACCEPTED
    assert again.outcome is DedupOutcome.DUPLICATE_MESSAGE
    assert again.duplicate
    page = await mock.read_messages("mock-thread-1")
    assert len(page.items) == 1
    assert len(mock.events) == 1
    assert sub.pending() == 1


async def test_exact_envelope_replay_is_deduplicated_by_event_id(mock: MockConnector) -> None:
    first = await mock.inject_inbound(external_thread_id="t", sender_id="c", text="hi")
    replay = await mock.ingest(first.event)
    assert replay.outcome is DedupOutcome.DUPLICATE_EVENT
    assert len(mock.events) == 1


async def test_contract_example_event_can_be_ingested_once() -> None:
    event = ConnectorEvent.model_validate(load_example("event.mock_inbound_message_injected.json"))
    mock = MockConnector(user_id=event.user_id, connection_id=event.connection_id)
    assert (await mock.ingest(event)).outcome is DedupOutcome.ACCEPTED
    assert (await mock.ingest(event)).outcome is DedupOutcome.DUPLICATE_EVENT
    page = await mock.read_messages("mock-thread-1")
    assert [m.external_message_id for m in page.items] == ["mock-msg-0001"]


async def test_same_external_id_for_another_user_is_not_a_duplicate() -> None:
    store = InMemoryDedupStore()
    a = MockConnector(
        user_id=USER_ID, connection_id=CONNECTION_ID, deduplicator=InboundDeduplicator(store)
    )
    b = MockConnector(
        user_id=OTHER_USER_ID,
        connection_id=UUID("0192f3a0-0000-7000-8000-0000000000a3"),
        deduplicator=InboundDeduplicator(store),
    )
    r1 = await a.inject_inbound(
        external_thread_id="t", sender_id="c", text="x", external_message_id="same"
    )
    r2 = await b.inject_inbound(
        external_thread_id="t", sender_id="c", text="x", external_message_id="same"
    )
    assert not r1.duplicate and not r2.duplicate
    assert len(store) == 2


async def test_ingest_rejects_event_for_another_user(mock: MockConnector) -> None:
    raw = load_example("event.mock_inbound_message_injected.json")
    raw["user_id"] = str(OTHER_USER_ID)
    with pytest.raises(EventScopeMismatch):
        await mock.ingest(ConnectorEvent.model_validate(raw))
    assert mock.events == []


async def test_subscribe_streams_events_in_order(mock: MockConnector) -> None:
    async with mock.subscribe() as events:
        await _with_thread(mock)
        await mock.send_message(_send())
        got = [await anext(events), await anext(events)]
    assert [e.type for e in got] == [
        ConnectorEventType.MOCK_INBOUND_MESSAGE_INJECTED,
        ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED,
    ]
    assert events.closed
    with pytest.raises(StopAsyncIteration):
        await anext(events)


# ------------------------------------------------------------------ send


async def test_send_is_acknowledged_not_delivered(mock: MockConnector) -> None:
    await _with_thread(mock)
    result = await mock.send_message(_send())
    assert result.status is SendStatus.ACKNOWLEDGED
    assert result.external_message_id is not None
    assert not result.replayed
    assert result.event.type is ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED
    # No receipt until one is explicitly simulated.
    assert ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED not in [e.type for e in mock.events]
    page = await mock.read_messages("mock-thread-1")
    assert page.items[-1].direction is MessageDirection.OUTBOUND
    assert page.items[-1].outbound_message_id == MSG_ID


async def test_exactly_one_send_per_idempotency_key(mock: MockConnector) -> None:
    await _with_thread(mock)
    first = await mock.send_message(_send())
    second = await mock.send_message(_send())
    assert mock.send_count(first.idempotency_key) == 1
    assert len(mock.transport_log) == 1
    assert second.replayed
    assert second.event == first.event
    assert second.external_message_id == first.external_message_id
    acks = [e for e in mock.events if e.type is ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED]
    assert len(acks) == 1


async def test_concurrent_duplicate_sends_transmit_once(mock: MockConnector) -> None:
    await _with_thread(mock)
    results = await asyncio.gather(*(mock.send_message(_send()) for _ in range(25)))
    assert len(mock.transport_log) == 1
    assert sum(1 for r in results if not r.replayed) == 1
    assert len({r.external_message_id for r in results}) == 1
    acks = [e for e in mock.events if e.type is ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED]
    assert len(acks) == 1


async def test_concurrent_distinct_keys_each_send(mock: MockConnector) -> None:
    await _with_thread(mock)
    requests = [
        _send(f"send:key-{i:04d}", message_id=UUID(int=i + 1), text=f"m{i}") for i in range(5)
    ]
    results = await asyncio.gather(*(mock.send_message(r) for r in requests))
    assert len(mock.transport_log) == 5
    assert len({r.external_message_id for r in results}) == 5


async def test_outbound_ids_are_unique_across_connector_instances() -> None:
    """WING-025 regression: the backend stores outbound IDs under a unique
    (user_id, platform, external_message_id). Two simulators for the same user (a second
    mock connection, or the same connection after a process restart) used to number
    their acknowledgements from 1 and collide, leaving the second send stuck."""
    first = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID)
    restarted = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID)
    other = MockConnector(user_id=USER_ID, connection_id=UUID(int=0xA3))
    for connector in (first, restarted, other):
        await _with_thread(connector)
    a = await first.send_message(_send("send:aaaaaaaa:1", message_id=UUID(int=1)))
    b = await restarted.send_message(_send("send:bbbbbbbb:1", message_id=UUID(int=2)))
    c = await other.send_message(_send("send:cccccccc:1", message_id=UUID(int=3)))
    ids = [r.external_message_id for r in (a, b, c)]
    assert None not in ids
    assert len(set(ids)) == 3
    # Stable per attempt key: a replay (and a restarted simulator retrying the same key)
    # reports the same ID, so the stored message never changes its platform ID.
    again = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID)
    await _with_thread(again)
    a2 = await again.send_message(_send("send:aaaaaaaa:1", message_id=UUID(int=1)))
    assert a2.external_message_id == a.external_message_id


async def test_reused_key_with_different_body_conflicts(mock: MockConnector) -> None:
    await _with_thread(mock)
    await mock.send_message(_send())
    with pytest.raises(IdempotencyKeyConflict):
        await mock.send_message(_send(text="something else"))
    assert len(mock.transport_log) == 1


async def test_failure_injection_produces_failed_not_sent(mock: MockConnector) -> None:
    await _with_thread(mock)
    mock.inject_send_failures(1, code=SendFailureCode.TIMEOUT, retryable=True)
    result = await mock.send_message(_send())
    assert result.status is SendStatus.FAILED
    assert not result.acknowledged
    assert result.external_message_id is None
    assert result.failure is not None
    assert result.failure.code is SendFailureCode.TIMEOUT
    assert result.failure.retryable
    assert result.event.type is ConnectorEventType.MOCK_OUTBOUND_SEND_FAILED
    assert result.event.external_message_id is None
    types = [e.type for e in mock.events]
    assert ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED not in types
    # A failed send is not in the thread, and no receipt can be faked for it.
    page = await mock.read_messages("mock-thread-1")
    assert all(m.direction is MessageDirection.INBOUND for m in page.items)
    with pytest.raises(ValueError):
        await mock.simulate_delivery_receipt(MSG_ID)


async def test_failed_key_replays_failure_and_retry_needs_new_key(mock: MockConnector) -> None:
    await _with_thread(mock)
    mock.inject_send_failures(1)
    failed = await mock.send_message(_send("send:msg-d3:1"))
    replay = await mock.send_message(_send("send:msg-d3:1"))
    assert replay.replayed and replay.status is SendStatus.FAILED
    assert len(mock.transport_log) == 1
    retry = await mock.send_message(_send("send:msg-d3:2"))
    assert failed.status is SendStatus.FAILED
    assert retry.status is SendStatus.ACKNOWLEDGED
    assert len(mock.transport_log) == 2


async def test_persistent_failures_until_cleared(mock: MockConnector) -> None:
    await _with_thread(mock)
    mock.inject_send_failures(None, code=SendFailureCode.NOT_CONNECTED, retryable=False)
    r1 = await mock.send_message(_send("send:k-000001", message_id=UUID(int=1)))
    r2 = await mock.send_message(_send("send:k-000002", message_id=UUID(int=2)))
    assert r1.status is SendStatus.FAILED and r2.status is SendStatus.FAILED
    mock.clear_send_failures()
    r3 = await mock.send_message(_send("send:k-000003", message_id=UUID(int=3)))
    assert r3.status is SendStatus.ACKNOWLEDGED


async def test_send_to_unknown_thread_fails(mock: MockConnector) -> None:
    result = await mock.send_message(_send(thread="nope"))
    assert result.status is SendStatus.FAILED
    assert result.failure is not None
    assert result.failure.code is SendFailureCode.REJECTED_BY_PLATFORM


async def test_delivery_receipt_simulation(mock: MockConnector) -> None:
    await _with_thread(mock)
    result = await mock.send_message(_send())
    receipt = await mock.simulate_delivery_receipt(MSG_ID)
    assert receipt.type is ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED
    assert receipt.external_message_id == result.external_message_id
    assert await mock.simulate_delivery_receipt(MSG_ID) == receipt
    receipts = [
        e for e in mock.events if e.type is ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED
    ]
    assert len(receipts) == 1


async def test_auto_delivery_receipts() -> None:
    m = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID, auto_delivery_receipts=True)
    await _with_thread(m)
    await m.send_message(_send())
    assert [e.type for e in m.events][-1] is ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED


async def test_receipts_refused_when_not_advertised(mock: MockConnector) -> None:
    await _with_thread(mock)
    mock.set_capabilities(_caps_with(send=Capability.unverified(reports_delivery_receipts=False)))
    await mock.send_message(_send())
    with pytest.raises(ValueError, match="delivery receipts"):
        await mock.simulate_delivery_receipt(MSG_ID)


# ------------------------------------------------------------------ pagination


async def test_message_pagination(mock: MockConnector) -> None:
    for i in range(7):
        await mock.inject_inbound(external_thread_id="t", sender_id="c", text=f"m{i}")
    page1 = await mock.read_messages("t", limit=3)
    assert page1.next_cursor is not None
    page2 = await mock.read_messages("t", cursor=page1.next_cursor, limit=3)
    page3 = await mock.read_messages("t", cursor=page2.next_cursor, limit=3)
    assert page3.next_cursor is None
    texts = [m.text for p in (page1, page2, page3) for m in p.items]
    assert texts == [f"m{i}" for i in range(7)]
    with pytest.raises(InvalidCursor):
        await mock.read_messages("other", cursor=page1.next_cursor)
    with pytest.raises(InvalidCursor):
        await mock.read_messages("t", cursor="!!garbage!!")


# --------------------------------------------------------------------------- clock (I-021)


async def test_default_clock_is_wall_clock_utc() -> None:
    """No fixed simulated date: events (and so ``sent_at``/``delivered_at``) are stamped
    with the current UTC time."""
    m = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID, auto_delivery_receipts=True)
    before = datetime.now(UTC)
    await _with_thread(m)
    result = await m.send_message(_send())
    after = datetime.now(UTC)
    assert result.acknowledged
    stamps = [e.occurred_at for e in m.events] + [e.observed_at for e in m.events]
    for at in stamps:
        assert at.tzinfo is not None and at.utcoffset() == timedelta(0)
        assert before <= at <= after
    assert [e.type for e in m.events][-2:] == [
        ConnectorEventType.MOCK_OUTBOUND_SEND_ACKNOWLEDGED,
        ConnectorEventType.MOCK_DELIVERY_RECEIPT_SIMULATED,
    ]


async def test_injected_clock_is_used_for_every_event() -> None:
    fixed = datetime(2030, 5, 6, 7, 8, 9, tzinfo=UTC)
    m = MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID, clock=lambda: fixed)
    await _with_thread(m)
    await m.send_message(_send())
    assert {e.occurred_at for e in m.events} == {fixed}
    assert {e.observed_at for e in m.events} == {fixed}
