"""Conversation memory (WING-008): attributable per-contact facts, rolling summaries and
bounded retrieval. A package rather than a single ``memory.py`` module so schemas,
retrieval and the service stay separately readable; import from ``app.ai.memory``.

Public API for WING-009/WING-010::

    service = MemoryService(gateway, retriever=LexicalRetriever())   # or VectorRetriever
    await service.extract_from_messages(session, scope, conversation_id, message_ids=[...])
    await service.update_summary(session, scope, conversation_id)
    ctx = await service.get_context(session, scope, conversation_id, query, ContextLimits())
    ctx.render(); ctx.memory_ids; ctx.source_message_ids   # -> Draft.grounding
    await service.correct_fact(session, scope, memory_id, content="...")
    await service.delete_fact(session, scope, memory_id)
    page = await service.list_page(session, scope, contact_id, cursor=None, limit=50)
"""

from app.ai.memory.retrieval import (
    EmbeddingError,
    EmbeddingProvider,
    HashingEmbedder,
    InMemoryVectorIndex,
    LexicalRetriever,
    Retriever,
    Scores,
    VectorIndex,
    VectorRetriever,
)
from app.ai.memory.schemas import (
    SENSITIVE_CATEGORIES,
    ContextFact,
    ContextLimits,
    ContextSummary,
    ExtractedFact,
    ExtractionOutput,
    ExtractionResult,
    FactBasis,
    FactCategory,
    FactOrigin,
    MemoryContext,
    MemoryFact,
    SkippedFact,
    SkipReason,
    SummaryOutput,
    is_sensitive,
)
from app.ai.memory.service import (
    EXTRACT_OPERATION,
    SUMMARY_OPERATION,
    ExtractionPolicy,
    MemoryService,
    MemoryStateError,
    RankingWeights,
)

__all__ = [
    "EXTRACT_OPERATION",
    "SENSITIVE_CATEGORIES",
    "SUMMARY_OPERATION",
    "ContextFact",
    "ContextLimits",
    "ContextSummary",
    "EmbeddingError",
    "EmbeddingProvider",
    "ExtractedFact",
    "ExtractionOutput",
    "ExtractionPolicy",
    "ExtractionResult",
    "FactBasis",
    "FactCategory",
    "FactOrigin",
    "HashingEmbedder",
    "InMemoryVectorIndex",
    "LexicalRetriever",
    "MemoryContext",
    "MemoryFact",
    "MemoryService",
    "MemoryStateError",
    "RankingWeights",
    "Retriever",
    "Scores",
    "SkipReason",
    "SkippedFact",
    "SummaryOutput",
    "VectorIndex",
    "VectorRetriever",
    "is_sensitive",
]
