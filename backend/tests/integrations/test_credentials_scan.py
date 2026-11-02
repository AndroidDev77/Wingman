"""ADR 0008: no backend route ever accepts platform credentials or session tokens.

Scans the contract (every schema) and the backend's served OpenAPI (every request
body the app actually parses) for credential-like property names."""

import json
import re
from pathlib import Path
from typing import Any

import pytest
import yaml

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
# Same pattern as contracts/validate.py check [7].
CREDENTIAL_NAME = re.compile(
    r"(passw|passcode|secret|token|cookie|session|credential|otp|one_time|"
    r"api_?key|auth_?code|verification_code|sms_code|login|bearer|jwt|private_key)",
    re.IGNORECASE,
)


def property_names(node: Any, path: str = "") -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    if isinstance(node, dict):
        props = node.get("properties")
        if isinstance(props, dict):
            found += [(path, name) for name in props]
        for key, value in node.items():
            found += property_names(value, f"{path}/{key}")
    elif isinstance(node, list):
        for i, value in enumerate(node):
            found += property_names(value, f"{path}/{i}")
    return found


def test_pattern_catches_the_obvious_names() -> None:
    for bad in ("password", "session_token", "auth_token", "cookie", "otp", "api_key", "sessionId"):
        assert CREDENTIAL_NAME.search(bad), bad
    for fine in ("idempotency_key", "external_message_id", "consent_text_version", "display_name"):
        assert not CREDENTIAL_NAME.search(fine), fine


def test_contract_has_no_credential_fields() -> None:
    doc = yaml.safe_load((CONTRACTS / "openapi.yaml").read_text())
    trees: list[Any] = [doc["components"]]
    trees += [json.loads(p.read_text()) for p in sorted((CONTRACTS / "schemas").glob("*.json"))]
    names = [n for t in trees for _, n in property_names(t)]
    assert len(names) > 300
    assert [n for n in names if CREDENTIAL_NAME.search(n)] == []


def test_platform_connection_schemas_have_no_credential_fields() -> None:
    schemas = yaml.safe_load((CONTRACTS / "openapi.yaml").read_text())["components"]["schemas"]
    for name in (
        "Connection",
        "ConnectionCreateRequest",
        "ConnectionSettingsUpdate",
        "ExperimentalConsentInput",
        "ExperimentalConsent",
    ):
        props = [n for _, n in property_names(schemas[name])]
        assert props, name
        assert not [n for n in props if CREDENTIAL_NAME.search(n)], name
        # Request bodies reject any unknown field, so a credential cannot slip in either.
        if name.endswith(("Request", "Input", "Update")):
            assert schemas[name]["additionalProperties"] is False, name


@pytest.mark.parametrize("env", ["dev", "prod"])
def test_served_request_bodies_have_no_credential_fields(
    monkeypatch: pytest.MonkeyPatch, env: str
) -> None:
    from tests.test_app_wiring import _client

    with _client(monkeypatch, env) as client:
        served = client.get("/openapi.json").json()
    names = [n for _, n in property_names(served.get("components", {}))]
    assert names
    assert [n for n in names if CREDENTIAL_NAME.search(n)] == []
