"""Profile evaluation and review-queue ranking (WING-019), behind contract 1.3.0.

:class:`ProfileMatchingService` sits next to :class:`app.services.integrations.ProfileService`
(import, decision and the swipe gate, unchanged) and adds:

* :meth:`~ProfileMatchingService.ensure_current` evaluates a profile against the user's
  **current** user-wide preferences when its latest evaluation is missing or was made
  from other inputs (profile content, preferences version, rules version, model on/off;
  see :func:`app.ai.match_evaluator.input_fingerprint`). Evaluations are append-only
  rows written through ``ProfileEvaluationRepository.create``: a preference change adds
  a new row tagged with the new ``preferences_version`` and never rewrites earlier
  evaluations or any recorded decision. Evaluations by another evaluator (a
  ``evaluator_version`` without the ``wing019-`` prefix) are left as they are.
* :meth:`~ProfileMatchingService.review_queue` ranks undecided profiles (no decision
  other than ``skip``) lazily re-evaluating them first:

  1. fresh profiles, by score (high first; unscored last), then confidence, then
     newest import;
  2. stale profiles (``source_observed_at`` older than 14 days, or invalidated): shown
     with ``stale: true`` after every fresh one; they can be decided on but the v1.3
     gate never executes them;
  3. dealbreaker hits are **left out** unless ``include_excluded=True``, which lists
     them last with ``dealbreakers_hit`` and the dealbreaker reason. (Contract 1.3.0 has
     no query parameter for this yet; see the WING-019 handoff.)

  The page cursor is an opaque keyset on ``(rank, id)`` (ADR 0003), computed over at
  most :data:`MAX_RANKED` candidates per request.

Nothing here records a decision, queues or executes a swipe. Evaluations are advisory.
Audit and log lines carry IDs and counts only, never profile content.

Fixtures: :func:`synthetic_profile_feed` is a deterministic feed of clearly fictional
``mock`` profiles (``source: fixture``) for development and tests;
:meth:`ProfileMatchingService.import_fixture_feed` imports it through the normal
``importProfile`` service path. Real profiles come in through ``importProfile`` with
``source: manual`` (the user shares one) or, later, ``device`` (WING-031).
"""

import logging
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.ai.match_evaluator import (
    EVALUATOR_PREFIX,
    EvaluationResult,
    MatchEvaluator,
    PreferenceInput,
    input_fingerprint,
)
from app.db.base import utc_now
from app.db.enums import ProfileProvider, ProfileSource
from app.db.errors import InvalidCursorError
from app.db.models import Profile, ProfileEvaluation
from app.db.pagination import CursorPosition, Page, clamp_limit, decode_cursor, encode_cursor
from app.db.repositories import (
    ProfileActionRepository,
    ProfileEvaluationRepository,
    ProfileRepository,
)
from app.db.scope import UserScope
from app.services.integrations import ProfileService, ProfileView, is_stale
from app.services.preferences import Preferences, PreferencesService

__all__ = [
    "MAX_RANKED",
    "ProfileMatchingService",
    "RankedProfile",
    "rank_key",
    "synthetic_profile_feed",
]

logger = logging.getLogger(__name__)

# Upper bound on undecided profiles ranked (and lazily evaluated) per queue request.
MAX_RANKED = 500

_TIER_FRESH = 0
_TIER_STALE = 1
_TIER_EXCLUDED = 2


def rank_key(*, stale: bool, excluded: bool, score: float | None, confidence: float) -> int:
    """Ascending integer sort key: tier, then score high-first (unscored last), then
    confidence high-first. Ties are broken by profile id in the cursor."""
    tier = _TIER_EXCLUDED if excluded else _TIER_STALE if stale else _TIER_FRESH
    score_part = 1001 if score is None else 1000 - round(score * 10)
    conf_part = 1000 - round(confidence * 1000)
    return tier * 10**8 + score_part * 10**4 + conf_part


@dataclass(frozen=True)
class RankedProfile:
    view: ProfileView
    rank: int
    excluded: bool


def _is_current(
    evaluation: ProfileEvaluation | None, fingerprint: str, preferences_version: int
) -> bool:
    if evaluation is None:
        return False
    if not evaluation.evaluator_version.startswith(EVALUATOR_PREFIX):
        return True  # another evaluator's row: never superseded here
    return (
        evaluation.input_fingerprint == fingerprint
        and evaluation.preferences_version == preferences_version
    )


class ProfileMatchingService:
    def __init__(
        self,
        evaluator: MatchEvaluator | None = None,
        *,
        now: Callable[[], datetime] = utc_now,
    ) -> None:
        self.evaluator = evaluator or MatchEvaluator()
        self._now = now
        self.profiles = ProfileService(now=now)

    async def _preferences(self, session: AsyncSession, scope: UserScope) -> Preferences:
        return await PreferencesService(session, scope).get_user_preferences()

    async def _store(
        self,
        session: AsyncSession,
        scope: UserScope,
        profile: Profile,
        result: EvaluationResult,
        prefs: PreferenceInput,
        fingerprint: str,
    ) -> ProfileEvaluation:
        return await ProfileEvaluationRepository(session, scope).create(
            profile_id=profile.id,
            score=result.score,
            reasons=result.reasons,
            known_unknowns=result.known_unknowns,
            intent_mode=result.intent_mode,
            dealbreakers_hit=result.dealbreakers_hit,
            confidence=result.confidence,
            evaluator_version=result.evaluator_version,
            preferences_version=prefs.version,
            input_fingerprint=fingerprint,
        )

    async def ensure_current(
        self,
        session: AsyncSession,
        scope: UserScope,
        profile: Profile,
        *,
        preferences: Preferences | PreferenceInput | None = None,
        latest: ProfileEvaluation | None = None,
        known_latest: bool = False,
    ) -> ProfileEvaluation:
        """The profile's current evaluation, evaluating (and appending) if needed."""
        prefs = PreferenceInput.of(preferences or await self._preferences(session, scope))
        if not known_latest:
            latest = await ProfileEvaluationRepository(session, scope).latest(profile.id)
        fingerprint = input_fingerprint(profile, prefs, uses_model=self.evaluator.uses_model)
        if latest is not None and _is_current(latest, fingerprint, prefs.version):
            return latest
        result = await self.evaluator.evaluate(profile, prefs, user_id=str(scope.user_id))
        return await self._store(session, scope, profile, result, prefs, fingerprint)

    async def evaluate_profile(
        self, session: AsyncSession, scope: UserScope, profile_id: uuid.UUID
    ) -> ProfileEvaluation:
        """Evaluate one of the caller's profiles now (another user's id: NotFoundError)."""
        profile = await ProfileRepository(session, scope).require(profile_id)
        return await self.ensure_current(session, scope, profile)

    async def review_queue(
        self,
        session: AsyncSession,
        scope: UserScope,
        *,
        provider: ProfileProvider | None = None,
        cursor: str | None = None,
        limit: int | None = None,
        include_excluded: bool = False,
    ) -> Page[ProfileView]:
        size = clamp_limit(limit)
        listing = "profiles:ranked" + (f":{provider.value}" if provider else "")
        listing += ":all" if include_excluded else ""
        position = decode_cursor(listing, cursor) if cursor is not None else None

        candidates = await ProfileRepository(session, scope).undecided(
            provider=provider, limit=MAX_RANKED
        )
        prefs = PreferenceInput.of(await self._preferences(session, scope))
        latest = await ProfileEvaluationRepository(session, scope).latest_for_profiles(
            [p.id for p in candidates]
        )
        now = self._now()
        ranked: list[tuple[int, uuid.UUID, Profile, ProfileEvaluation, bool]] = []
        refreshed = 0
        for profile in candidates:
            before = latest.get(profile.id)
            evaluation = await self.ensure_current(
                session, scope, profile, preferences=prefs, latest=before, known_latest=True
            )
            refreshed += evaluation is not before
            excluded = bool(evaluation.dealbreakers_hit)
            if excluded and not include_excluded:
                continue
            stale = is_stale(profile, now)
            key = rank_key(
                stale=stale,
                excluded=excluded,
                score=evaluation.score,
                confidence=evaluation.confidence,
            )
            ranked.append((key, profile.id, profile, evaluation, stale))
        if refreshed:
            logger.info(
                "review queue evaluated profiles user=%s count=%d", scope.user_id, refreshed
            )
        # Fresh imports first among equals: newer UUIDv7 ids sort later, so negate by
        # sorting on the id descending inside the same rank.
        ranked.sort(key=lambda r: (r[0], _desc_id(r[1])))
        if position is not None:
            if not isinstance(position.sort_value, int):
                raise InvalidCursorError("cursor does not belong to this listing")
            bound = (position.sort_value, _desc_id(position.id))
            ranked = [r for r in ranked if (r[0], _desc_id(r[1])) > bound]
        page = ranked[: size + 1]
        items = page[:size]
        next_cursor = None
        if len(page) > size:
            last = items[-1]
            next_cursor = encode_cursor(listing, CursorPosition(last[0], last[1]))
        views = [
            ProfileView(
                profile=profile,
                stale=stale,
                latest_evaluation=evaluation,
                latest_action=await ProfileActionRepository(session, scope).latest(profile.id),
            )
            for _, _, profile, evaluation, stale in items
        ]
        return Page(items=views, next_cursor=next_cursor)

    async def import_fixture_feed(
        self,
        session: AsyncSession,
        scope: UserScope,
        *,
        observed_at: datetime | None = None,
        count: int | None = None,
    ) -> list[ProfileView]:
        """Import :func:`synthetic_profile_feed` through the normal import path."""
        views: list[ProfileView] = []
        for body in synthetic_profile_feed(observed_at or self._now(), count=count):
            view, _ = await self.profiles.import_profile(
                session,
                scope,
                provider=ProfileProvider.MOCK,
                external_profile_id=body["external_profile_id"],
                source=ProfileSource.FIXTURE,
                connection_id=None,
                display_name=body["display_name"],
                bio=body["bio"],
                prompts=body["prompts"],
                attributes=body["attributes"],
                source_observed_at=observed_at or self._now(),
            )
            views.append(view)
        return views


def _desc_id(id_: uuid.UUID) -> int:
    return -id_.int


# --------------------------------------------------------------------------- fixtures

# Clearly fictional people (no real names, no photos). Deterministic: the same call
# always returns the same feed. ``age`` and other attributes appear only where the
# fixture "source" states them; several entries deliberately omit them.
_FEED: tuple[dict[str, Any], ...] = (
    {
        "display_name": "Fixture Person A (fictional)",
        "bio": "Weekend hiker and amateur baker. Looking for something real.",
        "prompts": [{"prompt": "A perfect Sunday", "answer": "Trail at dawn, pastries after"}],
        "attributes": {
            "age": 32,
            "looking_for": "Long-term relationship",
            "smoking": "No",
            "interests": ["hiking", "baking"],
        },
    },
    {
        "display_name": "Fixture Person B (fictional)",
        "bio": "Night owl, synth music, terrible puns.",
        "prompts": [{"prompt": "I'm known for", "answer": "Making playlists for every mood"}],
        "attributes": {"looking_for": "Short-term, open to long", "smoking": "Socially"},
    },
    {
        "display_name": "Fixture Person C (fictional)",
        "bio": "Here for fun, nothing serious right now.",
        "prompts": [],
        "attributes": {"looking_for": "Something casual", "drinking": "Sometimes"},
    },
    {
        "display_name": "Fixture Person D (fictional)",
        "bio": "Board games, ramen, and long walks with my dog.",
        "prompts": [{"prompt": "Green flags", "answer": "Remembering the little things"}],
        "attributes": {"interests": ["board games", "ramen"]},
    },
    {
        "display_name": "Fixture Person E (fictional)",
        "bio": "Figuring things out. Coffee first.",
        "prompts": [{"prompt": "Dating me is like", "answer": "A slow-burn mystery novel"}],
        "attributes": {"looking_for": "Figuring out my dating goals", "smoking": "Never"},
    },
    {
        "display_name": "Fixture Person F (fictional)",
        "bio": None,
        "prompts": [],
        "attributes": {},
    },
)


def synthetic_profile_feed(
    observed_at: datetime, *, count: int | None = None
) -> list[dict[str, Any]]:
    """``ProfileImportRequest`` bodies (``provider: mock``, ``source: fixture``)."""
    stamp = observed_at.isoformat().replace("+00:00", "Z")
    out: list[dict[str, Any]] = []
    for i, entry in enumerate(_FEED[: count if count is not None else len(_FEED)]):
        out.append(
            {
                "provider": "mock",
                "external_profile_id": f"fixture-{i + 1:03d}",
                "source": "fixture",
                "display_name": entry["display_name"],
                "bio": entry["bio"],
                "prompts": [dict(p) for p in entry["prompts"]],
                "attributes": dict(entry["attributes"]),
                "source_observed_at": stamp,
            }
        )
    return out
