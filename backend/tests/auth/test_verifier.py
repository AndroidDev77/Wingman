"""JwtVerifier: every rejection path, without HTTP or a database."""

from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec, rsa

from app.security.tokens import (
    InvalidTokenError,
    IssuerRoutingVerifier,
    JwksKeySource,
    JwtVerifier,
    KeySourceUnavailableError,
    RejectAllVerifier,
    StaticKeySource,
)
from tests.auth.conftest import AUDIENCE, EC_KID, ISSUER, KID, claims, forge, rsa_jwk, sign


async def test_valid_rs256_token(verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey) -> None:
    payload = claims(sub="alice", email="alice@example.com", name="Alice")
    ident = await verifier.verify(sign(rsa_key, payload))
    assert (ident.issuer, ident.subject) == (ISSUER, "alice")
    assert ident.email == "alice@example.com"
    assert ident.display_name == "Alice"


async def test_valid_es256_token(verifier: JwtVerifier, ec_key: ec.EllipticCurvePrivateKey) -> None:
    ident = await verifier.verify(sign(ec_key, claims(sub="bob"), alg="ES256", kid=EC_KID))
    assert ident.subject == "bob"


async def test_audience_list_containing_ours_is_accepted(
    verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey
) -> None:
    token = sign(rsa_key, claims(aud=["other-api", AUDIENCE]))
    assert (await verifier.verify(token)).issuer == ISSUER


async def test_invalid_email_claim_is_dropped(
    verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey
) -> None:
    ident = await verifier.verify(sign(rsa_key, claims(email="not an email")))
    assert ident.email is None


@pytest.mark.parametrize(
    "overrides",
    [
        pytest.param({"iss": "https://evil.test/"}, id="wrong-issuer"),
        pytest.param({"aud": "someone-else"}, id="wrong-audience"),
        pytest.param({"aud": None}, id="missing-audience"),
        pytest.param({"iss": None}, id="missing-issuer"),
        pytest.param({"sub": None}, id="missing-sub"),
        pytest.param({"sub": ""}, id="empty-sub"),
        pytest.param({"sub": "x" * 513}, id="oversized-sub"),
        pytest.param({"exp": None}, id="missing-exp"),
        pytest.param(
            {"exp": datetime.now(UTC) - timedelta(minutes=5)},
            id="expired",
        ),
        pytest.param(
            {"nbf": datetime.now(UTC) + timedelta(minutes=5)},
            id="not-yet-valid",
        ),
        pytest.param(
            {"iat": datetime.now(UTC) + timedelta(minutes=5)},
            id="issued-in-future",
        ),
    ],
)
async def test_bad_claims_rejected(
    verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey, overrides: dict[str, Any]
) -> None:
    with pytest.raises(InvalidTokenError):
        await verifier.verify(sign(rsa_key, claims(**overrides)))


async def test_leeway_tolerates_small_clock_skew(
    verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey
) -> None:
    now = datetime.now(UTC)
    token = sign(rsa_key, claims(exp=now - timedelta(seconds=10), nbf=now + timedelta(seconds=10)))
    assert (await verifier.verify(token)).issuer == ISSUER  # leeway is 30s


async def test_invalid_signature_rejected(
    verifier: JwtVerifier, other_rsa_key: rsa.RSAPrivateKey
) -> None:
    # Signed by a different key but claiming our kid.
    with pytest.raises(InvalidTokenError):
        await verifier.verify(sign(other_rsa_key, claims()))


async def test_tampered_payload_rejected(verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey) -> None:
    token = sign(rsa_key, claims(sub="alice"))
    h, _, s = token.split(".")
    evil = jwt.utils.base64url_encode(
        jwt.api_jws.json.dumps(claims(sub="mallory"), default=str).encode()
    ).decode()
    with pytest.raises(InvalidTokenError):
        await verifier.verify(f"{h}.{evil}.{s}")


async def test_unknown_kid_rejected(verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey) -> None:
    with pytest.raises(InvalidTokenError):
        await verifier.verify(sign(rsa_key, claims(), kid="nope"))


async def test_alg_none_rejected(verifier: JwtVerifier) -> None:
    token = forge({"alg": "none", "typ": "JWT", "kid": KID}, claims(), None)
    with pytest.raises(InvalidTokenError):
        await verifier.verify(token)


@pytest.mark.parametrize("alg", ["None", "NONE", "nOnE", ""])
async def test_alg_none_variants_rejected(verifier: JwtVerifier, alg: str) -> None:
    token = forge({"alg": alg, "kid": KID}, claims(), None)
    with pytest.raises(InvalidTokenError):
        await verifier.verify(token)


async def test_hs256_with_rsa_public_key_as_secret_rejected(
    verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey
) -> None:
    """Classic alg confusion: HMAC-sign with the server's public key bytes."""
    pem = rsa_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    token = forge({"alg": "HS256", "typ": "JWT", "kid": KID}, claims(), pem)
    with pytest.raises(InvalidTokenError, match="not allowed"):
        await verifier.verify(token)


async def test_hs256_rejected_even_if_allowlisted_against_rsa_key(
    jwks: dict[str, Any], rsa_key: rsa.RSAPrivateKey
) -> None:
    """Defense in depth: a misconfigured allowlist still cannot use an RSA key for HMAC."""
    lax = JwtVerifier(
        issuer=ISSUER,
        audience=AUDIENCE,
        algorithms=["RS256", "HS256"],
        key_source=StaticKeySource({KID: rsa_jwk(rsa_key, alg=None)}),
    )
    pem = rsa_key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    token = forge({"alg": "HS256", "kid": KID}, claims(), pem)
    with pytest.raises(InvalidTokenError):
        await lax.verify(token)


async def test_es256_header_against_rsa_key_rejected(
    verifier: JwtVerifier, ec_key: ec.EllipticCurvePrivateKey
) -> None:
    token = sign(ec_key, claims(), alg="ES256", kid=KID)  # RSA kid, EC alg
    with pytest.raises(InvalidTokenError):
        await verifier.verify(token)


async def test_alg_outside_allowlist_rejected(
    jwks: dict[str, Any], rsa_key: rsa.RSAPrivateKey
) -> None:
    strict = JwtVerifier(
        issuer=ISSUER,
        audience=AUDIENCE,
        algorithms=["ES256"],
        key_source=StaticKeySource({k["kid"]: k for k in jwks["keys"]}),
    )
    with pytest.raises(InvalidTokenError, match="not allowed"):
        await strict.verify(sign(rsa_key, claims()))


@pytest.mark.parametrize(
    "token",
    ["", "abc", "a.b", "a.b.c", "x" * 9000, "eyJhbGciOiJSUzI1NiJ9.!!!.sig"],
)
async def test_malformed_tokens_rejected(verifier: JwtVerifier, token: str) -> None:
    with pytest.raises(InvalidTokenError):
        await verifier.verify(token)


def test_allowlist_rejects_none_and_unknown() -> None:
    src = StaticKeySource({"k": {"kty": "oct", "k": "AAAA"}})
    for algs in (["none"], ["RS256", "none"], ["FOO"], []):
        with pytest.raises(ValueError):
            JwtVerifier(issuer=ISSUER, audience=AUDIENCE, algorithms=algs, key_source=src)


async def test_reject_all_verifier(rsa_key: rsa.RSAPrivateKey) -> None:
    with pytest.raises(InvalidTokenError):
        await RejectAllVerifier().verify(sign(rsa_key, claims()))


async def test_issuer_routing(verifier: JwtVerifier, rsa_key: rsa.RSAPrivateKey) -> None:
    routing = IssuerRoutingVerifier({ISSUER: verifier})
    assert (await routing.verify(sign(rsa_key, claims(sub="r")))).subject == "r"
    with pytest.raises(InvalidTokenError, match="unknown issuer"):
        await routing.verify(sign(rsa_key, claims(iss="https://other.test/")))


# --------------------------------------------------------------------------- JWKS


class _Clock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


def _jwks_source(
    docs: list[Any], clock: _Clock, *, status: int = 200
) -> tuple[JwksKeySource, list[int]]:
    calls: list[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        doc = docs[min(len(calls), len(docs)) - 1]
        return httpx.Response(status, json=doc)

    src = JwksKeySource(
        "https://idp.test/jwks",
        cache_ttl_s=300,
        min_refresh_s=30,
        transport=httpx.MockTransport(handler),
        clock=clock,
    )
    return src, calls


def _verifier_with(src: JwksKeySource) -> JwtVerifier:
    return JwtVerifier(issuer=ISSUER, audience=AUDIENCE, algorithms=["RS256"], key_source=src)


async def test_jwks_is_cached(rsa_key: rsa.RSAPrivateKey) -> None:
    clock = _Clock()
    src, calls = _jwks_source([{"keys": [rsa_jwk(rsa_key)]}], clock)
    v = _verifier_with(src)
    for _ in range(3):
        await v.verify(sign(rsa_key, claims()))
    assert len(calls) == 1
    clock.t += 301  # TTL expired
    await v.verify(sign(rsa_key, claims()))
    assert len(calls) == 2


async def test_jwks_refetches_on_rotation_but_rate_limited(
    rsa_key: rsa.RSAPrivateKey, other_rsa_key: rsa.RSAPrivateKey
) -> None:
    clock = _Clock()
    rotated = {"keys": [rsa_jwk(rsa_key), rsa_jwk(other_rsa_key, kid="rotated")]}
    src, calls = _jwks_source([{"keys": [rsa_jwk(rsa_key)]}, rotated], clock)
    v = _verifier_with(src)
    await v.verify(sign(rsa_key, claims()))
    new_token = sign(other_rsa_key, claims(), kid="rotated")
    # Unknown kid within min_refresh_s: no refetch, rejected.
    with pytest.raises(InvalidTokenError):
        await v.verify(new_token)
    assert len(calls) == 1
    clock.t += 31
    assert (await v.verify(new_token)).issuer == ISSUER
    assert len(calls) == 2
    # Random kids do not cause more fetches right after.
    with pytest.raises(InvalidTokenError):
        await v.verify(sign(other_rsa_key, claims(), kid="random"))
    assert len(calls) == 2


@pytest.mark.parametrize("doc", [{"nope": []}, ["x"], "garbage"])
async def test_jwks_bad_document(rsa_key: rsa.RSAPrivateKey, doc: Any) -> None:
    src, _ = _jwks_source([doc], _Clock())
    with pytest.raises(KeySourceUnavailableError):
        await _verifier_with(src).verify(sign(rsa_key, claims()))


async def test_jwks_http_error(rsa_key: rsa.RSAPrivateKey) -> None:
    src, _ = _jwks_source([{}], _Clock(), status=500)
    with pytest.raises(KeySourceUnavailableError):
        await _verifier_with(src).verify(sign(rsa_key, claims()))


async def test_jwks_encryption_key_not_used_for_signatures(rsa_key: rsa.RSAPrivateKey) -> None:
    jwk = rsa_jwk(rsa_key) | {"use": "enc"}
    src, _ = _jwks_source([{"keys": [jwk]}], _Clock())
    with pytest.raises(InvalidTokenError):
        await _verifier_with(src).verify(sign(rsa_key, claims()))
