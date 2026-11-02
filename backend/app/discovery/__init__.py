"""WING-031 profile discovery: the review workflow, decision and execution limits, the
audited execute gate and profile invalidation. No swipe executor exists (see
:mod:`app.discovery.service`)."""

from app.discovery.limits import DEFAULT_LIMITS, DecisionRateLimited, DiscoveryLimits
from app.discovery.service import DENIAL_REASON_CODE, DiscoveryService

__all__ = [
    "DEFAULT_LIMITS",
    "DENIAL_REASON_CODE",
    "DecisionRateLimited",
    "DiscoveryLimits",
    "DiscoveryService",
]
