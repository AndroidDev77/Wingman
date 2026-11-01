"""Retrieval: bounded, relevant, isolated; expired facts excluded; vector + fallback."""

import uuid
from collections.abc import Callable, Sequence
from datetime import timedelta

import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.memory import (
    ContextLimits,
    EmbeddingError,
    HashingEmbedder,
    InMemoryVectorIndex,
    LexicalRetriever,
    VectorRetriever,
)
from app.ai.memory.retrieval import cosine, tokenize
from app.db.errors import NotFoundError
from app.db.repositories import MemoryRepository
from tests.ai.memory.conftest import (
    NOW,
    TODAY,
    Harness,
    Thread,
    fact,
    facts,
    make_connection,
    make_thread,
    make_user,
    say,
)


async def _seed(
    session: AsyncSession, harness: Harness, t: Thread, items: list[tuple[str, str]]
) -> None:
    await say(session, t, "lots of things", at=NOW - timedelta(days=1))
    harness.provider.push(facts(*(fact(k, c, ["m1"]) for k, c in items)))
    await harness.service.extract_from_messages(
        session, t.scope, t.conversation_id, message_ids=[t.messages[-1]]
    )


async def test_expired_facts_excluded(
    session: AsyncSession, make_harness: Callable[..., Harness]
) -> None:
    early = make_harness(now=NOW)
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "in Lisbon until Friday, and I love surfing")
    early.provider.push(
        facts(
            fact(
                "plans.travel",
                "In Lisbon until Friday",
                ["m1"],
                valid_until=TODAY + timedelta(days=3),
            ),
            fact("interests.surfing", "Loves surfing", ["m1"]),
        )
    )
    await early.service.extract_from_messages(session, scope, t.conversation_id)
    ctx = await early.service.get_context(session, scope, t.conversation_id, "lisbon surfing")
    assert {f.key for f in ctx.facts} == {"plans.travel", "interests.surfing"}

    later = make_harness(now=NOW + timedelta(days=5))
    ctx2 = await later.service.get_context(session, scope, t.conversation_id, "lisbon surfing")
    assert [f.key for f in ctx2.facts] == ["interests.surfing"]
    active = await later.service.list_facts(session, scope, t.contact_id)
    assert [f.key for f in active] == ["interests.surfing"]
    # Still in the audit history.
    hist = await later.service.list_facts(session, scope, t.contact_id, include_history=True)
    assert {f.key for f in hist} == {"plans.travel", "interests.surfing"}


async def test_no_cross_contact_or_cross_user_leakage(
    session: AsyncSession, harness: Harness
) -> None:
    alice = await make_user(session)
    bob = await make_user(session)
    conn = await make_connection(session, alice)
    a1 = await make_thread(session, alice, connection_id=conn, name="Jo")
    a2 = await make_thread(session, alice, connection_id=conn, name="Kim")
    a3 = await make_thread(session, alice, name="Lee")  # another connection
    b1 = await make_thread(session, bob, name="Max")
    marker = {
        a1.conversation_id: "zebra",
        a2.conversation_id: "giraffe",
        a3.conversation_id: "pelican",
        b1.conversation_id: "walrus",
    }
    for t in (a1, a2, a3, b1):
        word = marker[t.conversation_id]
        await _seed(session, harness, t, [(f"interests.{word}", f"Loves the {word}")])
        harness.provider.push({"summary": f"Talked about the {word}."})
        await harness.service.update_summary(session, t.scope, t.conversation_id)

    query = "zebra giraffe pelican walrus loves"
    for t in (a1, a2, a3, b1):
        ctx = await harness.service.get_context(session, t.scope, t.conversation_id, query)
        word = marker[t.conversation_id]
        assert ctx.contact_id == t.contact_id
        assert [f.content for f in ctx.facts] == [f"Loves the {word}"]
        assert ctx.summary is not None and word in ctx.summary.content
        others = set(marker.values()) - {word}
        rendered = ctx.render()
        assert not any(o in rendered for o in others)
        rows = await MemoryRepository(session, t.scope).active_for_contact(t.contact_id)
        assert {f.id for f in ctx.facts} | {ctx.summary.id} == {r.id for r in rows}

    # Bob cannot read, correct or delete Alice's memory, or use her conversation.
    alice_fact = (await harness.service.list_facts(session, alice, a1.contact_id))[0]
    with pytest.raises(NotFoundError):
        await harness.service.get_context(session, bob, a1.conversation_id, query)
    with pytest.raises(NotFoundError):
        await harness.service.list_facts(session, bob, a1.contact_id)
    with pytest.raises(NotFoundError):
        await harness.service.delete_fact(session, bob, alice_fact.id)
    with pytest.raises(NotFoundError):
        await harness.service.history(session, bob, alice_fact.id)
    still = await harness.service.list_facts(session, alice, a1.contact_id)
    assert alice_fact.id in {f.id for f in still}


async def test_same_key_in_two_contacts_does_not_supersede(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    a = await make_thread(session, scope)
    b = await make_thread(session, scope)
    await _seed(session, harness, a, [("work.job_title", "Works as a nurse")])
    await _seed(session, harness, b, [("work.job_title", "Works as a pilot")])
    ca = await harness.service.get_context(session, scope, a.conversation_id, "job")
    cb = await harness.service.get_context(session, scope, b.conversation_id, "job")
    assert [f.content for f in ca.facts] == ["Works as a nurse"]
    assert [f.content for f in cb.facts] == ["Works as a pilot"]


async def test_retrieval_bounded_under_large_history(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    for batch in range(6):
        await say(session, t, f"batch {batch}", at=NOW - timedelta(days=60 - batch))
        harness.provider.push(
            facts(
                *(
                    fact(f"interests.topic_{batch}_{i}", f"Enjoys topic {batch}-{i} a lot", ["m1"])
                    for i in range(40)
                )
            )
        )
        await harness.service.extract_from_messages(
            session, scope, t.conversation_id, message_ids=[t.messages[-1]]
        )
    await say(session, t, "my dog Pixel is the best", at=NOW - timedelta(days=50))
    harness.provider.push(facts(fact("pets.dog", "Has a dog named Pixel", ["m1"])))
    await harness.service.extract_from_messages(
        session, scope, t.conversation_id, message_ids=[t.messages[-1]]
    )
    assert len(await harness.service.list_facts(session, scope, t.contact_id)) == 241

    limits = ContextLimits(max_facts=5, max_chars=400)
    ctx = await harness.service.get_context(
        session, scope, t.conversation_id, "how is your dog Pixel?", limits
    )
    assert len(ctx.facts) <= 5
    assert len(ctx.render()) == ctx.char_count <= 400
    assert ctx.facts[0].key == "pets.dog", "the relevant fact outranks newer noise"
    assert ctx.omitted_count == 241 - len(ctx.facts)

    tiny = await harness.service.get_context(
        session, scope, t.conversation_id, "dog", ContextLimits(max_facts=50, max_chars=60)
    )
    assert len(tiny.render()) == tiny.char_count <= 60
    none = await harness.service.get_context(
        session, scope, t.conversation_id, "dog", ContextLimits(max_facts=0)
    )
    assert none.facts == ()


async def test_ranking_is_deterministic(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await _seed(
        session,
        harness,
        t,
        [(f"interests.thing_{i}", f"Likes thing {i}") for i in range(10)],
    )
    a = await harness.service.get_context(session, scope, t.conversation_id, "things")
    b = await harness.service.get_context(session, scope, t.conversation_id, "things")
    assert [f.id for f in a.facts] == [f.id for f in b.facts]


async def test_inferred_facts_labeled_and_filterable(
    session: AsyncSession, harness: Harness
) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await say(session, t, "up at 5 for a run")
    harness.provider.push(
        facts(
            fact("interests.running", "Goes running", ["m1"]),
            fact(
                "preferences.mornings",
                "Seems to be a morning person",
                ["m1"],
                category="preferences",
                basis="inferred",
                confidence=0.8,
            ),
        )
    )
    await harness.service.extract_from_messages(session, scope, t.conversation_id)
    ctx = await harness.service.get_context(session, scope, t.conversation_id, "morning run")
    rendered = ctx.render()
    assert "Seems to be a morning person (inferred, unconfirmed)" in rendered
    assert "Goes running\n" in rendered + "\n" and "Goes running (" not in rendered
    stated_only = await harness.service.get_context(
        session, scope, t.conversation_id, "morning run", ContextLimits(include_inferred=False)
    )
    assert [f.key for f in stated_only.facts] == ["interests.running"]


async def test_grounding_ids_for_drafts(session: AsyncSession, harness: Harness) -> None:
    scope = await make_user(session)
    t = await make_thread(session, scope)
    m1 = await say(session, t, "I have a dog")
    harness.provider.push(facts(fact("pets.dog", "Has a dog", ["m1"])))
    await harness.service.extract_from_messages(session, scope, t.conversation_id)
    harness.provider.push({"summary": "Talked about pets."})
    s = await harness.service.update_summary(session, scope, t.conversation_id)
    assert s is not None
    ctx = await harness.service.get_context(session, scope, t.conversation_id, "dog")
    assert ctx.memory_ids == [ctx.facts[0].id, s.id]
    assert ctx.source_message_ids == [m1]


# --------------------------------------------------------------------------- vector path


class FailingEmbedder:
    name = "failing"

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        raise RuntimeError("provider down")


async def test_vector_retrieval_and_index_partitioning(
    session: AsyncSession, make_harness: Callable[..., Harness]
) -> None:
    index = InMemoryVectorIndex()
    embedder = HashingEmbedder(dim=512)
    h = make_harness(retriever=VectorRetriever(embedder, index))
    alice = await make_user(session)
    bob = await make_user(session)
    a = await make_thread(session, alice)
    b = await make_thread(session, bob)
    await _seed(session, h, a, [("pets.dog", "Has a dog named Pixel"), ("work.job", "Pilot")])
    await _seed(session, h, b, [("pets.dog", "Has a dog named Rex")])

    assert index.size(alice.user_id, a.contact_id) == 2
    assert index.size(bob.user_id, b.contact_id) == 1
    ctx = await h.service.get_context(session, alice, a.conversation_id, "dog Pixel")
    assert ctx.strategy == "vector"
    assert ctx.facts[0].content == "Has a dog named Pixel"
    assert all("Rex" not in f.content for f in ctx.facts)

    # Deleting removes the vector, and DB eligibility filtering applies regardless.
    await h.service.delete_fact(session, alice, ctx.facts[0].id)
    assert index.size(alice.user_id, a.contact_id) == 1
    ctx2 = await h.service.get_context(session, alice, a.conversation_id, "dog Pixel")
    assert all("Pixel" not in f.content for f in ctx2.facts)


async def test_stale_index_entry_cannot_surface_ineligible_fact(
    session: AsyncSession, make_harness: Callable[..., Harness]
) -> None:
    index = InMemoryVectorIndex()
    h = make_harness(retriever=VectorRetriever(HashingEmbedder(), index))
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await _seed(session, h, t, [("pets.dog", "Has a dog named Pixel")])
    # Plant a vector for an ID that is not an eligible candidate (e.g. a leaked row).
    rogue = uuid.uuid4()
    (vec,) = await HashingEmbedder().embed(["dog Pixel"])
    await index.upsert(scope.user_id, t.contact_id, rogue, vec)
    ctx = await h.service.get_context(session, scope, t.conversation_id, "dog Pixel")
    assert rogue not in ctx.memory_ids


async def test_embedding_failure_falls_back_to_lexical(
    session: AsyncSession, make_harness: Callable[..., Harness]
) -> None:
    h = make_harness(retriever=VectorRetriever(FailingEmbedder()))
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await _seed(session, h, t, [("pets.dog", "Has a dog"), ("work.job", "Pilot")])
    ctx = await h.service.get_context(session, scope, t.conversation_id, "dog")
    assert ctx.strategy == "lexical"
    assert ctx.facts[0].content == "Has a dog"


async def test_lexical_is_default_and_needs_no_provider(
    session: AsyncSession, harness: Harness
) -> None:
    assert isinstance(harness.service.retriever, LexicalRetriever)
    scope = await make_user(session)
    t = await make_thread(session, scope)
    await _seed(session, harness, t, [("pets.dog", "Has a dog")])
    calls = harness.provider.calls
    ctx = await harness.service.get_context(session, scope, t.conversation_id, "dog")
    assert ctx.strategy == "lexical" and harness.provider.calls == calls


def test_tokenize_and_cosine_helpers() -> None:
    assert tokenize("The DOGS and the cat's toys") == ["dog", "cat", "toy"]
    assert cosine([1, 0], [1, 0]) == pytest.approx(1.0)
    assert cosine([0, 0], [1, 0]) == 0.0
    with pytest.raises(ValueError):
        cosine([1], [1, 2])


async def test_embedding_error_type_exported() -> None:
    assert issubclass(EmbeddingError, Exception)
