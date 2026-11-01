"""Dev-only demo router, mounted on a throwaway FastAPI app."""

from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator

from app.api.demo_connector import DEMO_PREFIX, create_demo_connector_router
from app.connectors import Capability, MockConnector, default_mock_capabilities
from tests.connectors.conftest import CONNECTION_ID, USER_ID

P = DEMO_PREFIX


@pytest.fixture
def connector() -> MockConnector:
    return MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID, scenario_id="router")


@pytest.fixture
def client(connector: MockConnector) -> TestClient:
    app = FastAPI()
    app.include_router(create_demo_connector_router(connector))
    return TestClient(app)


def _inject(client: TestClient, ext_id: str | None = None) -> dict[str, object]:
    body: dict[str, object] = {
        "external_thread_id": "mock-thread-1",
        "sender_id": "mock-contact-sam",
        "display_name": "Sam",
        "text": "Just got back from Lisbon!",
    }
    if ext_id:
        body["external_message_id"] = ext_id
    resp = client.post(f"{P}/inbound", json=body)
    assert resp.status_code in (200, 201), resp.text
    data: dict[str, object] = resp.json()
    return data


def test_capabilities_endpoint_validates(
    client: TestClient, capabilities_validator: Draft202012Validator
) -> None:
    resp = client.get(f"{P}/capabilities")
    assert resp.status_code == 200
    capabilities_validator.validate(resp.json())
    assert resp.json()["platform"] == "mock"


def test_inject_list_and_read(client: TestClient) -> None:
    first = client.post(
        f"{P}/inbound",
        json={
            "external_thread_id": "mock-thread-1",
            "sender_id": "mock-contact-sam",
            "text": "hi",
            "external_message_id": "m-1",
        },
    )
    assert first.status_code == 201
    assert first.json()["outcome"] == "accepted"
    convs = client.get(f"{P}/conversations").json()
    assert [c["external_thread_id"] for c in convs["items"]] == ["mock-thread-1"]
    assert convs["next_cursor"] is None
    msgs = client.get(f"{P}/conversations/mock-thread-1/messages").json()
    assert [m["external_message_id"] for m in msgs["items"]] == ["m-1"]


def test_duplicate_inject_returns_200_duplicate(client: TestClient) -> None:
    _inject(client, "m-1")
    resp = client.post(
        f"{P}/inbound",
        json={
            "external_thread_id": "mock-thread-1",
            "sender_id": "mock-contact-sam",
            "text": "hi",
            "external_message_id": "m-1",
        },
    )
    assert resp.status_code == 200
    assert resp.json()["duplicate"] is True
    assert len(client.get(f"{P}/events").json()["items"]) == 1


def test_send_requires_idempotency_key(client: TestClient) -> None:
    _inject(client)
    resp = client.post(f"{P}/send", json={"external_thread_id": "mock-thread-1", "text": "yo"})
    assert resp.status_code == 422


def test_send_once_per_key_with_replay_header(client: TestClient, connector: MockConnector) -> None:
    _inject(client)
    body = {"external_thread_id": "mock-thread-1", "text": "Welcome back!"}
    headers = {"Idempotency-Key": "demo-key-0001"}
    first = client.post(f"{P}/send", json=body, headers=headers)
    second = client.post(f"{P}/send", json=body, headers=headers)
    assert first.status_code == 200 and second.status_code == 200
    assert first.json()["status"] == "acknowledged"
    assert "Idempotent-Replayed" not in first.headers
    assert second.headers["Idempotent-Replayed"] == "true"
    assert second.json()["external_message_id"] == first.json()["external_message_id"]
    assert len(connector.transport_log) == 1


def test_send_key_reuse_with_different_body_is_422(client: TestClient) -> None:
    _inject(client)
    headers = {"Idempotency-Key": "demo-key-0002"}
    client.post(
        f"{P}/send", json={"external_thread_id": "mock-thread-1", "text": "a"}, headers=headers
    )
    resp = client.post(
        f"{P}/send", json={"external_thread_id": "mock-thread-1", "text": "b"}, headers=headers
    )
    assert resp.status_code == 422
    assert resp.headers["content-type"].startswith("application/problem+json")
    assert resp.json()["code"] == "idempotency_key_mismatch"


def test_failure_injection_via_api(client: TestClient) -> None:
    _inject(client)
    assert client.post(f"{P}/failures", json={"count": 1, "code": "timeout"}).status_code == 204
    resp = client.post(
        f"{P}/send",
        json={"external_thread_id": "mock-thread-1", "text": "x"},
        headers={"Idempotency-Key": "demo-key-0003"},
    )
    assert resp.json()["status"] == "failed"
    assert resp.json()["failure"]["code"] == "timeout"
    assert resp.json()["event"]["type"] == "mock.outbound_send_failed"


def test_unsupported_send_returns_problem(client: TestClient, connector: MockConnector) -> None:
    _inject(client)
    base = default_mock_capabilities()
    connector.set_capabilities(
        base.model_copy(
            update={
                "capabilities": base.capabilities.model_copy(
                    update={"send": Capability.unsupported()}
                )
            }
        )
    )
    resp = client.post(
        f"{P}/send",
        json={"external_thread_id": "mock-thread-1", "text": "x"},
        headers={"Idempotency-Key": "demo-key-0004"},
    )
    assert resp.status_code == 422
    assert resp.headers["content-type"].startswith("application/problem+json")
    assert resp.json()["code"] == "capability_unsupported"
    assert connector.transport_log == []


def test_receipt_and_events_validate(
    client: TestClient, event_validator: Draft202012Validator
) -> None:
    _inject(client)
    sent = client.post(
        f"{P}/send",
        json={"external_thread_id": "mock-thread-1", "text": "x"},
        headers={"Idempotency-Key": "demo-key-0005"},
    ).json()
    receipt = client.post(f"{P}/receipts/{sent['outbound_message_id']}")
    assert receipt.status_code == 200
    assert receipt.json()["type"] == "mock.delivery_receipt_simulated"
    unknown = client.post(f"{P}/receipts/00000000-0000-4000-8000-000000000000")
    assert unknown.status_code == 409

    events = client.get(f"{P}/events").json()["items"]
    assert [e["type"] for e in events] == [
        "mock.inbound_message_injected",
        "mock.outbound_send_acknowledged",
        "mock.delivery_receipt_simulated",
    ]
    for event in events:
        event_validator.validate(event)


def test_invalid_cursor_is_problem(client: TestClient) -> None:
    resp = client.get(f"{P}/conversations", params={"cursor": "bogus"})
    assert resp.status_code == 422
    assert resp.json()["code"] == "validation_error"


def test_default_router_builds_its_own_connector() -> None:
    app = FastAPI()
    app.include_router(create_demo_connector_router())
    c = TestClient(app)
    assert c.get(f"{P}/capabilities").json()["platform"] == "mock"
