"""Resolves a verified token identity into a Wingman user (WING-004).

No passwords are stored: identity lives at the OIDC provider, and Wingman keeps only
``(auth_issuer, auth_subject)`` plus optional profile hints from the token.
"""

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import User
from app.db.repositories import UserRepository
from app.security.tokens import VerifiedIdentity


class AccountDeletedError(Exception):
    """The account behind a valid token was deleted. Treated as unauthenticated."""


async def resolve_user(session: AsyncSession, identity: VerifiedIdentity) -> User:
    """Return the user for ``identity``, creating it on first login.

    Reads first so a returning user's request does not write. ``email`` and
    ``display_name`` are recorded only when the account is created.
    """
    repo = UserRepository(session)
    user = await repo.get_by_auth(identity.issuer, identity.subject)
    if user is None:
        user = await repo.get_or_create_by_auth(
            identity.issuer,
            identity.subject,
            email=identity.email,
            display_name=identity.display_name,
        )
    if user.deleted_at is not None:
        raise AccountDeletedError(str(user.id))
    return user
