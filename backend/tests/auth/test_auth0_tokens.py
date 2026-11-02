"""Auth0 readiness (I-014): realistic Auth0 access tokens through the real factory path.

No network. A locally generated RSA key is published through an Auth0-shaped JWKS
(``kid``, ``x5t``, ``x5c``, ``alg: RS256``) served by ``httpx.MockTransport``; the
verifier is built by :func:`app.security.factory.build_verifier` from the same
``WINGMAN_AUTH_*`` settings ``scripts/configure-auth0.sh`` prints.

Token shape follows Auth0's documented access-token profile
(https://auth0.com/docs/secure/tokens/access-tokens/access-token-profiles):
``iss`` is ``https://<tenant>/`` WITH a trailing slash, and when ``openid`` is requested
together with an API ``audience``, ``aud`` is a list
``[<API identifier>, "https://<tenant>/userinfo"]``. Google users have a ``sub`` like
``google-oauth2|<numeric id>`` and access tokens carry no ``email`` by default.
"""

import base64
import hashlib
import uuid
from collections.abc import AsyncIterator, Callable
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import jwt
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from jwt.algorithms import RSAAlgorithm
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import User
from app.db.repositories import UserRepository
from app.security.factory import build_verifier
from app.security.settings import AuthSettings
from app.security.tokens import InvalidTokenError, TokenVerifier
from tests.auth.conftest import bearer, build_app, forge

TENANT = "wingman-test.us.auth0.com"
ISSUER = f"https://{TENANT}/"  # Auth0's iss ends with "/"
JWKS_URL = f"https://{TENANT}/.well-known/jwks.json"
API_ID = "https://api.wingman.test"
USERINFO = f"https://{TENANT}/userinfo"
CLIENT_ID = "aBcDeFgHiJkLmNoPqRsTuVwXyZ012345"
GOOGLE_SUB = "google-oauth2|103547991597142817347"
KID_A = "auth0-kid-current"
KID_B = "auth0-kid-next"


# --------------------------------------------------------------------------- helpers


def auth0_jwk(key: rsa.RSAPrivateKey, kid: str) -> dict[str, Any]:
    """A JWK shaped like the entries at ``https://<tenant>/.well-known/jwks.json``."""
    now = datetime.now(UTC)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, TENANT)])
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - timedelta(days=1))
        .not_valid_after(now + timedelta(days=365))
        .sign(key, hashes.SHA256())
    )
    der = cert.public_bytes(serialization.Encoding.DER)
    jwk: dict[str, Any] = RSAAlgorithm.to_jwk(key.public_key(), as_dict=True)
    jwk |= {
        "kid": kid,
        "use": "sig",
        "alg": "RS256",
        "x5c": [base64.b64encode(der).decode()],
        "x5t": base64.urlsafe_b64encode(hashlib.sha1(der).digest()).rstrip(b"=").decode(),  # noqa: S324 - JWK thumbprint format
    }
    return jwk


def auth0_claims(**overrides: Any) -> dict[str, Any]:
    """An Auth0 access token for API_ID requested with ``openid profile email offline_access``."""
    now = datetime.now(UTC)
    base: dict[str, Any] = {
        "iss": ISSUER,
        "sub": GOOGLE_SUB,
        "aud": [API_ID, USERINFO],
        "iat": now,
        "exp": now + timedelta(hours=24),
        "scope": "openid profile email offline_access",
        "azp": CLIENT_ID,
    }
    base |= overrides
    return {k: v for k, v in base.items() if v is not None}


def sign(key: rsa.RSAPrivateKey, payload: dict[str, Any], *, kid: str = KID_A) -> str:
    return jwt.encode(payload, key, algorithm="RS256", headers={"kid": kid, "typ": "JWT"})


class JwksServer:
    """A mock ``/.well-known/jwks.json`` whose key set can be rotated."""

    def __init__(self, keys: list[dict[str, Any]]) -> None:
        self.keys = keys
        self.requests: list[str] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(str(request.url))
        if str(request.url) != JWKS_URL:
            return httpx.Response(404)
        return httpx.Response(200, json={"keys": self.keys})

    @property
    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self.handler)


def auth0_settings(**overrides: Any) -> AuthSettings:
    """The values ``scripts/configure-auth0.sh`` writes, as AuthSettings."""
    base: dict[str, Any] = {
        "issuer": ISSUER,
        "audience": API_ID,
        "jwks_url": JWKS_URL,
        "algorithms": "RS256",
    }
    return AuthSettings(**(base | overrides))


@pytest.fixture
def key_a() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def key_b() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def jwks_server(key_a: rsa.RSAPrivateKey) -> JwksServer:
    return JwksServer([auth0_jwk(key_a, KID_A)])


@pytest.fixture
def make_verifier(
    clean_auth_env: pytest.MonkeyPatch, jwks_server: JwksServer
) -> Callable[..., TokenVerifier]:
    def _make(**overrides: Any) -> TokenVerifier:
        return build_verifier(
            auth0_settings(**overrides), "prod", jwks_transport=jwks_server.transport
        )

    return _make


# --------------------------------------------------------------------------- accepted


@pytest.mark.parametrize("typ", ["JWT", "at+jwt"])
async def test_realistic_auth0_access_token_accepted(
    make_verifier: Callable[..., TokenVerifier],
    jwks_server: JwksServer,
    key_a: rsa.RSAPrivateKey,
    typ: str,
) -> None:
    token = jwt.encode(auth0_claims(), key_a, algorithm="RS256", headers={"kid": KID_A, "typ": typ})
    ident = await make_verifier().verify(token)
    assert (ident.issuer, ident.subject) == (ISSUER, GOOGLE_SUB)
    # Auth0 access tokens carry no email/name unless an Action adds them: that is fine.
    assert ident.email is None
    assert ident.display_name is None
    assert jwks_server.requests == [JWKS_URL]


async def test_audience_as_plain_string_accepted(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    """Without ``openid`` Auth0 issues ``aud`` as a single string."""
    token = sign(key_a, auth0_claims(aud=API_ID, scope="offline_access"))
    assert (await make_verifier().verify(token)).subject == GOOGLE_SUB


async def test_namespaced_email_claim_is_ignored_but_standard_email_used(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    """If an Action adds a standard ``email`` it is recorded; namespaced claims are not read."""
    v = make_verifier()
    with_email = await v.verify(sign(key_a, auth0_claims(email="jd@example.com")))
    assert with_email.email == "jd@example.com"
    namespaced = await v.verify(sign(key_a, auth0_claims(**{"https://wingman.app/email": "x@y.z"})))
    assert namespaced.email is None


# --------------------------------------------------------------------------- issuer slash


async def test_issuer_without_trailing_slash_in_token_rejected(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(sign(key_a, auth0_claims(iss=f"https://{TENANT}")))


async def test_configured_issuer_without_trailing_slash_rejects_real_auth0_tokens(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    """The match is exact: WINGMAN_AUTH_ISSUER must be ``https://<tenant>/`` WITH the slash."""
    v = make_verifier(issuer=f"https://{TENANT}")
    with pytest.raises(InvalidTokenError, match="unknown issuer"):
        await v.verify(sign(key_a, auth0_claims()))


# --------------------------------------------------------------------------- audience


@pytest.mark.parametrize(
    "aud",
    [
        pytest.param([USERINFO], id="userinfo-only"),
        pytest.param(["https://other-api.test", USERINFO], id="other-api"),
        pytest.param(CLIENT_ID, id="id-token-audience"),
        pytest.param([], id="empty-list"),
        pytest.param(f"{API_ID}/", id="identifier-with-extra-slash"),
    ],
)
async def test_audience_without_api_identifier_rejected(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey, aud: Any
) -> None:
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(sign(key_a, auth0_claims(aud=aud)))


async def test_id_token_sent_as_access_token_rejected(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    """An Auth0 ID token (``aud`` = client ID, has ``email``) is not an API access token."""
    id_token = sign(
        key_a,
        auth0_claims(aud=CLIENT_ID, email="jd@example.com", nonce="n", scope=None, azp=None),
    )
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(id_token)


async def test_opaque_access_token_rejected(make_verifier: Callable[..., TokenVerifier]) -> None:
    """No ``audience`` requested: Auth0 returns an opaque (JWE-shaped, 5-part) token."""
    opaque = "eyJhbGciOiJkaXIiLCJlbmMiOiJBMjU2R0NNIn0..aGVsbG8.d29ybGQ.dGFn"
    with pytest.raises(InvalidTokenError, match="malformed"):
        await make_verifier().verify(opaque)


# --------------------------------------------------------------------------- key rotation


async def test_kid_rotation_triggers_one_jwks_refetch(
    make_verifier: Callable[..., TokenVerifier],
    jwks_server: JwksServer,
    key_a: rsa.RSAPrivateKey,
    key_b: rsa.RSAPrivateKey,
) -> None:
    v = make_verifier(jwks_min_refresh_s=0)
    await v.verify(sign(key_a, auth0_claims()))
    assert len(jwks_server.requests) == 1

    # Auth0 rotates the signing key: the JWKS now publishes the next key too.
    jwks_server.keys = [auth0_jwk(key_a, KID_A), auth0_jwk(key_b, KID_B)]
    ident = await v.verify(sign(key_b, auth0_claims(), kid=KID_B))
    assert ident.subject == GOOGLE_SUB
    assert len(jwks_server.requests) == 2

    # Both keys are now cached: no more fetches for either kid.
    await v.verify(sign(key_a, auth0_claims()))
    await v.verify(sign(key_b, auth0_claims(), kid=KID_B))
    assert len(jwks_server.requests) == 2

    # After the old key is revoked, its tokens fail once the cache refreshes.
    jwks_server.keys = [auth0_jwk(key_b, KID_B)]
    v2 = make_verifier()
    with pytest.raises(InvalidTokenError, match="unknown kid"):
        await v2.verify(sign(key_a, auth0_claims()))


async def test_rotation_refetch_is_rate_limited_by_default(
    make_verifier: Callable[..., TokenVerifier],
    jwks_server: JwksServer,
    key_a: rsa.RSAPrivateKey,
    key_b: rsa.RSAPrivateKey,
) -> None:
    v = make_verifier()  # default jwks_min_refresh_s=30
    await v.verify(sign(key_a, auth0_claims()))
    jwks_server.keys = [auth0_jwk(key_a, KID_A), auth0_jwk(key_b, KID_B)]
    for _ in range(3):
        with pytest.raises(InvalidTokenError):
            await v.verify(sign(key_b, auth0_claims(), kid="attacker-random-kid"))
    assert len(jwks_server.requests) == 1


# --------------------------------------------------------------------------- hostile tokens


async def test_hs256_tokens_rejected(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    v = make_verifier()
    pem = key_a.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    for secret in (pem, CLIENT_ID.encode(), b"x" * 64):
        with pytest.raises(InvalidTokenError, match="not allowed"):
            await v.verify(
                forge({"alg": "HS256", "typ": "JWT", "kid": KID_A}, auth0_claims(), secret)
            )


@pytest.mark.parametrize("alg", ["none", "None", "NONE"])
async def test_alg_none_rejected(make_verifier: Callable[..., TokenVerifier], alg: str) -> None:
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(forge({"alg": alg, "kid": KID_A}, auth0_claims(), None))


@pytest.mark.parametrize(
    "iss",
    [
        "https://evil.us.auth0.com/",
        f"https://{TENANT}.evil.test/",
        f"http://{TENANT}/",
        f"https://{TENANT.upper()}/",
    ],
)
async def test_wrong_issuer_rejected_even_with_valid_signature(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey, iss: str
) -> None:
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(sign(key_a, auth0_claims(iss=iss)))


async def test_other_tenant_key_rejected(
    make_verifier: Callable[..., TokenVerifier], key_b: rsa.RSAPrivateKey
) -> None:
    """Right iss and kid, but signed by a key our tenant's JWKS does not publish."""
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(sign(key_b, auth0_claims()))


async def test_expired_auth0_token_rejected(
    make_verifier: Callable[..., TokenVerifier], key_a: rsa.RSAPrivateKey
) -> None:
    past = datetime.now(UTC) - timedelta(hours=2)
    with pytest.raises(InvalidTokenError):
        await make_verifier().verify(
            sign(key_a, auth0_claims(iat=past - timedelta(hours=24), exp=past))
        )


def test_prod_requires_https_jwks(clean_auth_env: pytest.MonkeyPatch) -> None:
    from app.security.dev_stub import AuthConfigError

    with pytest.raises(AuthConfigError, match="https"):
        build_verifier(auth0_settings(jwks_url=f"http://{TENANT}/.well-known/jwks.json"), "prod")


def test_settings_from_env_match_configure_script_output(
    clean_auth_env: pytest.MonkeyPatch,
) -> None:
    """The exact env lines scripts/configure-auth0.sh writes parse into a valid config."""
    clean_auth_env.setenv("WINGMAN_AUTH_ISSUER", ISSUER)
    clean_auth_env.setenv("WINGMAN_AUTH_AUDIENCE", API_ID)
    clean_auth_env.setenv("WINGMAN_AUTH_JWKS_URL", JWKS_URL)
    clean_auth_env.setenv("WINGMAN_AUTH_ALGORITHMS", "RS256")
    s = AuthSettings()
    assert s.oidc_configured
    assert (s.issuer, s.audience, s.jwks_url, s.algorithm_list()) == (
        ISSUER,
        API_ID,
        JWKS_URL,
        ["RS256"],
    )


# --------------------------------------------------------------------------- user resolution


async def _user_count(session: AsyncSession) -> int:
    return int(await session.scalar(select(func.count()).select_from(User)) or 0)


@pytest.fixture
async def auth0_client(
    make_verifier: Callable[..., TokenVerifier],
    session: AsyncSession,
) -> AsyncIterator[httpx.AsyncClient]:
    app = build_app(make_verifier(), session)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://test"
    ) as c:
        yield c


async def test_google_sub_creates_then_reuses_user(
    auth0_client: httpx.AsyncClient, session: AsyncSession, key_a: rsa.RSAPrivateKey
) -> None:
    sub = f"google-oauth2|{uuid.uuid4().int % 10**21:021d}"
    before = await _user_count(session)

    first = await auth0_client.get("/v1/me", headers=bearer(sign(key_a, auth0_claims(sub=sub))))
    assert first.status_code == 200, first.text
    assert first.json()["email"] is None  # no email in the access token: still fine
    assert await _user_count(session) == before + 1

    second = await auth0_client.get("/v1/me", headers=bearer(sign(key_a, auth0_claims(sub=sub))))
    assert second.status_code == 200, second.text
    assert second.json()["id"] == first.json()["id"]
    assert await _user_count(session) == before + 1

    # Keyed on (iss with trailing slash, full "google-oauth2|..." sub).
    user = await UserRepository(session).get_by_auth(ISSUER, sub)
    assert user is not None and str(user.id) == first.json()["id"]
    assert await UserRepository(session).get_by_auth(ISSUER.rstrip("/"), sub) is None
    assert await UserRepository(session).get_by_auth(ISSUER, sub.split("|", 1)[1]) is None


async def test_get_or_create_by_auth_is_idempotent_for_auth0_identity(
    session: AsyncSession,
) -> None:
    sub = f"google-oauth2|{uuid.uuid4().int % 10**21:021d}"
    repo = UserRepository(session)
    u1 = await repo.get_or_create_by_auth(ISSUER, sub)
    u2 = await repo.get_or_create_by_auth(ISSUER, sub, email="later@example.com")
    assert u1.id == u2.id
    assert u2.email is None  # profile hints are recorded on create only


async def test_rejected_auth0_tokens_create_no_user(
    auth0_client: httpx.AsyncClient, session: AsyncSession, key_a: rsa.RSAPrivateKey
) -> None:
    before = await _user_count(session)
    bad = [
        sign(key_a, auth0_claims(iss=f"https://{TENANT}")),
        sign(key_a, auth0_claims(aud=[USERINFO])),
        forge({"alg": "none", "kid": KID_A}, auth0_claims(), None),
    ]
    for token in bad:
        resp = await auth0_client.get("/v1/me", headers=bearer(token))
        assert resp.status_code == 401
    assert await _user_count(session) == before
