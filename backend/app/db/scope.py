"""Tenant scope passed to every repository.

WING-004 builds a :class:`UserScope` from the authenticated user and hands it to
repositories. Repositories bind the scope at construction and add
``WHERE user_id = :scope`` to every statement; there is no unscoped read path for
user-owned rows.
"""

import uuid
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class UserScope:
    user_id: uuid.UUID

    def __post_init__(self) -> None:
        if not isinstance(self.user_id, uuid.UUID):
            raise TypeError("UserScope.user_id must be a uuid.UUID")
