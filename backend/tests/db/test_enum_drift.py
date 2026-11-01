"""Persisted enums must equal the contract enums (no drift in either direction)."""

import json
from functools import cache
from pathlib import Path
from typing import Any

import pytest
import yaml
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import CONTRACT_ENUMS, ContractEnum

CONTRACTS = Path(__file__).resolve().parents[3] / "contracts"


@cache
def _load(name: str) -> Any:
    raw = (CONTRACTS / name).read_text()
    return json.loads(raw) if name.endswith(".json") else yaml.safe_load(raw)


def contract_values(ce: ContractEnum) -> list[str]:
    node: Any = _load(ce.contract_path[0])
    for key in ce.contract_path[1:]:
        node = node[key]
    values = node["enum"]
    # StyleProfile attributes are `enum: [..., null]`: null is column nullability, not a label.
    return [v for v in values if v is not None]


def test_contracts_are_present() -> None:
    assert (CONTRACTS / "openapi.yaml").is_file(), CONTRACTS


@pytest.mark.parametrize("ce", CONTRACT_ENUMS, ids=lambda ce: ce.pg_name)
def test_python_enum_matches_contract(ce: ContractEnum) -> None:
    assert [m.value for m in ce.enum] == contract_values(ce)


async def test_database_enums_match_contract(session: AsyncSession) -> None:
    rows = (
        await session.execute(
            text(
                "SELECT t.typname, array_agg(e.enumlabel ORDER BY e.enumsortorder)"
                " FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid"
                " JOIN pg_namespace n ON n.oid = t.typnamespace"
                " WHERE n.nspname = 'public' GROUP BY t.typname"
            )
        )
    ).all()
    in_db = {name: list(labels) for name, labels in rows}
    expected = {ce.pg_name: contract_values(ce) for ce in CONTRACT_ENUMS}
    assert in_db == expected


def test_every_contract_enum_used_by_storage_is_covered() -> None:
    """Contract enums that map to stored columns must all be registered in CONTRACT_ENUMS."""
    schemas = _load("openapi.yaml")["components"]["schemas"]
    stored = {
        "IntentMode",
        "AutomationMode",
        "ConversationStage",
        "DateProposalState",
        "DeliveryState",
        "MessageDirection",
        "MessageOrigin",
        "ApprovalReason",
        "ContactAutomationStatus",
        "GlobalAutomationStatus",
        "Actor",
        "AgentActionType",
        "ConnectionStatus",
    }
    registered = {
        ce.contract_path[3] for ce in CONTRACT_ENUMS if ce.contract_path[0] == "openapi.yaml"
    }
    assert stored <= registered
    for name in stored:
        assert "enum" in schemas[name], name
