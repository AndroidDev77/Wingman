"""Required vs nullable columns follow the contract schemas.

For each stored contract object, every property that maps to a column is checked:
required + non-nullable in the contract -> ``NOT NULL`` column; nullable in the contract
-> nullable column. Then constraint errors are provoked on missing required fields.
"""

import uuid
from datetime import UTC, datetime
from functools import cache
from pathlib import Path
from typing import Any

import pytest
import yaml
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import models
from app.db.base import Base
from app.db.enums import DeliveryState, MessageDirection, MessageOrigin
from app.db.repositories import ContactRepository, ConversationRepository
from tests.db.conftest import make_connection, make_user

OPENAPI = Path(__file__).resolve().parents[3] / "contracts" / "openapi.yaml"


@cache
def schemas() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load(OPENAPI.read_text())
    return doc["components"]["schemas"]  # type: ignore[no-any-return]


def contract_nullable(prop: dict[str, Any]) -> bool:
    t = prop.get("type")
    if isinstance(t, list) and "null" in t:
        return True
    return any(alt.get("type") == "null" for alt in prop.get("anyOf", []))


# contract schema -> (table, {contract property: column}) for properties stored 1:1.
MAPPINGS: dict[str, tuple[str, dict[str, str]]] = {
    "User": (
        "users",
        {"id": "id", "email": "email", "display_name": "display_name", "created_at": "created_at"},
    ),
    "Connection": (
        "platform_connections",
        {
            "id": "id",
            "platform": "platform",
            "display_name": "display_name",
            "status": "status",
            "capabilities": "capabilities",
            "created_at": "created_at",
            "updated_at": "updated_at",
        },
    ),
    "Conversation": (
        "conversations",
        {
            "id": "id",
            "connection_id": "connection_id",
            "platform": "platform",
            "external_thread_id": "external_thread_id",
            "stage": "stage",
            "unread_count": "unread_count",
            "last_message_preview": "last_message_preview",
            "last_activity_at": "last_activity_at",
            "created_at": "created_at",
        },
    ),
    "Message": (
        "messages",
        {
            "id": "id",
            "conversation_id": "conversation_id",
            "direction": "direction",
            "origin": "origin",
            "text": "text",
            "delivery_state": "delivery_state",
            "external_message_id": "external_message_id",
            "approval_id": "approval_id",
            "attempt_count": "attempt_count",
            "created_at": "created_at",
            "sent_at": "sent_at",
            "delivered_at": "delivered_at",
        },
    ),
    "AgentAction": (
        "agent_actions",
        {
            "id": "id",
            "type": "type",
            "actor": "actor",
            "conversation_id": "conversation_id",
            "contact_id": "contact_id",
            "message_id": "message_id",
            "approval_id": "approval_id",
            "reason_code": "reason_code",
            "occurred_at": "occurred_at",
            "details": "details",
        },
    ),
    "DateProposal": (
        "date_proposals",
        {
            "id": "id",
            "conversation_id": "conversation_id",
            "state": "state",
            "proposed_start": "proposed_start",
            "proposed_end": "proposed_end",
            "venue_text": "venue_text",
            "confirmation_message_ids": "confirmation_message_ids",
            "calendar_event_id": "calendar_event_id",
            "created_at": "created_at",
            "updated_at": "updated_at",
        },
    ),
    "StyleProfile": (
        "style_profiles",
        {
            "punctuation": "punctuation",
            "emoji_frequency": "emoji_frequency",
            "brevity": "brevity",
            "humor": "humor",
            "tone": "tone",
            "response_pace": "response_pace",
            "style_samples": "style_samples",
        },
    ),
}


def _cases() -> list[tuple[str, str, str]]:
    return [(s, p, c) for s, (_, m) in MAPPINGS.items() for p, c in m.items()]


@pytest.mark.parametrize(("schema", "prop", "column"), _cases())
def test_column_nullability_matches_contract(schema: str, prop: str, column: str) -> None:
    table_name, _ = MAPPINGS[schema]
    spec = schemas()[schema]
    prop_spec = spec["properties"][prop]
    col = Base.metadata.tables[table_name].c[column]
    if contract_nullable(prop_spec):
        assert col.nullable, f"{schema}.{prop} is nullable in the contract"
    elif prop in spec.get("required", []) or prop_spec.get("type") == "array":
        # Required non-null fields, and arrays (stored as NOT NULL DEFAULT '{}').
        assert not col.nullable, f"{schema}.{prop} is required in the contract"


async def test_live_schema_nullability_matches_models(session: AsyncSession) -> None:
    rows = (
        await session.execute(
            text(
                "SELECT table_name, column_name, is_nullable FROM information_schema.columns"
                " WHERE table_schema = 'public' AND table_name <> 'alembic_version'"
            )
        )
    ).all()
    live = {(t, c): n == "YES" for t, c, n in rows}
    declared = {
        (t.name, c.name): bool(c.nullable) for t in Base.metadata.sorted_tables for c in t.columns
    }
    assert live == declared


def test_every_user_owned_model_has_required_user_id() -> None:
    for model in models.USER_OWNED_MODELS:
        col = model.__table__.c["user_id"]  # type: ignore[attr-defined]
        assert not col.nullable
        assert {fk.column.table.name for fk in col.foreign_keys} >= {"users"}


# ------------------------------------------------------------------ provoke NOT NULL errors

REQUIRED_MESSAGE = ["text", "direction", "origin", "delivery_state", "conversation_id"]


@pytest.mark.parametrize("missing", REQUIRED_MESSAGE)
async def test_message_missing_required_field_rejected(session: AsyncSession, missing: str) -> None:
    scope = await make_user(session)
    conn_id = await make_connection(session, scope)
    contact = await ContactRepository(session, scope).get_or_create(
        connection_id=conn_id, external_contact_id="c1", display_name="Sam"
    )
    conv = await ConversationRepository(session, scope).create(
        contact_id=contact.id, connection_id=conn_id, platform="mock"
    )
    values: dict[str, Any] = {
        "id": uuid.uuid4(),
        "user_id": scope.user_id,
        "conversation_id": conv.id,
        "platform": "mock",
        "direction": MessageDirection.INBOUND.value,
        "origin": MessageOrigin.CONTACT.value,
        "text": "hi",
        "delivery_state": DeliveryState.RECEIVED.value,
        "external_message_id": "ext-1",
    }
    values[missing] = None
    cols = ", ".join(values)
    params = ", ".join(f":{k}" for k in values)
    with pytest.raises(IntegrityError, match="not-null|null value"):
        async with session.begin_nested():
            await session.execute(text(f"INSERT INTO messages ({cols}) VALUES ({params})"), values)  # noqa: S608


@pytest.mark.parametrize(
    ("table", "values"),
    [
        ("users", {"auth_issuer": None, "auth_subject": "x"}),
        ("contacts", {"display_name": None}),
        ("agent_actions", {"type": None}),
        ("date_proposals", {"state": None}),
    ],
)
async def test_other_required_fields_rejected(
    session: AsyncSession, table: str, values: dict[str, Any]
) -> None:
    scope = await make_user(session)
    conn_id = await make_connection(session, scope)
    base: dict[str, dict[str, Any]] = {
        "users": {"id": uuid.uuid4(), "auth_issuer": "i", "auth_subject": "s"},
        "contacts": {
            "id": uuid.uuid4(),
            "user_id": scope.user_id,
            "connection_id": conn_id,
            "external_contact_id": "x",
            "display_name": "n",
        },
        "agent_actions": {
            "id": uuid.uuid4(),
            "user_id": scope.user_id,
            "type": "message_received",
            "actor": "connector",
            "occurred_at": datetime.now(UTC),
        },
        "date_proposals": {
            "id": uuid.uuid4(),
            "user_id": scope.user_id,
            "conversation_id": uuid.uuid4(),
            "state": "suggested",
        },
    }
    row = base[table] | values
    cols = ", ".join(row)
    params = ", ".join(f":{k}" for k in row)
    with pytest.raises(IntegrityError, match="not-null|null value"):
        async with session.begin_nested():
            await session.execute(text(f"INSERT INTO {table} ({cols}) VALUES ({params})"), row)  # noqa: S608
