"""Fixtures for WING-004 auth tests.

Keys are generated once per session; no network. Real-Postgres fixtures are reused from
``tests/db/conftest.py`` (same server-selection rules: ``WINGMAN_TEST_DATABASE_URL`` or a
throwaway ``initdb`` cluster; never a silent skip).
"""

import base64
import hashlib
import hmac
import json
import uuid
from collections.abc import AsyncIterator, Iterator
from datetime import UTC, datetime, timedelta
from functools import cache
from pathlib import Path
from typing import Annotated, Any

import httpx
import jwt
import pytest
import yaml
from cryptography.hazmat.primitives.asymmetric import ec, rsa
from fastapi import APIRouter, Depends, FastAPI
from jsonschema import Draft202012Validator, FormatChecker
from jwt.algorithms import ECAlgorithm, RSAAlgorithm
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.auth import create_auth_router
from app.api.errors import register_error_handlers
from app.db.repositories import ContactRepository, ConversationRepository
from app.db.session import get_session
from app.security.factory import get_token_verifier
from app.security.scoped import scoped_repository
from app.security.tokens import JwtVerifier, StaticKeySource, TokenVerifier

# Re-exported so pytest registers them for tests/auth.
from tests.db.conftest import migrated_db_url, pg_server_url, session  # noqa: F401

ISSUER = "https://idp.test/"
AUDIENCE = "wingman-api"
KID = "test-rsa-1"
EC_KID = "test-ec-1"
OPENAPI = Path(__file__).resolve().parents[3] / "contracts" / "openapi.yaml"


# --------------------------------------------------------------------------- keys


@pytest.fixture(scope="session")
def rsa_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(scope="session")
def other_rsa_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(scope="session")
def ec_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


def rsa_jwk(key: rsa.RSAPrivateKey, kid: str = KID, alg: str | None = "RS256") -> dict[str, Any]:
    jwk: dict[str, Any] = RSAAlgorithm.to_jwk(key.public_key(), as_dict=True)
    jwk |= {"kid": kid, "use": "sig"}
    if alg:
        jwk["alg"] = alg
    return jwk


def ec_jwk(key: ec.EllipticCurvePrivateKey, kid: str = EC_KID) -> dict[str, Any]:
    jwk: dict[str, Any] = ECAlgorithm.to_jwk(key.public_key(), as_dict=True)
    jwk |= {"kid": kid, "use": "sig", "alg": "ES256"}
    return jwk


@pytest.fixture(scope="session")
def jwks(rsa_key: rsa.RSAPrivateKey, ec_key: ec.EllipticCurvePrivateKey) -> dict[str, Any]:
    return {"keys": [rsa_jwk(rsa_key), ec_jwk(ec_key)]}


def claims(**overrides: Any) -> dict[str, Any]:
    now = datetime.now(UTC)
    base: dict[str, Any] = {
        "iss": ISSUER,
        "aud": AUDIENCE,
        "sub": f"user-{uuid.uuid4().hex}",
        "iat": now,
        "nbf": now,
        "exp": now + timedelta(minutes=10),
    }
    base |= overrides
    return {k: v for k, v in base.items() if v is not None}


def sign(key: Any, payload: dict[str, Any], *, alg: str = "RS256", kid: str | None = KID) -> str:
    headers = {"kid": kid} if kid else None
    return jwt.encode(payload, key, algorithm=alg, headers=headers)


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def _json_default(v: Any) -> Any:
    if isinstance(v, datetime):
        return int(v.timestamp())
    raise TypeError(type(v))


def forge(header: dict[str, Any], payload: dict[str, Any], hmac_secret: bytes | None) -> str:
    """Hand-build a JWT (PyJWT refuses to make the malicious ones)."""
    h = _b64(json.dumps(header).encode())
    p = _b64(json.dumps(payload, default=_json_default).encode())
    signing_input = f"{h}.{p}".encode()
    sig = (
        ""
        if hmac_secret is None
        else _b64(hmac.new(hmac_secret, signing_input, hashlib.sha256).digest())
    )
    return f"{h}.{p}.{sig}"


@pytest.fixture
def verifier(jwks: dict[str, Any]) -> JwtVerifier:
    return JwtVerifier(
        issuer=ISSUER,
        audience=AUDIENCE,
        algorithms=["RS256", "ES256"],
        key_source=StaticKeySource({k["kid"]: k for k in jwks["keys"]}),
        leeway_s=30,
    )


# --------------------------------------------------------------------------- contract


@cache
def openapi() -> dict[str, Any]:
    doc: dict[str, Any] = yaml.safe_load(OPENAPI.read_text())
    return doc


def contract_validator(schema_name: str) -> Draft202012Validator:
    schema = {
        "$ref": f"#/components/schemas/{schema_name}",
        "components": openapi()["components"],
    }
    return Draft202012Validator(schema, format_checker=FormatChecker())


def assert_problem(resp: httpx.Response, status: int, code: str) -> dict[str, Any]:
    assert resp.status_code == status, resp.text
    assert resp.headers["content-type"].startswith("application/problem+json")
    body: dict[str, Any] = resp.json()
    errors = sorted(contract_validator("Problem").iter_errors(body), key=str)
    assert not errors, [e.message for e in errors]
    assert body["code"] == code
    assert body["status"] == status
    assert body["type"] == f"https://wingman.invalid/problems/{code}"
    return body


# --------------------------------------------------------------------------- app


def build_app(token_verifier: TokenVerifier, db: AsyncSession | None = None) -> FastAPI:
    """The WING-004 router + error handlers + two protected probe routes."""
    app = FastAPI()
    register_error_handlers(app)
    app.include_router(create_auth_router())

    probes = APIRouter(prefix="/probe")
    Conversations = Annotated[
        ConversationRepository, Depends(scoped_repository(ConversationRepository))
    ]
    Contacts = Annotated[ContactRepository, Depends(scoped_repository(ContactRepository))]

    @probes.get("/conversations/{conversation_id}")
    async def get_conversation(conversation_id: uuid.UUID, repo: Conversations) -> dict[str, str]:
        conv = await repo.require(conversation_id)
        return {"id": str(conv.id)}

    @probes.get("/contacts/{contact_id}")
    async def get_contact(contact_id: uuid.UUID, repo: Contacts) -> dict[str, str]:
        contact = await repo.require(contact_id)
        return {"id": str(contact.id)}

    app.include_router(probes)
    app.dependency_overrides[get_token_verifier] = lambda: token_verifier
    if db is not None:

        async def _session() -> AsyncIterator[AsyncSession]:
            yield db
            await db.flush()

        app.dependency_overrides[get_session] = _session
    return app


@pytest.fixture
async def client(
    verifier: JwtVerifier,
    session: AsyncSession,  # noqa: F811
) -> AsyncIterator[httpx.AsyncClient]:
    app = build_app(verifier, session)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def clean_auth_env(monkeypatch: pytest.MonkeyPatch) -> Iterator[pytest.MonkeyPatch]:
    """Remove ambient WINGMAN_AUTH_* / WINGMAN_ENV so settings come only from the test."""
    import os

    for name in list(os.environ):
        if name.startswith("WINGMAN_AUTH_") or name == "WINGMAN_ENV":
            monkeypatch.delenv(name)
    monkeypatch.chdir(Path(__file__).parent)  # no stray .env file
    yield monkeypatch
