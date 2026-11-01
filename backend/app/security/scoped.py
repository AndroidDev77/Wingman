"""User-scoped repository helpers for protected routers.

Builds WING-003 repositories (``ScopedRepository`` subclasses) bound to the
authenticated user's :class:`UserScope`, sharing the request's session. The
repository adds ``WHERE user_id = :scope`` to every statement, so another user's row
is indistinguishable from a missing one; ``require`` raises ``NotFoundError``, which
``app.api.errors`` turns into ``404 not_found`` (never 403).

Usage::

    from app.db.repositories import ConversationRepository
    from app.security.scoped import scoped_repository

    Conversations = Annotated[
        ConversationRepository, Depends(scoped_repository(ConversationRepository))
    ]

    @router.get("/v1/conversations/{conversation_id}")
    async def get_conversation(conversation_id: UUID, repo: Conversations) -> ...:
        return await repo.require(conversation_id)
"""

from collections.abc import Callable, Coroutine
from typing import Annotated, Any

from fastapi import Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.repositories.base import ScopedRepository
from app.db.scope import UserScope
from app.db.session import get_session
from app.security.deps import get_current_user_scope


def scoped_repository[RepoT: ScopedRepository[Any]](
    repo_cls: type[RepoT],
) -> Callable[..., Coroutine[Any, Any, RepoT]]:
    """FastAPI dependency factory: ``repo_cls(session, current_user_scope)``."""

    async def _dep(
        session: Annotated[AsyncSession, Depends(get_session)],
        scope: Annotated[UserScope, Depends(get_current_user_scope)],
    ) -> RepoT:
        return repo_cls(session, scope)

    _dep.__name__ = f"scoped_{repo_cls.__name__}"
    return _dep
