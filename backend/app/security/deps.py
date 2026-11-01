"""Stable auth dependency interface for protected routers.

Integrator-provided stub so feature tickets (WING-007, WING-008, ...) can depend on
``get_current_user_scope`` while WING-004 builds authentication in parallel.

WING-004 owns this file and replaces the body with real JWT verification. The
signature and import path are the contract: keep them.

The stub fails closed: every protected call is rejected with 401 until real auth
lands. Tests inject a scope with ``app.dependency_overrides[get_current_user_scope]``.
"""

from fastapi import HTTPException, status

from app.db.scope import UserScope


async def get_current_user_scope() -> UserScope:
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Authentication is not configured",
        headers={"WWW-Authenticate": "Bearer"},
    )
