"""Shared fixtures: contract schema validators and a fresh MockConnector."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from app.connectors import MockConnector

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"
SCHEMAS = CONTRACTS / "schemas"
EXAMPLES = CONTRACTS / "examples"

USER_ID = UUID("0192f3a0-0000-7000-8000-000000000001")
OTHER_USER_ID = UUID("0192f3a0-0000-7000-8000-000000000002")
CONNECTION_ID = UUID("0192f3a0-0000-7000-8000-0000000000a2")


def _registry() -> Registry:
    resources: list[tuple[str, Resource[Any]]] = []
    for path in sorted(SCHEMAS.glob("*.json")):
        schema = json.loads(path.read_text())
        res = DRAFT202012.create_resource(schema)
        resources.append((path.as_uri(), res))
        resources.append((schema["$id"], res))
    return Registry().with_resources(resources)


def _validator(name: str) -> Draft202012Validator:
    schema = json.loads((SCHEMAS / name).read_text())
    return Draft202012Validator(
        schema, registry=_registry(), format_checker=Draft202012Validator.FORMAT_CHECKER
    )


@pytest.fixture(scope="session")
def capabilities_validator() -> Draft202012Validator:
    return _validator("connector_capabilities.v1.json")


@pytest.fixture(scope="session")
def event_validator() -> Draft202012Validator:
    return _validator("connector_event.v1.json")


def load_example(name: str) -> dict[str, Any]:
    data: dict[str, Any] = json.loads((EXAMPLES / name).read_text())
    return data


@pytest.fixture
def mock() -> MockConnector:
    return MockConnector(user_id=USER_ID, connection_id=CONNECTION_ID, scenario_id="unit")
