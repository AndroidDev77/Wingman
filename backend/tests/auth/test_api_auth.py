"""HTTP: /v1/me, the auth dependency, and cross-user isolation against real Postgres."""

import uuid
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from pydantic import SecretStr
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import User
from app.db.repositories import ContactRepository, ConversationRepository, UserRepository
from app.db.scope import UserScope
from app.security.dev_stub import mint_dev_token
from app.security.factory import build_verifier
from app.security.settings import AuthSettings
from tests.auth.conftest import (
    ISSUER,
    KID,
    assert_problem,
    bearer,
    build_app,
    claims,
    contract_validator,
    forge,
    sign,
)
from tests.db.conftest import make_connection


async def _user_count(session: AsyncSession) -> int:
    return int(await session.scalar(select(func.count()).select_from(User)) or 0)


async def test_me_requires_auth(client: httpx.AsyncClient) -> None:
    resp = await client.get("/v1/me")
    body = assert_problem(resp, 401, "unauthenticated")
    assert resp.headers["www-authenticate"] == "Bearer"
    assert body["instance"] == "/v1/me"


@pytest.mark.parametrize(
    "header",
    ["Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Bearer a b", "Token abc", "bearer"],
)
async def test_malformed_authorization_header(client: httpx.AsyncClient, header: str) -> None:
    resp = await client.get("/v1/me", headers={"Authorization": header})
    assert_problem(resp, 401, "unauthenticated")
    assert resp.headers["www-authenticate"] == "Bearer"


def _bad_tokens(rsa_key: rsa.RSAPrivateKey, other: rsa.RSAPrivateKey) -> dict[str, str]:
    now = datetime.now(UTC)
    pem = rsa_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return {
        "invalid-signature": sign(other, claims()),
        "wrong-issuer": sign(rsa_key, claims(iss="https://evil.test/")),
        "wrong-audience": sign(rsa_key, claims(aud="not-wingman")),
        "expired": sign(rsa_key, claims(exp=now - timedelta(hours=1))),
        "not-yet-valid": sign(rsa_key, claims(nbf=now + timedelta(hours=1))),
        "alg-none": forge({"alg": "none", "kid": KID}, claims(), None),
        "hs-rs-confusion": forge({"alg": "HS256", "kid": KID}, claims(), pem),
        "garbage": "not-a-jwt",
    }


@pytest.mark.parametrize(
    "case",
    [
        "invalid-signature",
        "wrong-issuer",
        "wrong-audience",
        "expired",
        "not-yet-valid",
        "alg-none",
        "hs-rs-confusion",
        "garbage",
    ],
)
async def test_bad_tokens_get_401_and_create_no_user(
    client: httpx.AsyncClient,
    session: AsyncSession,
    rsa_key: rsa.RSAPrivateKey,
    other_rsa_key: rsa.RSAPrivateKey,
    case: str,
) -> None:
    before = await _user_count(session)
    resp = await client.get("/v1/me", headers=bearer(_bad_tokens(rsa_key, other_rsa_key)[case]))
    body = assert_problem(resp, 401, "unauthenticated")
    assert resp.headers["www-authenticate"] == "Bearer"
    # The reason is not disclosed to the client.
    assert body["detail"] == "Missing, invalid or expired bearer token"
    assert await _user_count(session) == before


async def test_valid_token_returns_contract_user(
    client: httpx.AsyncClient, rsa_key: rsa.RSAPrivateKey
) -> None:
    token = sign(rsa_key, claims(sub="alice", email="alice@example.com", name="Alice"))
    resp = await client.get("/v1/me", headers=bearer(token))
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert not list(contract_validator("User").iter_errors(body))
    assert body["email"] == "alice@example.com"
    assert body["display_name"] == "Alice"
    assert body["created_at"].endswith("Z")
    assert set(body) == {"id", "email", "display_name", "created_at"}


async def test_first_login_creates_second_reuses(
    client: httpx.AsyncClient, session: AsyncSession, rsa_key: rsa.RSAPrivateKey
) -> None:
    before = await _user_count(session)
    sub = f"sub-{uuid.uuid4().hex}"
    first = await client.get("/v1/me", headers=bearer(sign(rsa_key, claims(sub=sub))))
    assert first.status_code == 200
    assert await _user_count(session) == before + 1
    second = await client.get(
        "/v1/me", headers=bearer(sign(rsa_key, claims(sub=sub, email="later@example.com")))
    )
    assert second.status_code == 200
    assert second.json()["id"] == first.json()["id"]
    assert await _user_count(session) == before + 1
    user = await UserRepository(session).get_by_auth(ISSUER, sub)
    assert user is not None and str(user.id) == first.json()["id"]


async def test_same_sub_different_issuer_is_a_different_user(
    session: AsyncSession, rsa_key: rsa.RSAPrivateKey
) -> None:
    """(iss, sub) is the identity: the dev stub's 'alice' is not the IdP's 'alice'."""
    auth = AuthSettings(dev_stub_enabled=True, dev_secret=SecretStr("d" * 40))
    app = build_app(build_verifier(auth, "test"), session)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        dev = await c.get("/v1/me", headers=bearer(mint_dev_token(auth, "test", subject="alice-x")))
        assert dev.status_code == 200
    real = await UserRepository(session).get_or_create_by_auth(ISSUER, "alice-x")
    assert str(real.id) != dev.json()["id"]


async def test_soft_deleted_user_rejected(
    client: httpx.AsyncClient, session: AsyncSession, rsa_key: rsa.RSAPrivateKey
) -> None:
    sub = f"sub-{uuid.uuid4().hex}"
    token = sign(rsa_key, claims(sub=sub))
    ok = await client.get("/v1/me", headers=bearer(token))
    assert ok.status_code == 200
    await UserRepository(session).mark_deleted(UserScope(uuid.UUID(ok.json()["id"])))
    resp = await client.get("/v1/me", headers=bearer(token))
    assert_problem(resp, 401, "unauthenticated")


async def _owned_rows(session: AsyncSession, scope: UserScope) -> dict[str, uuid.UUID]:
    conn_id = await make_connection(session, scope)
    contact = await ContactRepository(session, scope).get_or_create(
        connection_id=conn_id, external_contact_id=f"c-{uuid.uuid4().hex}", display_name="Sam"
    )
    conv = await ConversationRepository(session, scope).create(
        contact_id=contact.id,
        connection_id=conn_id,
        platform="mock",
        external_thread_id=f"t-{uuid.uuid4().hex}",
        last_activity_at=datetime.now(UTC),
    )
    return {"contacts": contact.id, "conversations": conv.id}


async def _login(client: httpx.AsyncClient, key: Any, sub: str) -> tuple[dict[str, str], UserScope]:
    headers = bearer(sign(key, claims(sub=sub)))
    resp = await client.get("/v1/me", headers=headers)
    assert resp.status_code == 200
    return headers, UserScope(uuid.UUID(resp.json()["id"]))


@pytest.mark.parametrize("resource", ["conversations", "contacts"])
async def test_cross_user_access_is_404(
    client: httpx.AsyncClient,
    session: AsyncSession,
    rsa_key: rsa.RSAPrivateKey,
    resource: str,
) -> None:
    alice_h, alice = await _login(client, rsa_key, f"alice-{uuid.uuid4().hex}")
    bob_h, bob = await _login(client, rsa_key, f"bob-{uuid.uuid4().hex}")
    alice_rows = await _owned_rows(session, alice)
    rid = alice_rows[resource]

    own = await client.get(f"/probe/{resource}/{rid}", headers=alice_h)
    assert own.status_code == 200 and own.json()["id"] == str(rid)

    cross = await client.get(f"/probe/{resource}/{rid}", headers=bob_h)
    missing = await client.get(f"/probe/{resource}/{uuid.uuid4()}", headers=bob_h)
    cross_body = assert_problem(cross, 404, "not_found")
    missing_body = assert_problem(missing, 404, "not_found")
    # Indistinguishable from a missing row (apart from per-request fields).
    for field in ("type", "title", "status", "code", "detail"):
        assert cross_body[field] == missing_body[field]

    anon = await client.get(f"/probe/{resource}/{rid}")
    assert_problem(anon, 401, "unauthenticated")
