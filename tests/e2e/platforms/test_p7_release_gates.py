"""P7: the release feature gates (``config/release/feature_gates.json``) against the backend's
actual capability enforcement.

For every gated feature the backend is driven with that platform's own device claim and the most
permissive user setting (autopilot), and what it really does is compared with the gate:

* a non-``autonomous`` gate must never see the backend send on its own (a dispatched autopilot
  reply would mean the release advertises less than, or differently from, what runs);
* an ``autonomous`` gate must see the backend dispatch without approval (otherwise the UI and
  release notes promise autonomy the backend refuses);
* the observed outcome must equal the gate's ``backend_check.expect`` (``approval``,
  ``assisted_delivery``, ``refused``), and ``no_route`` features must have no served route.
"""

from __future__ import annotations

import json
import time
import uuid
from typing import Any

from tests.e2e.harness import REPO, Api, now_z
from tests.e2e.platforms.platform_harness import (
    PlatformDevice,
    assisted,
    outbox,
    pair_hinge_experimental,
    pair_imessage,
    pair_notification,
    pair_sms,
    problem_code,
    record,
    write_artifact,
)

GATES = json.loads((REPO / "config" / "release" / "feature_gates.json").read_text())


def _pair(u: Api, platform: str, transport: str) -> PlatformDevice:
    if platform == "sms":
        return pair_sms(u, claim_verified=True)  # the most optimistic client claim
    if transport == "notification":
        return pair_notification(u, platform)
    if transport == "unofficial_api":
        return pair_hinge_experimental(u)
    if transport == "companion":
        return pair_imessage(u)
    raise AssertionError(f"no device for {platform}/{transport}")


def _observe_reply(u: Api, dev: PlatformDevice) -> str:
    thread = {
        "sms_provider": "+15550137001",
        "notification": f"shortcut:gate-{uuid.uuid4().hex[:6]}",
        "unofficial_api": f"hinge-match-gate-{uuid.uuid4().hex[:6]}",
        "companion": f"iMessage;-;+1555013{uuid.uuid4().int % 10000:04d}",
    }[dev.transport]
    dev.deliver("hey, how's your week going?", conversation=thread, label="Gate")
    time.sleep(2)
    convs = [c for c in u.conversations(limit=100) if c["connection_id"] == dev.id]
    assert len(convs) == 1
    conv = convs[0]
    out = u.outbound(conv["id"])
    if any(m["origin"] == "autopilot" for m in out) or outbox(u, dev.id):
        return "dispatch"
    if u.approvals(status="pending", conversation_id=conv["id"]):
        return "approval"
    if [a for a in assisted(u, status="pending") if a["conversation_id"] == conv["id"]]:
        return "assisted_delivery"
    return "nothing"


def _observe_swipe(u: Api, dev: PlatformDevice, *, auto: bool) -> str:
    if auto:
        r = u.request(
            "PUT", f"/v1/connections/{dev.id}/settings", json_body={"auto_swipe_enabled": True}, key=str(uuid.uuid4())
        )
        return (
            "allowed"
            if r.status_code == 200
            else ("refused" if problem_code(r) == "swipe_execution_disabled" else r.text)
        )
    profile = u.post(
        "/v1/profiles",
        {
            "provider": dev.platform,
            "external_profile_id": f"gate-{uuid.uuid4().hex[:8]}",
            "source": "manual",
            "display_name": "Gate",
            "source_observed_at": now_z(),
        },
        expect=(200, 201),
    ).json()
    action = u.post(
        f"/v1/profiles/{profile['id']}/decision", {"decision": "like", "connection_id": dev.id}, expect=(200, 201)
    ).json()
    r = u.request("POST", f"/v1/profile-actions/{action['id']}/execute", json_body={}, key=str(uuid.uuid4()))
    if r.status_code == 200 and r.json()["execution"] == "queued":
        return "allowed"
    return "refused" if problem_code(r) == "swipe_execution_disabled" else r.text


def test_p7_gate_config_matches_backend_enforcement(make_user) -> None:
    observed: dict[str, Any] = {}
    paths = None
    for i, f in enumerate(GATES["features"]):
        check = f["backend_check"]
        kind = check["kind"]
        if kind == "none":
            observed[f["id"]] = {"mode": f["mode"], "observed": "n/a", "why": check["why"]}
            continue
        u: Api = make_user(f"p7-gate-{i}")
        u.put_preferences(mode="autopilot")
        if kind == "no_route":
            paths = paths or list(u.request("GET", "/openapi.json").json()["paths"])
            hits = [p for p in paths if any(word in p for word in check["paths"])]
            got = "no_route" if not hits else f"routes: {hits}"
            assert got == "no_route", (f["id"], got)
            assert f["mode"] != "autonomous", f["id"]
            observed[f["id"]] = {"mode": f["mode"], "observed": got}
            continue
        platforms = [check.get("platform") or p for p in f["platform"].split(",")]
        if f["transport"] == "any":
            devs = [pair_notification(u, p) for p in platforms] + [pair_hinge_experimental(u)]
        else:
            devs = [_pair(u, p, f["transport"]) for p in dict.fromkeys(platforms)]
        for dev in devs:
            if kind == "reply":
                got = _observe_reply(u, dev)
            else:
                got = _observe_swipe(u, dev, auto=kind == "auto_swipe")
            if f["mode"] == "autonomous":
                assert got in ("dispatch", "allowed"), f"{f['id']}: gate says autonomous, backend: {got}"
            else:
                assert got not in ("dispatch", "allowed"), f"{f['id']}: gate says {f['mode']}, backend acted: {got}"
                assert got == check["expect"], (
                    f"{f['id']} on {dev.platform}/{dev.transport}: expected {check['expect']}, got {got}"
                )
            observed.setdefault(f["id"], {"mode": f["mode"], "observed": {}})["observed"][
                f"{dev.platform}/{dev.transport}"
            ] = got
    write_artifact("gate_crosscheck.json", observed)
    autonomous = [f["id"] for f in GATES["features"] if f["mode"] == "autonomous"]
    record(
        "P7 release gates: config matches backend enforcement for every gated feature",
        "all",
        features=len(GATES["features"]),
        autonomous=autonomous or "none",
    )
