"""Relevance scoring for memory retrieval: pluggable vector search plus a lexical fallback.

Eligibility is decided by the database, never by the index. ``MemoryService`` first loads
the candidate facts with a query scoped to ``(user_id, contact_id)`` that drops deleted,
superseded and expired rows. A ``Retriever`` only *scores* those candidates. Even a
shared or stale vector index therefore cannot surface another user's or contact's fact,
or a deleted one: the index is consulted with ``restrict_to`` = the candidate IDs and is
itself partitioned by ``(user_id, contact_id)``.

* ``LexicalRetriever``: deterministic token overlap. Needs no provider; always available.
* ``VectorRetriever``: cosine similarity from an ``EmbeddingProvider`` and a
  ``VectorIndex``. Any embedding failure falls back to lexical scoring for that call.
* ``InMemoryVectorIndex``: brute-force index for tests and single-process dev. pgvector
  is not available, so production vector storage is a later, pluggable choice.
* ``HashingEmbedder``: deterministic feature-hashing embedder for tests and dev. It is
  not semantic; it only exercises the vector path without a model.
"""

import hashlib
import logging
import math
import re
import uuid
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from typing import Literal, Protocol, runtime_checkable

from app.ai.memory.schemas import MemoryFact

logger = logging.getLogger(__name__)

Strategy = Literal["vector", "lexical"]

_TOKEN_RE = re.compile(r"[a-z0-9]+")
_STOPWORDS = frozenset(
    """a an and are as at be but by do does for from had has have he her his i if in is it
    its me my of on or our she so that the their them they this to was we were what when
    where which who will with you your about just like really very can could would should
    did not no yes ok okay""".split()
)


def tokenize(text: str) -> list[str]:
    """Lowercase word tokens without stopwords, with a naive plural strip. Deterministic."""
    out: list[str] = []
    for tok in _TOKEN_RE.findall(text.lower()):
        if len(tok) < 2 or tok in _STOPWORDS:
            continue
        if len(tok) > 3 and tok.endswith("s") and not tok.endswith("ss"):
            tok = tok[:-1]
        out.append(tok)
    return out


def fact_text(fact: MemoryFact) -> str:
    """The text a fact is matched on: its key (dots and underscores as words) and content."""
    key = (fact.key or "").replace(".", " ").replace("_", " ")
    return f"{key} {fact.category or ''} {fact.content}"


# --------------------------------------------------------------------------- protocols


class EmbeddingError(Exception):
    """The embedding provider failed. Retrieval falls back to lexical scoring."""


@runtime_checkable
class EmbeddingProvider(Protocol):
    @property
    def name(self) -> str: ...

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        """One vector per text, same order. Raise ``EmbeddingError`` on failure."""
        ...


@runtime_checkable
class VectorIndex(Protocol):
    """Stores vectors partitioned by ``(user_id, contact_id)``. Never a source of truth."""

    async def upsert(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID, vector: list[float]
    ) -> None: ...

    async def remove(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID
    ) -> None: ...

    async def missing(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_ids: Iterable[uuid.UUID]
    ) -> set[uuid.UUID]:
        """Which of ``memory_ids`` have no vector in this partition."""
        ...

    async def search(
        self,
        user_id: uuid.UUID,
        contact_id: uuid.UUID,
        query: list[float],
        *,
        restrict_to: set[uuid.UUID],
        k: int,
    ) -> list[tuple[uuid.UUID, float]]:
        """Top ``k`` cosine similarities among ``restrict_to`` in this partition only."""
        ...


@dataclass(frozen=True)
class Scores:
    strategy: Strategy
    # Relevance in [0, 1] per candidate ID. Missing IDs count as 0.
    relevance: dict[uuid.UUID, float]


@runtime_checkable
class Retriever(Protocol):
    async def score(
        self,
        user_id: uuid.UUID,
        contact_id: uuid.UUID,
        query: str,
        candidates: Sequence[MemoryFact],
    ) -> Scores: ...

    async def on_upsert(self, user_id: uuid.UUID, fact: MemoryFact) -> None:
        """Called after a fact is stored. Must not raise for provider failures."""
        ...

    async def on_remove(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID
    ) -> None:
        """Called after a fact is deleted or superseded."""
        ...


# --------------------------------------------------------------------------- lexical


class LexicalRetriever:
    """Relevance = share of distinct query tokens that appear in the fact's text."""

    async def score(
        self,
        user_id: uuid.UUID,
        contact_id: uuid.UUID,
        query: str,
        candidates: Sequence[MemoryFact],
    ) -> Scores:
        return Scores("lexical", lexical_scores(query, candidates))

    async def on_upsert(self, user_id: uuid.UUID, fact: MemoryFact) -> None:
        return None

    async def on_remove(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID
    ) -> None:
        return None


def lexical_scores(query: str, candidates: Sequence[MemoryFact]) -> dict[uuid.UUID, float]:
    q = set(tokenize(query))
    if not q:
        return {c.id: 0.0 for c in candidates}
    out: dict[uuid.UUID, float] = {}
    for c in candidates:
        f = set(tokenize(fact_text(c)))
        out[c.id] = len(q & f) / len(q)
    return out


# --------------------------------------------------------------------------- vector


def _normalize(v: Sequence[float]) -> list[float]:
    norm = math.sqrt(sum(x * x for x in v))
    return [x / norm for x in v] if norm > 0 else [0.0 for _ in v]


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    if len(a) != len(b):
        raise ValueError("vector dimensions differ")
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(x * x for x in b))
    if na == 0 or nb == 0:
        return 0.0
    return sum(x * y for x, y in zip(a, b, strict=True)) / (na * nb)


@dataclass
class InMemoryVectorIndex:
    """Brute-force cosine search, partitioned by ``(user_id, contact_id)``. Process-local."""

    _parts: dict[tuple[uuid.UUID, uuid.UUID], dict[uuid.UUID, list[float]]] = field(
        default_factory=dict
    )

    async def upsert(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID, vector: list[float]
    ) -> None:
        self._parts.setdefault((user_id, contact_id), {})[memory_id] = _normalize(vector)

    async def remove(self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID) -> None:
        self._parts.get((user_id, contact_id), {}).pop(memory_id, None)

    async def missing(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_ids: Iterable[uuid.UUID]
    ) -> set[uuid.UUID]:
        part = self._parts.get((user_id, contact_id), {})
        return {m for m in memory_ids if m not in part}

    async def search(
        self,
        user_id: uuid.UUID,
        contact_id: uuid.UUID,
        query: list[float],
        *,
        restrict_to: set[uuid.UUID],
        k: int,
    ) -> list[tuple[uuid.UUID, float]]:
        part = self._parts.get((user_id, contact_id), {})
        hits = [(mid, cosine(query, vec)) for mid, vec in part.items() if mid in restrict_to]
        hits.sort(key=lambda h: (-h[1], str(h[0])))
        return hits[:k]

    def size(self, user_id: uuid.UUID, contact_id: uuid.UUID) -> int:
        return len(self._parts.get((user_id, contact_id), {}))


class HashingEmbedder:
    """Deterministic bag-of-words feature hashing. For tests and dev only (not semantic)."""

    def __init__(self, dim: int = 256) -> None:
        if dim < 8:
            raise ValueError("dim must be >= 8")
        self.dim = dim
        self.calls = 0

    @property
    def name(self) -> str:
        return f"hashing-{self.dim}"

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        self.calls += 1
        out: list[list[float]] = []
        for text in texts:
            v = [0.0] * self.dim
            for tok in tokenize(text):
                h = int.from_bytes(hashlib.blake2b(tok.encode(), digest_size=8).digest(), "big")
                v[h % self.dim] += 1.0 if (h >> 32) & 1 else -1.0
            out.append(_normalize(v))
        return out


class VectorRetriever:
    """Cosine relevance via an embedder and index, falling back to lexical on failure."""

    def __init__(self, embedder: EmbeddingProvider, index: VectorIndex | None = None) -> None:
        self.embedder = embedder
        self.index: VectorIndex = index if index is not None else InMemoryVectorIndex()

    async def score(
        self,
        user_id: uuid.UUID,
        contact_id: uuid.UUID,
        query: str,
        candidates: Sequence[MemoryFact],
    ) -> Scores:
        if not candidates:
            return Scores("vector", {})
        if not tokenize(query):
            return Scores("lexical", lexical_scores(query, candidates))
        try:
            by_id = {c.id: c for c in candidates}
            missing = await self.index.missing(user_id, contact_id, by_id)
            if missing:
                ordered = sorted(missing, key=str)
                vectors = await self._embed([fact_text(by_id[m]) for m in ordered])
                for mid, vec in zip(ordered, vectors, strict=True):
                    await self.index.upsert(user_id, contact_id, mid, vec)
            (qvec,) = await self._embed([query])
            hits = await self.index.search(
                user_id, contact_id, qvec, restrict_to=set(by_id), k=len(by_id)
            )
        except EmbeddingError as exc:
            logger.warning(
                "memory embedding failed; lexical fallback provider=%s error=%s",
                self.embedder.name,
                type(exc).__name__,
            )
            return Scores("lexical", lexical_scores(query, candidates))
        relevance = {c.id: 0.0 for c in candidates}
        for mid, sim in hits:
            relevance[mid] = max(0.0, min(1.0, sim))
        return Scores("vector", relevance)

    async def on_upsert(self, user_id: uuid.UUID, fact: MemoryFact) -> None:
        if fact.kind != "fact":
            return
        try:
            (vec,) = await self._embed([fact_text(fact)])
        except EmbeddingError:
            # Embedded lazily on the next retrieval instead.
            return
        await self.index.upsert(user_id, fact.contact_id, fact.id, vec)

    async def on_remove(
        self, user_id: uuid.UUID, contact_id: uuid.UUID, memory_id: uuid.UUID
    ) -> None:
        await self.index.remove(user_id, contact_id, memory_id)

    async def _embed(self, texts: list[str]) -> list[list[float]]:
        try:
            vectors = await self.embedder.embed(texts)
        except EmbeddingError:
            raise
        except Exception as exc:  # noqa: BLE001 - any provider failure means "fall back"
            raise EmbeddingError(type(exc).__name__) from exc
        if len(vectors) != len(texts):
            raise EmbeddingError("embedder returned the wrong number of vectors")
        return vectors
