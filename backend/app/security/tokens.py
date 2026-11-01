"""OIDC-compatible JWT verification.

Interfaces:

* :class:`TokenVerifier`: ``await verify(token) -> VerifiedIdentity``; raises
  :class:`InvalidTokenError` for anything it will not accept and
  :class:`KeySourceUnavailableError` when keys cannot be fetched.
* :class:`KeySource`: resolves the verification key for a token header.
  :class:`JwksKeySource` fetches and caches a JWKS document (real IdPs);
  :class:`StaticKeySource` holds fixed keys (tests, pinned keys).

:class:`JwtVerifier` enforces, for every token:

* the header ``alg`` is in an explicit allowlist (``none`` is never accepted, and the
  key must be of the matching type, so an HS256 token cannot be checked against an
  RSA public key: no "alg confusion");
* a valid signature;
* ``iss`` equals the configured issuer and ``aud`` contains the configured audience;
* ``exp`` is present and in the future, ``nbf``/``iat`` (if present) are not in the
  future, all with a small configurable leeway;
* ``sub`` is a non-empty string of at most 512 characters.

Reasons for rejection are kept on the exception for server-side logs; clients only
ever see a generic ``401``.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx
import jwt
from jwt.algorithms import get_default_algorithms

MAX_TOKEN_LENGTH = 8192
MAX_CLAIM_LENGTH = 512
MAX_DISPLAY_NAME = 120
MAX_EMAIL_LENGTH = 320

_KNOWN_ALGORITHMS = frozenset(get_default_algorithms()) - {"none"}


class InvalidTokenError(Exception):
    """The token is missing, malformed, unsigned, expired or otherwise unacceptable."""


class KeySourceUnavailableError(Exception):
    """The verification keys could not be fetched (IdP outage, bad JWKS document)."""


@dataclass(frozen=True, slots=True)
class VerifiedIdentity:
    """The parts of a verified token Wingman uses. ``(issuer, subject)`` is the key."""

    issuer: str
    subject: str
    email: str | None = None
    display_name: str | None = None
    claims: Mapping[str, Any] = field(default_factory=dict, repr=False, compare=False)


class TokenVerifier(Protocol):
    async def verify(self, token: str) -> VerifiedIdentity: ...


class KeySource(Protocol):
    async def get_key(self, header: Mapping[str, Any]) -> Any:
        """The key object PyJWT should verify a token with this header against."""
        ...


def unverified_header(token: str) -> dict[str, Any]:
    if not token or len(token) > MAX_TOKEN_LENGTH or token.count(".") != 2:
        raise InvalidTokenError("malformed token")
    try:
        header = jwt.get_unverified_header(token)
    except jwt.PyJWTError as exc:
        raise InvalidTokenError("malformed token header") from exc
    alg = header.get("alg")
    if not isinstance(alg, str) or alg not in _KNOWN_ALGORITHMS:
        raise InvalidTokenError(f"unsupported alg {alg!r}")
    return header


def unverified_issuer(token: str) -> str | None:
    """``iss`` without verifying the signature. Use ONLY to route to a verifier."""
    try:
        claims = jwt.decode(token, options={"verify_signature": False})
    except jwt.PyJWTError:
        return None
    iss = claims.get("iss")
    return iss if isinstance(iss, str) else None


def _jwk_to_key(jwk: Mapping[str, Any], alg: str) -> Any:
    """Build a verification key from a JWK, requiring it to fit ``alg``."""
    if jwk.get("use") not in (None, "sig"):
        raise InvalidTokenError("key is not a signing key")
    key_alg = jwk.get("alg")
    if key_alg is not None and key_alg != alg:
        raise InvalidTokenError("token alg does not match key alg")
    try:
        return jwt.PyJWK(dict(jwk), algorithm=alg).key
    except jwt.PyJWTError as exc:
        # e.g. an RSA JWK offered for an HS256 or ES256 token.
        raise InvalidTokenError("key type does not match token alg") from exc


class StaticKeySource:
    """Fixed keys: either JWKs (``{"keys": [...]}`` entries) or raw key objects.

    ``keys`` maps a ``kid`` to a JWK dict. A token without ``kid`` is accepted only
    if exactly one key is configured.
    """

    def __init__(self, keys: Mapping[str, Mapping[str, Any]]) -> None:
        if not keys:
            raise ValueError("StaticKeySource needs at least one key")
        self._keys = dict(keys)

    async def get_key(self, header: Mapping[str, Any]) -> Any:
        return _jwk_to_key(_select_jwk(self._keys, header), header["alg"])


def _select_jwk(keys: Mapping[str, Mapping[str, Any]], header: Mapping[str, Any]) -> Any:
    kid = header.get("kid")
    if kid is None:
        if len(keys) == 1:
            return next(iter(keys.values()))
        raise InvalidTokenError("token has no kid and several keys are configured")
    if not isinstance(kid, str) or kid not in keys:
        raise InvalidTokenError("unknown kid")
    return keys[kid]


def _parse_jwks(doc: Any) -> dict[str, dict[str, Any]]:
    if not isinstance(doc, dict) or not isinstance(doc.get("keys"), list):
        raise KeySourceUnavailableError("JWKS document has no 'keys' array")
    out: dict[str, dict[str, Any]] = {}
    anonymous: list[dict[str, Any]] = []
    for k in doc["keys"]:
        if not isinstance(k, dict) or not isinstance(k.get("kty"), str):
            continue
        if isinstance(k.get("kid"), str):
            out[k["kid"]] = k
        else:
            anonymous.append(k)
    if not out and len(anonymous) == 1:
        out[""] = anonymous[0]
    return out


class JwksKeySource:
    """Fetches ``jwks_url`` and caches keys for ``cache_ttl_s``.

    An unknown ``kid`` (key rotation) triggers a refetch, at most once every
    ``min_refresh_s`` so random kids cannot be used to hammer the IdP.
    """

    def __init__(
        self,
        jwks_url: str,
        *,
        cache_ttl_s: float = 300,
        min_refresh_s: float = 30,
        timeout_s: float = 5.0,
        transport: httpx.AsyncBaseTransport | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.jwks_url = jwks_url
        self.cache_ttl_s = cache_ttl_s
        self.min_refresh_s = min_refresh_s
        self.timeout_s = timeout_s
        self._transport = transport
        self._clock = clock
        self._keys: dict[str, dict[str, Any]] = {}
        self._fetched_at: float | None = None
        self._lock = asyncio.Lock()
        self.fetch_count = 0

    def _fresh(self) -> bool:
        return self._fetched_at is not None and (
            self._clock() - self._fetched_at < self.cache_ttl_s
        )

    def _may_refresh_early(self) -> bool:
        return self._fetched_at is None or (self._clock() - self._fetched_at >= self.min_refresh_s)

    async def _fetch(self) -> None:
        self.fetch_count += 1
        try:
            async with httpx.AsyncClient(
                timeout=self.timeout_s, transport=self._transport, follow_redirects=False
            ) as client:
                resp = await client.get(self.jwks_url, headers={"Accept": "application/json"})
                resp.raise_for_status()
                doc = resp.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise KeySourceUnavailableError(f"JWKS fetch failed: {type(exc).__name__}") from exc
        self._keys = _parse_jwks(doc)
        self._fetched_at = self._clock()

    async def get_key(self, header: Mapping[str, Any]) -> Any:
        kid = header.get("kid")
        async with self._lock:
            if not self._fresh():
                await self._fetch()
            elif isinstance(kid, str) and kid not in self._keys and self._may_refresh_early():
                await self._fetch()
            keys = dict(self._keys)
        return _jwk_to_key(_select_jwk(keys, header), header["alg"])


def _optional_str(claims: Mapping[str, Any], name: str, max_len: int) -> str | None:
    v = claims.get(name)
    if not isinstance(v, str):
        return None
    v = v.strip()
    if not v:
        return None
    return v[:max_len]


def _email(claims: Mapping[str, Any]) -> str | None:
    v = _optional_str(claims, "email", MAX_EMAIL_LENGTH + 1)
    if v is None or len(v) > MAX_EMAIL_LENGTH:
        return None
    local, sep, domain = v.rpartition("@")
    if not sep or not local or "." not in domain or any(c.isspace() for c in v):
        return None
    return v


def identity_from_claims(claims: Mapping[str, Any]) -> VerifiedIdentity:
    iss, sub = claims.get("iss"), claims.get("sub")
    if not isinstance(iss, str) or not 1 <= len(iss) <= MAX_CLAIM_LENGTH:
        raise InvalidTokenError("invalid iss")
    if not isinstance(sub, str) or not 1 <= len(sub) <= MAX_CLAIM_LENGTH or not sub.strip():
        raise InvalidTokenError("invalid sub")
    return VerifiedIdentity(
        issuer=iss,
        subject=sub,
        email=_email(claims),
        display_name=_optional_str(claims, "name", MAX_DISPLAY_NAME),
        claims=dict(claims),
    )


class JwtVerifier:
    """Verifies JWTs from one issuer against a :class:`KeySource`."""

    def __init__(
        self,
        *,
        issuer: str,
        audience: str,
        algorithms: Iterable[str],
        key_source: KeySource,
        leeway_s: int = 30,
    ) -> None:
        algs = frozenset(algorithms)
        if not algs:
            raise ValueError("algorithm allowlist must not be empty")
        unknown = algs - _KNOWN_ALGORITHMS
        if unknown:
            raise ValueError(f"unsupported algorithms in allowlist: {sorted(unknown)}")
        self.issuer = issuer
        self.audience = audience
        self.algorithms = algs
        self.key_source = key_source
        self.leeway_s = leeway_s

    async def verify(self, token: str) -> VerifiedIdentity:
        header = unverified_header(token)
        alg = header["alg"]
        if alg not in self.algorithms:
            raise InvalidTokenError(f"alg {alg!r} not allowed")
        key = await self.key_source.get_key(header)
        try:
            claims = jwt.decode(
                token,
                key=key,
                algorithms=[alg],  # exactly the one already checked against the allowlist
                audience=self.audience,
                issuer=self.issuer,
                leeway=self.leeway_s,
                options={
                    "require": ["exp", "iss", "aud", "sub"],
                    "verify_signature": True,
                    "verify_exp": True,
                    "verify_nbf": True,
                    "verify_iat": True,
                    "verify_aud": True,
                    "verify_iss": True,
                },
            )
        except jwt.PyJWTError as exc:
            raise InvalidTokenError(f"{type(exc).__name__}: {exc}") from exc
        return identity_from_claims(claims)


class IssuerRoutingVerifier:
    """Dispatches to the verifier registered for the token's (unverified) ``iss``.

    The routed verifier re-checks ``iss`` and the signature, so reading ``iss``
    unverified here only selects which keys and algorithms apply; it grants nothing.
    """

    def __init__(self, verifiers: Mapping[str, TokenVerifier]) -> None:
        self._verifiers = dict(verifiers)

    @property
    def issuers(self) -> frozenset[str]:
        return frozenset(self._verifiers)

    async def verify(self, token: str) -> VerifiedIdentity:
        unverified_header(token)
        iss = unverified_issuer(token)
        verifier = self._verifiers.get(iss) if iss is not None else None
        if verifier is None:
            raise InvalidTokenError("unknown issuer")
        return await verifier.verify(token)


class RejectAllVerifier:
    """Used when no identity provider is configured: fail closed."""

    async def verify(self, token: str) -> VerifiedIdentity:
        raise InvalidTokenError("authentication is not configured")
