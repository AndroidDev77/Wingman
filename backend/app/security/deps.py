"""Auth dependencies for protected routers.

``get_current_user_scope`` is the stable interface (D-007): keep its import path and
signature. Protected handlers use it like this::

    from app.security.deps import get_current_user_scope

    @router.get("/v1/things/{id}")
    async def get_thing(
        id: UUID,
        scope: UserScope = Depends(get_current_user_scope),
        session: AsyncSession = Depends(get_session),
    ) -> Thing:
        return await ThingRepository(session, scope).require(id)  # 404 if not owned

Missing, malformed, invalid or expired bearer tokens, and deleted accounts, all get
the same generic ``401`` with ``WWW-Authenticate: Bearer``; the specific reason is
logged server-side only. Tests either override ``get_current_user_scope`` directly
or override :func:`app.security.factory.get_token_verifier` and ``get_session``.
"""

import logging
from typing import Annotated

from fastapi import Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import User
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.factory import get_token_verifier
from app.security.tokens import (
    InvalidTokenError,
    KeySourceUnavailableError,
    TokenVerifier,
)
from app.services.auth import AccountDeletedError, resolve_user

logger = logging.getLogger(__name__)

UNAUTHENTICATED_DETAIL = "Missing, invalid or expired bearer token"


def _unauthenticated() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=UNAUTHENTICATED_DETAIL,
        headers={"WWW-Authenticate": "Bearer"},
    )


def bearer_token(request: Request) -> str:
    """The bearer token from ``Authorization``, or a 401."""
    raw = request.headers.get("authorization")
    if raw is None:
        logger.info("auth rejected: no Authorization header")
        raise _unauthenticated()
    scheme, _, token = raw.strip().partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token or " " in token:
        logger.info("auth rejected: malformed Authorization header")
        raise _unauthenticated()
    return token


async def get_current_user(
    token: Annotated[str, Depends(bearer_token)],
    verifier: Annotated[TokenVerifier, Depends(get_token_verifier)],
    session: Annotated[AsyncSession, Depends(get_session)],
) -> User:
    """The authenticated, non-deleted user (created on first login)."""
    try:
        identity = await verifier.verify(token)
    except InvalidTokenError as exc:
        logger.info("auth rejected: %s", exc)
        raise _unauthenticated() from None
    except KeySourceUnavailableError as exc:
        logger.warning("auth unavailable: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Identity provider keys are temporarily unavailable",
            headers={"Retry-After": "5"},
        ) from None
    try:
        return await resolve_user(session, identity)
    except AccountDeletedError:
        logger.info("auth rejected: account deleted")
        raise _unauthenticated() from None


async def get_current_user_scope(
    user: Annotated[User, Depends(get_current_user)],
) -> UserScope:
    return UserScope(user.id)


CurrentUser = Annotated[User, Depends(get_current_user)]
CurrentScope = Annotated[UserScope, Depends(get_current_user_scope)]
