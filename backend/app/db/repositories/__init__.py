"""User-scoped repositories. Construct each with ``(session, UserScope)``."""

from app.db.repositories.activity import (
    AgentActionRepository,
    AutomationRuleRepository,
    DateProposalRepository,
    MemoryRepository,
)
from app.db.repositories.automation import (
    ApprovalRepository,
    DraftRepository,
    EventIngestRepository,
    OutboxRepository,
)
from app.db.repositories.base import ScopedRepository
from app.db.repositories.billing import (
    SubscriptionRepository,
    UsageEventRepository,
    UsageRecordResult,
)
from app.db.repositories.connections import (
    ConnectionRepository,
    ContactRepository,
    capability_columns,
)
from app.db.repositories.conversations import (
    ConversationRepository,
    IngestResult,
    MessageRepository,
)
from app.db.repositories.idempotency import (
    Claim,
    ClaimOutcome,
    IdempotencyRepository,
    fingerprint,
)
from app.db.repositories.integrations import (
    AssistedDeliveryRepository,
    IdentityLinkRepository,
    InboundNoticeRepository,
    ProfileActionRepository,
    ProfileEvaluationRepository,
    ProfileRepository,
)
from app.db.repositories.preferences import PreferenceRepository, StyleProfileRepository
from app.db.repositories.users import UserRepository

__all__ = [
    "AgentActionRepository",
    "ApprovalRepository",
    "AssistedDeliveryRepository",
    "IdentityLinkRepository",
    "InboundNoticeRepository",
    "ProfileActionRepository",
    "ProfileEvaluationRepository",
    "ProfileRepository",
    "AutomationRuleRepository",
    "Claim",
    "ClaimOutcome",
    "ConnectionRepository",
    "ContactRepository",
    "ConversationRepository",
    "DateProposalRepository",
    "DraftRepository",
    "EventIngestRepository",
    "IdempotencyRepository",
    "IngestResult",
    "MemoryRepository",
    "MessageRepository",
    "OutboxRepository",
    "PreferenceRepository",
    "ScopedRepository",
    "StyleProfileRepository",
    "SubscriptionRepository",
    "UsageEventRepository",
    "UsageRecordResult",
    "UserRepository",
    "capability_columns",
    "fingerprint",
]
