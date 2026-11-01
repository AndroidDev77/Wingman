"""Persistence layer (WING-003): async SQLAlchemy models, repositories and sessions.

* :mod:`app.db.session` - engine / session factory and the ``get_session`` dependency.
* :mod:`app.db.scope` - :class:`UserScope`, required by every repository.
* :mod:`app.db.repositories` - user-scoped repositories.
* :mod:`app.db.models` - ORM models; :mod:`app.db.enums` - contract enums.

Schema changes go through Alembic (``backend/alembic``), owned by WING-003; other
tickets request changes rather than editing migrations.
"""

from app.db.base import Base
from app.db.scope import UserScope

__all__ = ["Base", "UserScope"]
