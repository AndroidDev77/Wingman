"""P5: profile discovery and swipe (WING-019/031). Review workflow only; no swipe exists.

Import -> evaluation -> ranked review queue -> recorded decision. Execution is refused
(``422 swipe_execution_disabled``) on every platform and path, and auto-swipe cannot be turned
on: no connection has a VERIFIED ``swipe`` and the experimental one is not operator-verified.
"""

from __future__ import annotations

import uuid

from tests.e2e.harness import Api, assert_contract, now_z
from tests.e2e.platforms.platform_harness import (
    DATING,
    pair_hinge_experimental,
    pair_notification,
    problem_code,
    record,
)


def _import(u: Api, provider: str, n: int) -> dict:
    body = {
        "provider": provider,
        "external_profile_id": f"{provider}-profile-{n}-{uuid.uuid4().hex[:6]}",
        "source": "manual",
        "display_name": f"Profile {n}",
        "bio": "Trail runner, terrible at karaoke.",
        "prompts": [{"prompt": "A shower thought I recently had", "answer": "Do fish get thirsty?"}],
        "attributes": {"age": 31, "location_label": "Chicago", "interests": ["running", "jazz"]},
        "source_observed_at": now_z(),
    }
    return assert_contract("Profile", u.post("/v1/profiles", body, expect=(200, 201)).json())


def test_p5_discovery_review_decision_and_execute_refused_everywhere(make_user) -> None:
    u: Api = make_user("p5-discovery")
    u.put_preferences(mode="autopilot")
    connections = {p: pair_notification(u, p) for p in DATING}
    experimental = pair_hinge_experimental(u)
    targets = [*connections.values(), experimental]
    actions = []
    for i, dev in enumerate(targets):
        profile = _import(u, dev.platform, i)
        queue = assert_contract("ProfilePage", u.get("/v1/profiles/review-queue", params={"provider": dev.platform}))
        listed = [p for p in queue["items"] if p["id"] == profile["id"]]
        assert listed, "imported profile is in the review queue"
        evaluation = listed[0]["latest_evaluation"]
        assert evaluation is not None and evaluation["reasons"], evaluation
        action = assert_contract(
            "ProfileAction",
            u.post(
                f"/v1/profiles/{profile['id']}/decision",
                {"decision": "like", "connection_id": dev.id},
                expect=(200, 201),
            ).json(),
        )
        assert action["execution"] == "not_executable" and action["executed_at"] is None
        r = u.request("POST", f"/v1/profile-actions/{action['id']}/execute", json_body={}, key=str(uuid.uuid4()))
        assert r.status_code == 422 and problem_code(r) == "swipe_execution_disabled", (dev.platform, r.text)
        s = u.request(
            "PUT", f"/v1/connections/{dev.id}/settings", json_body={"auto_swipe_enabled": True}, key=str(uuid.uuid4())
        )
        assert s.status_code == 422 and problem_code(s) == "swipe_execution_disabled", s.text
        actions.append(action["id"])

    blocked = [
        a for a in u.actions() if a["type"] == "policy_blocked" and a["reason_code"] == "swipe_execution_disabled"
    ]
    assert len(blocked) >= len(targets)
    assert "profile_action_queued" not in {a["type"] for a in u.actions()}
    for dev in targets:
        prof = assert_contract("ProfilePage", u.get("/v1/profiles/review-queue", params={"provider": dev.platform}))
        for p in prof["items"]:
            la = p.get("latest_action")
            assert la is None or la["execution"] == "not_executable", la
    record(
        "P5 Discovery: import -> evaluation -> decision; execute 422 on Tinder/Bumble/Hinge/Hinge-exp",
        "profile/swipe",
        executed=0,
    )


def test_p5_decision_rate_limit_is_429_and_replays_are_never_limited(make_user) -> None:
    u: Api = make_user("p5-ratelimit")
    dev = pair_notification(u, "hinge")
    profile = _import(u, "hinge", 99)
    statuses = []
    first_key = str(uuid.uuid4())
    for i in range(40):
        key = first_key if i == 0 else str(uuid.uuid4())
        r = u.request(
            "POST",
            f"/v1/profiles/{profile['id']}/decision",
            json_body={"decision": "pass" if i % 2 else "like", "connection_id": dev.id},
            key=key,
        )
        statuses.append(r.status_code)
        if r.status_code == 429:
            assert problem_code(r) == "rate_limited" and r.headers.get("Retry-After"), r.text
            break
    assert statuses[-1] == 429 and len(statuses) == 31, statuses  # 30 per rolling minute
    replay = u.request(
        "POST",
        f"/v1/profiles/{profile['id']}/decision",
        json_body={"decision": "like", "connection_id": dev.id},
        key=first_key,
    )
    assert replay.status_code in (200, 201) and replay.headers.get("Idempotent-Replayed") == "true", replay.text
    record("P5 Discovery: decision rate limit 30/min -> 429 with Retry-After; replays never limited", "profile/swipe")
