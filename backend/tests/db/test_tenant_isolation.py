"""Rows written for user A are never visible, or modifiable, through user B's scope."""

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.enums import Actor, AgentActionType, AutomationMode, ContactAutomationStatus, IntentMode
from app.db.errors import NotFoundError
from app.db.repositories import (
    AgentActionRepository,
    AutomationRuleRepository,
    ConnectionRepository,
    ContactRepository,
    ConversationRepository,
    DateProposalRepository,
    IdempotencyRepository,
    MemoryRepository,
    MessageRepository,
    PreferenceRepository,
    ScopedRepository,
    StyleProfileRepository,
    SubscriptionRepository,
    UsageEventRepository,
    fingerprint,
)
from app.db.scope import UserScope
from tests.db.conftest import make_connection, make_user

T0 = datetime(2026, 11, 1, 12, 0, tzinfo=UTC)


@dataclass
class World:
    scope: UserScope
    ids: dict[str, uuid.UUID]


async def populate(session: AsyncSession, scope: UserScope) -> World:
    """One row in every user-owned table for ``scope``."""
    ids: dict[str, uuid.UUID] = {}
    ids["connection"] = await make_connection(session, scope)
    contact = await ContactRepository(session, scope).get_or_create(
        connection_id=ids["connection"], external_contact_id="c", display_name="Sam"
    )
    ids["contact"] = contact.id
    conv = await ConversationRepository(session, scope).create(
        contact_id=contact.id,
        connection_id=ids["connection"],
        platform="mock",
        external_thread_id="t-1",
        last_activity_at=T0,
    )
    ids["conversation"] = conv.id
    msg = await MessageRepository(session, scope).ingest_inbound(
        conversation_id=conv.id,
        platform="mock",
        external_message_id="m-1",
        text="hi",
        sent_at_source=T0,
    )
    ids["message"] = msg.message.id
    style = await StyleProfileRepository(session, scope).create()
    ids["style"] = style.id
    pref = await PreferenceRepository(session, scope).save_version(
        expected_version=0,
        intent_mode=IntentMode.SERIOUS,
        style_profile_id=style.id,
        automation_mode=AutomationMode.MANUAL,
    )
    ids["preference"] = pref.id
    mem = await MemoryRepository(session, scope).add(
        contact_id=contact.id,
        content="likes hiking",
        confidence=0.9,
        source_message_ids=[msg.message.id],
    )
    ids["memory"] = mem.id
    act = await AgentActionRepository(session, scope).record(
        type=AgentActionType.MESSAGE_RECEIVED,
        actor=Actor.CONNECTOR,
        conversation_id=conv.id,
        message_id=msg.message.id,
    )
    ids["action"] = act.id
    rule = await AutomationRuleRepository(session, scope).create(
        name="r", action="require_approval"
    )
    ids["rule"] = rule.id
    dp = await DateProposalRepository(session, scope).create(conversation_id=conv.id)
    ids["date"] = dp.id
    sub = await SubscriptionRepository(session, scope).create(plan="basic", status="active")
    ids["subscription"] = sub.id
    usage = await UsageEventRepository(session, scope).record(
        event_type="message.analyzed", dedup_key="k1", occurred_at=T0
    )
    ids["usage"] = usage.event.id
    claim = await IdempotencyRepository(session, scope).claim(
        key="key-123456", method="POST", route="/v1/x", request_hash=fingerprint({})
    )
    ids["idem"] = claim.record.id
    return World(scope, ids)


REPOS: dict[str, type[ScopedRepository[object]]] = {  # type: ignore[type-var]
    "connection": ConnectionRepository,
    "contact": ContactRepository,
    "conversation": ConversationRepository,
    "message": MessageRepository,
    "style": StyleProfileRepository,
    "preference": PreferenceRepository,
    "memory": MemoryRepository,
    "action": AgentActionRepository,
    "rule": AutomationRuleRepository,
    "date": DateProposalRepository,
    "subscription": SubscriptionRepository,
    "usage": UsageEventRepository,
    "idem": IdempotencyRepository,
}


@pytest.fixture
async def two_users(session: AsyncSession) -> tuple[World, World]:
    a = await populate(session, await make_user(session))
    b = await populate(session, await make_user(session))
    return a, b


@pytest.mark.parametrize("kind", sorted(REPOS))
async def test_get_by_id_is_scoped(
    session: AsyncSession, two_users: tuple[World, World], kind: str
) -> None:
    a, b = two_users
    repo_cls = REPOS[kind]
    assert await repo_cls(session, a.scope).get(a.ids[kind]) is not None
    assert await repo_cls(session, b.scope).get(a.ids[kind]) is None
    with pytest.raises(NotFoundError):
        await repo_cls(session, b.scope).require(a.ids[kind])


async def test_listings_are_scoped(session: AsyncSession, two_users: tuple[World, World]) -> None:
    a, b = two_users

    def ids(items: list[object]) -> set[uuid.UUID]:
        return {i.id for i in items}  # type: ignore[attr-defined]

    for scope_w, other in ((a, b), (b, a)):
        s = scope_w.scope
        inbox = await ConversationRepository(session, s).list_inbox()
        assert ids(inbox.items) == {scope_w.ids["conversation"]}
        conns = await ConnectionRepository(session, s).list()
        assert ids(conns.items) == {scope_w.ids["connection"]}
        audit = await AgentActionRepository(session, s).list()
        assert ids(audit.items) == {scope_w.ids["action"]}
        # Asking for the other user's conversation thread yields nothing, not their messages.
        thread = await MessageRepository(session, s).list_thread(other.ids["conversation"])
        assert thread.items == []
        mems = await MemoryRepository(session, s).active_for_contact(other.ids["contact"])
        assert mems == []
        assert await PreferenceRepository(session, s).current() is not None
        assert (await PreferenceRepository(session, s).current()).id == scope_w.ids["preference"]  # type: ignore[union-attr]
        rules = await AutomationRuleRepository(session, s).applicable(None)
        assert ids(rules) == {scope_w.ids["rule"]}  # type: ignore[arg-type]
        cur_sub = await SubscriptionRepository(session, s).current()
        assert cur_sub is not None and cur_sub.id == scope_w.ids["subscription"]
        assert (
            await UsageEventRepository(session, s).total(
                "message.analyzed", since=T0 - timedelta(days=1), until=T0 + timedelta(days=1)
            )
            == 1
        )
        dps = await DateProposalRepository(session, s).list_for_conversation(
            other.ids["conversation"]
        )
        assert dps == []


async def test_lookups_by_natural_key_are_scoped(
    session: AsyncSession, two_users: tuple[World, World]
) -> None:
    a, b = two_users
    assert await MessageRepository(session, b.scope).get_by_external_id("mock", "m-1") is not None
    found = await MessageRepository(session, b.scope).get_by_external_id("mock", "m-1")
    assert found is not None and found.id == b.ids["message"] != a.ids["message"]
    conv = await ConversationRepository(session, b.scope).get_by_external_thread(
        a.ids["connection"], "t-1"
    )
    assert conv is None
    idem = await IdempotencyRepository(session, b.scope).lookup(
        key="key-123456", method="POST", route="/v1/x"
    )
    assert idem is not None and idem.id == b.ids["idem"]


async def test_updates_cannot_touch_other_users_rows(
    session: AsyncSession, two_users: tuple[World, World]
) -> None:
    a, b = two_users
    with pytest.raises(NotFoundError):
        await ContactRepository(session, b.scope).set_automation_status(
            a.ids["contact"], ContactAutomationStatus.TAKEN_OVER
        )
    with pytest.raises(NotFoundError):
        await MessageRepository(session, b.scope).update_delivery(a.ids["message"], attempt_count=5)
    with pytest.raises(NotFoundError):
        await MemoryRepository(session, b.scope).soft_delete(a.ids["memory"])
    with pytest.raises(NotFoundError):
        await DateProposalRepository(session, b.scope).update(a.ids["date"], venue_text="x")
    contact = await ContactRepository(session, a.scope).require(a.ids["contact"])
    assert contact.automation_status is ContactAutomationStatus.ACTIVE


async def test_cross_tenant_references_rejected_by_database(
    session: AsyncSession, two_users: tuple[World, World]
) -> None:
    """B cannot attach rows to A's parents even by guessing IDs (composite tenant FKs)."""
    a, b = two_users
    attempts = [
        lambda: ConversationRepository(session, b.scope).create(
            contact_id=a.ids["contact"], connection_id=a.ids["connection"], platform="mock"
        ),
        lambda: MessageRepository(session, b.scope).ingest_inbound(
            conversation_id=a.ids["conversation"],
            platform="mock",
            external_message_id="evil",
            text="x",
            sent_at_source=T0,
        ),
        lambda: ContactRepository(session, b.scope).get_or_create(
            connection_id=a.ids["connection"], external_contact_id="evil", display_name="x"
        ),
        lambda: MemoryRepository(session, b.scope).add(
            contact_id=a.ids["contact"],
            content="x",
            confidence=0.5,
            source_message_ids=[a.ids["message"]],
        ),
        lambda: AgentActionRepository(session, b.scope).record(
            type=AgentActionType.DRAFT_GENERATED,
            actor=Actor.AGENT,
            conversation_id=a.ids["conversation"],
        ),
        lambda: DateProposalRepository(session, b.scope).create(
            conversation_id=a.ids["conversation"]
        ),
        lambda: PreferenceRepository(session, b.scope).save_version(
            expected_version=None,
            contact_id=a.ids["contact"],
            intent_mode=None,
            style_profile_id=None,
            automation_mode=None,
        ),
        lambda: PreferenceRepository(session, b.scope).save_version(
            expected_version=None,
            intent_mode=IntentMode.OPEN,
            style_profile_id=a.ids["style"],
            automation_mode=AutomationMode.MANUAL,
        ),
    ]
    for attempt in attempts:
        with pytest.raises(IntegrityError, match="foreign key"):
            async with session.begin_nested():
                await attempt()


def test_repositories_require_a_scope(session: AsyncSession) -> None:
    for repo_cls in REPOS.values():
        with pytest.raises(TypeError):
            repo_cls(session, uuid.uuid4())  # type: ignore[arg-type]
    with pytest.raises(TypeError):
        UserScope("not-a-uuid")  # type: ignore[arg-type]


async def test_scope_cannot_be_overridden_on_write(session: AsyncSession) -> None:
    a = await make_user(session)
    b = await make_user(session)
    with pytest.raises(ValueError, match="user_id comes from the repository scope"):
        await AutomationRuleRepository(session, a)._add(
            user_id=b.user_id, name="x", action="block_send"
        )


def test_no_public_unscoped_read_methods() -> None:
    """Every public coroutine on a scoped repository reads through the bound scope.

    Structural guard: repositories may only build SELECTs through ``_select`` (scoped),
    so their source must not call ``select(`` directly.
    """
    import inspect

    import app.db.repositories.activity as activity
    import app.db.repositories.billing as billing
    import app.db.repositories.connections as connections
    import app.db.repositories.conversations as conversations
    import app.db.repositories.idempotency as idempotency

    for mod in (activity, billing, connections, conversations, idempotency):
        src = inspect.getsource(mod)
        assert "select(" not in src.replace("_select(", ""), mod.__name__
    pref_src = inspect.getsource(PreferenceRepository)
    # The only raw select() aggregates max(version) and is filtered by the scope.
    assert pref_src.count("select(") - pref_src.count("_select(") == 1
    assert "UserPreference.user_id == self.scope.user_id" in pref_src
