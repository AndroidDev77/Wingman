"""The WING-008 provenance columns folded into migration 0001 (D-010)."""

import pytest
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.repositories import MemoryRepository
from tests.ai.memory.conftest import make_thread, make_user, say


async def test_provenance_columns_and_defaults(session: AsyncSession) -> None:
    rows = (
        await session.execute(
            text(
                "SELECT column_name, is_nullable, column_default FROM information_schema.columns"
                " WHERE table_name = 'conversation_memories'"
                " AND column_name IN ('origin', 'basis', 'fact_key', 'stated_at')"
            )
        )
    ).all()
    cols = {r[0]: (r[1], r[2]) for r in rows}
    assert set(cols) == {"origin", "basis", "fact_key", "stated_at"}
    assert cols["origin"][0] == "NO" and "extracted" in cols["origin"][1]
    assert cols["basis"][0] == "NO" and "stated" in cols["basis"][1]
    assert cols["fact_key"][0] == "YES" and cols["stated_at"][0] == "YES"


@pytest.mark.parametrize(
    ("field", "value", "constraint"),
    [
        ("origin", "model", "ck_conversation_memories_origin_values"),
        ("basis", "guess", "ck_conversation_memories_basis_values"),
    ],
)
async def test_provenance_checks(
    session: AsyncSession, field: str, value: str, constraint: str
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    m = await say(session, t, "hi")
    with pytest.raises(IntegrityError, match=constraint):
        async with session.begin_nested():
            await MemoryRepository(session, scope).add(
                contact_id=t.contact_id,
                content="x",
                confidence=0.5,
                source_message_ids=[m],
                **{field: value},
            )
