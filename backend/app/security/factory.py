"""Builds the process-wide :class:`TokenVerifier` from settings.

=====================  ===================  ===============================================
OIDC configured        dev stub requested   result
=====================  ===================  ===============================================
yes                    no                   OIDC only
yes                    yes (dev/test)       route by ``iss``: OIDC or dev stub
no                     yes (dev/test)       dev stub only
no                     no                   reject every token (fail closed)
any                    yes (prod)           :class:`AuthConfigError`: refuse to start
=====================  ===================  ===============================================
"""

import logging
from functools import lru_cache

import httpx

from app.config import get_settings
from app.security.dev_stub import AuthConfigError, build_dev_verifier, dev_stub_active
from app.security.settings import AuthSettings
from app.security.tokens import (
    IssuerRoutingVerifier,
    JwksKeySource,
    JwtVerifier,
    RejectAllVerifier,
    TokenVerifier,
)

logger = logging.getLogger(__name__)


def build_verifier(
    auth: AuthSettings,
    env: str,
    *,
    jwks_transport: httpx.AsyncBaseTransport | None = None,
) -> TokenVerifier:
    """Raises :class:`AuthConfigError` for unsafe or invalid configuration.

    ``jwks_transport`` is a test seam (e.g. ``httpx.MockTransport``) for the JWKS fetch;
    production leaves it ``None`` and uses the network.
    """
    stub = dev_stub_active(auth, env)  # raises in prod if requested
    try:
        algorithms = auth.algorithm_list()
    except ValueError as exc:
        raise AuthConfigError(str(exc)) from exc

    verifiers: dict[str, TokenVerifier] = {}
    if auth.oidc_configured:
        assert auth.issuer and auth.audience and auth.jwks_url  # noqa: S101 - narrowed above
        if env == "prod" and not auth.jwks_url.startswith("https://"):
            raise AuthConfigError("WINGMAN_AUTH_JWKS_URL must use https in prod")
        verifiers[auth.issuer] = JwtVerifier(
            issuer=auth.issuer,
            audience=auth.audience,
            algorithms=algorithms,
            key_source=JwksKeySource(
                auth.jwks_url,
                cache_ttl_s=auth.jwks_cache_ttl_s,
                min_refresh_s=auth.jwks_min_refresh_s,
                timeout_s=auth.jwks_timeout_s,
                transport=jwks_transport,
            ),
            leeway_s=auth.leeway_s,
        )
    elif any((auth.issuer, auth.audience, auth.jwks_url)):
        raise AuthConfigError(
            "partial OIDC config: set all of WINGMAN_AUTH_ISSUER, WINGMAN_AUTH_AUDIENCE "
            "and WINGMAN_AUTH_JWKS_URL, or none"
        )
    if stub:
        logger.warning(
            "DEV auth stub is ENABLED (env=%s). Never use this configuration in prod.", env
        )
        verifiers[auth.dev_issuer] = build_dev_verifier(auth, env)

    if not verifiers:
        logger.warning("No identity provider configured: every authenticated call returns 401.")
        return RejectAllVerifier()
    return IssuerRoutingVerifier(verifiers)


def check_auth_config() -> None:
    """Startup check for the integrator: raises :class:`AuthConfigError` on unsafe config.

    Call from ``create_app()`` so a prod process with the dev stub enabled never starts.
    """
    get_token_verifier()


@lru_cache
def get_token_verifier() -> TokenVerifier:
    """FastAPI dependency (and cache) for the configured verifier.

    Tests override it with ``app.dependency_overrides[get_token_verifier]``.
    """
    return build_verifier(AuthSettings(), get_settings().env)
