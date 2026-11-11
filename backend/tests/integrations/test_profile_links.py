"""Contract 1.3.2 (D-032): a profile linked to a conversation is grounded context for
reply drafts.

Covers: linking (user and stable-ID ``device_match``), the draft citing a real profile
field, a citation of a non-existent field being rejected, unlinking and deleting
stopping the use, sensitive and image attributes never reaching the model, cross-user
isolation, the stored (pipeline) draft, and contract validation of every body.
"""

import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.providers.base import ProviderRequest
from app.ai.providers.mock import MockStep
from app.ai.reply_generator import DraftDecision, DraftReason, profile_context_fields
from app.api.drafts import get_reply_generator
from app.api.drafts import router as drafts_router
from app.connectors.events import ConnectorEvent
from app.db.enums import AgentActionType
from app.db.models import AgentAction, Conversation, ConversationProfileLink, Draft, Profile
from app.db.repositories import ConnectionRepository
from app.services.automation import draft_to_contract
from tests.automation.conftest import (
    ScriptedLLM,
    World,
    assert_contract,
    headers,
    make_user,
    make_world,
    problem,
    reply,
)

PROMPTS = [
    {"prompt": "A shower thought I recently had", "answer": "Do fish get thirsty?"},
    {"prompt": "Typical Sunday", "answer": "Farmers market, then a long trail run."},
]
ATTRIBUTES: dict[str, Any] = {
    "job_title": "Pastry chef",
    "hometown": "Example Falls",
    "religion": "Zoroastrian",
    "height": "5 ft 7",
    "age": 31,
    "photos": ["https://images.example.invalid/1.jpg"],
    "photo_caption": "On a boat",
}


@dataclass
class RecordingLLM(ScriptedLLM):
    """A scripted model that keeps the reply prompts it was asked."""

    prompts: list[str] = field(default_factory=list)

    def __call__(self, request: ProviderRequest) -> MockStep:
        if request.schema_name == "ReplyOutput":
            self.prompts.append(request.messages[-1].content)
        return super().__call__(request)


def cited(text: str, *refs: str, **extra: Any) -> dict[str, Any]:
    return reply(text) | {"profile_refs": list(refs)} | extra


def profile_body(external_id: str = "screen:profile-a", **over: Any) -> dict[str, Any]:
    body: dict[str, Any] = {
        "provider": "hinge",
        "external_profile_id": external_id,
        "source": "manual",
        "display_name": "Riley",
        "prompts": PROMPTS,
        "attributes": ATTRIBUTES,
        "source_observed_at": "2026-11-01T11:00:00Z",
    }
    return body | over


@dataclass
class Setup:
    world: World
    llm: RecordingLLM
    client: httpx.AsyncClient
    conversation_id: str
    profile_id: str

    @property
    def link_url(self) -> str:
        return f"/v1/conversations/{self.conversation_id}/profile-link"

    async def draft(self, scope: Any = None) -> httpx.Response:
        return await self.client.post(
            f"/v1/conversations/{self.conversation_id}/drafts",
            json={},
            headers=headers(scope or self.world.scope),
        )


async def _inbound(session: AsyncSession, world: World, text: str, *, run: bool = False) -> Any:
    connection = await ConnectionRepository(session, world.scope).require(world.connection_id)
    raw = world.event(text, thread="match-thread-77", sender="match-77")
    result = await world.services.ingest.ingest(
        session,
        world.scope,
        connection,
        ConnectorEvent.model_validate(raw),
        request_hash=world.services.ingest.request_hash(raw),
    )
    assert result.new_inbound_message_id is not None
    if run:
        return await world.controller.handle_inbound_message(
            session, world.scope, result.new_inbound_message_id
        )
    return None


async def setup(session: AsyncSession, make_v13_client: Any, *, link: bool = True) -> Setup:
    llm = RecordingLLM()
    world = await make_world(
        session, platform="hinge", send="UNSUPPORTED", device_side=True, mode="manual", llm=llm
    )
    client = make_v13_client(world)
    app = client._transport.app  # noqa: SLF001 - the test app behind the ASGI transport
    app.include_router(drafts_router)
    app.dependency_overrides[get_reply_generator] = lambda: world.controller.generator
    await _inbound(session, world, "What do you do on weekends?")
    conversation_id = str(
        await session.scalar(
            select(Conversation.id).where(Conversation.user_id == world.scope.user_id)
        )
    )
    created = await client.post("/v1/profiles", json=profile_body(), headers=headers(world.scope))
    assert created.status_code == 201, created.text
    s = Setup(world, llm, client, conversation_id, created.json()["id"])
    if link:
        resp = await client.put(
            s.link_url, json={"profile_id": s.profile_id}, headers=headers(world.scope)
        )
        assert resp.status_code == 200, resp.text
        assert_contract("ConversationProfileLink", resp.json())
    return s


async def _audit(session: AsyncSession, world: World, kind: AgentActionType) -> list[AgentAction]:
    rows = await session.scalars(
        select(AgentAction)
        .where(AgentAction.user_id == world.scope.user_id, AgentAction.type == kind)
        .order_by(AgentAction.id)
    )
    return list(rows.all())


# --------------------------------------------------------------------------- drafts


async def test_draft_with_linked_profile_cites_a_real_field(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(cited("A farmers market and a trail run sounds great. Which trail?", "p2"))

    resp = await s.draft()

    assert resp.status_code == 201, resp.text
    draft = resp.json()
    assert_contract("Draft", draft)
    assert draft["decision"] == "send_candidate"
    assert draft["grounding"]["profile_id"] == s.profile_id
    assert draft["grounding"]["profile_fields"] == ["prompts/1"]
    # The cited pointer is a real field of the stored profile.
    profile = await session.get(Profile, uuid.UUID(s.profile_id))
    assert profile is not None and profile.prompts[1]["prompt"] == "Typical Sunday"
    prompt = s.llm.prompts[-1]
    assert "Profile fields: " in prompt
    assert "Farmers market, then a long trail run." in prompt
    assert '"ref": "p2"' in prompt
    # The audit entry carries IDs and pointers, never profile text.
    audit = (await _audit(session, s.world, AgentActionType.DRAFT_GENERATED))[-1]
    assert audit.details["profile_fields"] == ["prompts/1"]
    assert audit.details["profile_linked"] is True
    assert "Farmers market" not in str(audit.details)


async def test_citation_of_a_non_existent_profile_field_is_rejected(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(cited("I loved your prompt about sailing!", "p9"))

    resp = await s.draft()

    draft = resp.json()
    assert_contract("Draft", draft)
    assert draft["decision"] == "no_send" and draft["text"] is None
    assert draft["grounding"]["profile_fields"] == [] and draft["grounding"]["profile_id"] is None
    audit = (await _audit(session, s.world, AgentActionType.DRAFT_GENERATED))[-1]
    assert audit.reason_code == DraftReason.INVALID_GROUNDING.value
    assert audit.details["violations"] == ["unknown_ref"]


async def test_profile_ref_in_the_wrong_list_is_rejected(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(reply("Nice!", message_refs=["m1"]) | {"profile_refs": ["m1"]})
    draft = (await s.draft()).json()
    assert draft["decision"] == "no_send"
    s.llm.replies.append(reply("Nice!") | {"memory_refs": ["p1"]})
    assert (await s.draft()).json()["decision"] == "no_send"


async def test_a_profile_field_never_grounds_a_fact_about_the_user(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(
        cited(
            "I'm a pastry chef too!",
            "p3",
            user_claims=[{"kind": "profession", "refs": ["p3"]}],
        )
    )
    draft = (await s.draft()).json()
    assert draft["decision"] == "ask_user" and draft["text"] is None
    audit = (await _audit(session, s.world, AgentActionType.DRAFT_GENERATED))[-1]
    assert audit.reason_code == DraftReason.FABRICATED_PERSONAL_FACT.value


async def test_sensitive_and_image_attributes_never_reach_the_model_or_a_draft(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(cited("Pastry chef, that's a fun job!", "p4"))
    ok = (await s.draft()).json()
    assert ok["grounding"]["profile_fields"] == ["attributes/job_title"]
    prompt = s.llm.prompts[-1]
    for withheld in ("Zoroastrian", "5 ft 7", "images.example.invalid", "On a boat", "31"):
        assert withheld not in prompt.split("Profile fields: ")[1].split("\n\n")[0], withheld
    assert "Riley" not in prompt  # the display name is not profile context either

    profile = await session.get(Profile, uuid.UUID(s.profile_id))
    assert profile is not None
    pointers = [f.pointer for f in profile_context_fields(profile)]
    assert pointers == ["prompts/0", "prompts/1", "attributes/hometown", "attributes/job_title"]

    # A model that states the withheld value anyway is refused in code.
    s.llm.replies.append(cited("So you're Zoroastrian? Tell me more!", "p1"))
    refused = (await s.draft()).json()
    assert refused["decision"] == "no_send" and refused["text"] is None
    audit = (await _audit(session, s.world, AgentActionType.DRAFT_GENERATED))[-1]
    assert audit.reason_code == DraftReason.SENSITIVE_PROFILE_ATTRIBUTE.value
    assert "Zoroastrian" not in str(audit.details)


async def test_unlinking_stops_the_use(session: AsyncSession, make_v13_client: Any) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(cited("Do fish get thirsty? Great question.", "p1"))
    assert (await s.draft()).json()["grounding"]["profile_fields"] == ["prompts/0"]

    gone = await s.client.delete(s.link_url, headers=headers(s.world.scope))
    assert gone.status_code == 204
    problem(await s.client.get(s.link_url, headers=headers(s.world.scope)), 404, "not_found")
    problem(await s.client.delete(s.link_url, headers=headers(s.world.scope)), 404, "not_found")

    # The next prompt has no profile, and a model still citing it is rejected.
    s.llm.replies.append(cited("Do fish get thirsty? Great question.", "p1"))
    after = (await s.draft()).json()
    assert "Profile fields: " not in s.llm.prompts[-1]
    assert "Do fish get thirsty?" not in s.llm.prompts[-1]
    assert after["decision"] == "no_send"
    assert after["grounding"]["profile_id"] is None and after["grounding"]["profile_fields"] == []
    # A plain reply still works, with no profile grounding.
    plain = (await s.draft()).json()
    assert plain["decision"] == "send_candidate" and plain["grounding"]["profile_fields"] == []
    assert [
        a.details["origin"]
        for a in await _audit(session, s.world, AgentActionType.PROFILE_UNLINKED)
    ] == ["user"]


async def test_deleting_the_profile_removes_the_link_and_the_use(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    resp = await s.client.delete(f"/v1/profiles/{s.profile_id}", headers=headers(s.world.scope))
    assert resp.status_code == 204
    assert await session.get(Profile, uuid.UUID(s.profile_id)) is None
    assert (
        await session.scalar(
            select(ConversationProfileLink.id).where(
                ConversationProfileLink.user_id == s.world.scope.user_id
            )
        )
        is None
    )
    problem(await s.client.get(s.link_url, headers=headers(s.world.scope)), 404, "not_found")
    problem(
        await s.client.delete(f"/v1/profiles/{s.profile_id}", headers=headers(s.world.scope)),
        404,
        "not_found",
    )
    assert (await s.draft()).json()["grounding"]["profile_fields"] == []
    assert "Profile fields: " not in s.llm.prompts[-1]
    deleted = await _audit(session, s.world, AgentActionType.PROFILE_DELETED)
    assert len(deleted) == 1 and deleted[0].details["unlinked_conversations"] == 1
    assert "Riley" not in str(deleted[0].details) and "fish" not in str(deleted[0].details)


async def test_stored_pipeline_draft_keeps_its_profile_grounding(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client)
    s.llm.replies.append(cited("Which trail is your favorite?", "p2", message_refs=["m2"]))
    await _inbound(session, s.world, "Any plans this Sunday?", run=True)
    stored = (
        await session.scalars(select(Draft).where(Draft.user_id == s.world.scope.user_id))
    ).one()
    assert stored.grounding_profile_fields == ["prompts/1"]
    assert str(stored.grounding_profile_id) == s.profile_id
    body = draft_to_contract(stored)
    assert_contract("Draft", body)
    assert body["grounding"]["profile_fields"] == ["prompts/1"]


# --------------------------------------------------------------------------- linking


async def test_link_is_replaceable_idempotent_and_audited(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client, link=False)
    h = headers(s.world.scope)
    problem(await s.client.get(s.link_url, headers=h), 404, "not_found")
    first = (await s.client.put(s.link_url, json={"profile_id": s.profile_id}, headers=h)).json()
    again = (await s.client.put(s.link_url, json={"profile_id": s.profile_id}, headers=h)).json()
    assert again == first and first["origin"] == "user"
    got = await s.client.get(s.link_url, headers=h)
    assert got.status_code == 200 and got.json() == first
    other = (
        await s.client.post(
            "/v1/profiles", json=profile_body("screen:profile-b"), headers=headers(s.world.scope)
        )
    ).json()["id"]
    replaced = (await s.client.put(s.link_url, json={"profile_id": other}, headers=h)).json()
    assert replaced["id"] == first["id"] and replaced["profile_id"] == other
    assert len(await _audit(session, s.world, AgentActionType.PROFILE_LINKED)) == 2
    # Unknown fields (a name, a score) and unknown origins are refused.
    for bad in (
        {"profile_id": s.profile_id, "display_name": "Riley"},
        {"profile_id": s.profile_id, "confidence": 0.9},
        {"profile_id": s.profile_id, "origin": "name_match"},
        {},
    ):
        problem(await s.client.put(s.link_url, json=bad, headers=h), 422, "validation_error")
    problem(
        await s.client.put(s.link_url, json={"profile_id": str(uuid.uuid4())}, headers=h),
        422,
        "validation_error",
    )


async def test_device_match_needs_the_same_stable_platform_id(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client, link=False)
    h = headers(s.world.scope)
    # Same display name, different ID: never linked automatically.
    body = {"profile_id": s.profile_id, "origin": "device_match"}
    refused = problem(await s.client.put(s.link_url, json=body, headers=h), 422, "validation_error")
    assert refused["errors"][0]["pointer"] == "/origin"
    problem(await s.client.get(s.link_url, headers=h), 404, "not_found")
    # The profile's platform ID equals the conversation's thread ID: accepted.
    stable = (
        await s.client.post(
            "/v1/profiles", json=profile_body("match-thread-77"), headers=headers(s.world.scope)
        )
    ).json()["id"]
    ok = await s.client.put(
        s.link_url, json={"profile_id": stable, "origin": "device_match"}, headers=h
    )
    assert ok.status_code == 200 and ok.json()["origin"] == "device_match"
    assert_contract("ConversationProfileLink", ok.json())
    # ... but not for a profile of another platform with the same ID.
    made = await s.client.post(
        "/v1/profiles",
        json=profile_body("match-thread-77", provider="tinder"),
        headers=headers(s.world.scope),
    )
    assert made.status_code == 201, made.text
    tinder = made.json()["id"]
    problem(
        await s.client.put(
            s.link_url, json={"profile_id": tinder, "origin": "device_match"}, headers=h
        ),
        422,
        "validation_error",
    )


async def test_linked_profile_leaves_the_review_queue(
    session: AsyncSession,
    make_v13_client: Any,
) -> None:
    s = await setup(session, make_v13_client, link=False)
    h = headers(s.world.scope)
    queue = (await s.client.get("/v1/profiles/review-queue", headers=h)).json()["items"]
    assert [p["id"] for p in queue] == [s.profile_id]
    await s.client.put(s.link_url, json={"profile_id": s.profile_id}, headers=h)
    assert (await s.client.get("/v1/profiles/review-queue", headers=h)).json()["items"] == []
    await s.client.delete(s.link_url, headers=h)
    queue = (await s.client.get("/v1/profiles/review-queue", headers=h)).json()["items"]
    assert [p["id"] for p in queue] == [s.profile_id]


async def test_cross_user_isolation(session: AsyncSession, make_v13_client: Any) -> None:
    s = await setup(session, make_v13_client)
    stranger = await make_user(session)
    sh = headers(stranger)
    # A stranger sees, changes and deletes nothing of ours.
    problem(await s.client.get(s.link_url, headers=sh), 404, "not_found")
    problem(
        await s.client.put(s.link_url, json={"profile_id": s.profile_id}, headers=sh),
        404,
        "not_found",
    )
    problem(await s.client.delete(s.link_url, headers=sh), 404, "not_found")
    problem(await s.client.delete(f"/v1/profiles/{s.profile_id}", headers=sh), 404, "not_found")
    problem(await s.draft(stranger), 404, "not_found")
    # Their own profile cannot be attached to our conversation, nor ours to theirs.
    theirs = (await s.client.post("/v1/profiles", json=profile_body(), headers=sh)).json()["id"]
    assert theirs != s.profile_id
    problem(
        await s.client.put(s.link_url, json={"profile_id": theirs}, headers=headers(s.world.scope)),
        422,
        "validation_error",
    )
    # Everything of ours is still in place and still used.
    still = await s.client.get(s.link_url, headers=headers(s.world.scope))
    assert still.status_code == 200 and still.json()["profile_id"] == s.profile_id
    s.llm.replies.append(cited("Do fish get thirsty? Great question.", "p1"))
    assert (await s.draft()).json()["grounding"]["profile_id"] == s.profile_id
    # Deleting their profile does not touch ours.
    assert (await s.client.delete(f"/v1/profiles/{theirs}", headers=sh)).status_code == 204
    assert await session.get(Profile, uuid.UUID(s.profile_id)) is not None


# --------------------------------------------------------------------------- static


def test_nothing_but_the_link_service_writes_profile_links() -> None:
    """ADR 0008: no path links by name or inference. Only ``ProfileLinkService`` (through
    its repository) creates a link, and its request has no name or score field."""
    app_dir = Path(__file__).resolve().parents[2] / "app"
    users = sorted(
        str(p.relative_to(app_dir))
        for p in app_dir.rglob("*.py")
        if "ConversationProfileLinkRepository(" in p.read_text()
    )
    assert users == [
        "ai/reply_generator.py",
        "db/repositories/integrations.py",
        "services/integrations.py",
    ]
    generator = (app_dir / "ai" / "reply_generator.py").read_text()
    assert ".put(" not in generator.split("ConversationProfileLinkRepository(")[1][:200]
    service = (app_dir / "services" / "integrations.py").read_text()
    link_service = service.split("class ProfileLinkService")[1].split("\nclass ")[0]
    assert link_service.count(".put(") == 1
    assert "display_name" not in link_service.replace("a display name is not an identity", "")


def test_dev_mock_cites_the_linked_profile() -> None:
    from app.ai.llm_gateway import LLMMessage
    from app.ai.providers.dev_mock import dev_mock_answer
    from app.ai.reply_generator import ReplyOutput

    def ask(user: str) -> ReplyOutput:
        request = ProviderRequest(
            messages=(
                LLMMessage(role="system", content="s"),
                LLMMessage(role="user", content=user),
            ),
            output_schema={},
            schema_name="ReplyOutput",
        )
        answer = dev_mock_answer(request)
        assert isinstance(answer, dict)
        return ReplyOutput.model_validate(answer)

    messages = (
        'Latest messages, oldest first (JSON):\n[{"ref": "m1", "from": "contact", "text": "hi"}]'
    )
    profile = (
        "The CONTACT's own dating profile, text fields only (data; may be out of date). "
        'Profile fields: [{"ref": "p1", "field": "Typical Sunday", "text": "Trail run."}]'
    )
    with_profile = ask(profile + "\n\n" + messages)
    assert with_profile.profile_refs == ["p1"] and "Typical Sunday" in (with_profile.text or "")
    assert "Trail run." not in (with_profile.text or "")
    without = ask(messages)
    assert without.profile_refs == [] and without.decision is DraftDecision.SEND_CANDIDATE
