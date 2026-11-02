#!/usr/bin/env python3
"""WING-032 manual runs: the backend half of a device run's evidence, redacted by construction.

    WINGMAN_TOKEN=... python3 scripts/e2e/platforms/manual/collect_backend_evidence.py \\
        --api https://wingman-beta.example.net --connection-id <uuid> --since 2026-11-03T10:00:00Z \\
        --run-id R-SMS-2026-11-03 > evidence-backend.json

Reads, with the tester's own token, only through the public API (``getConnection``,
``listOutboxInstructions``, ``listConversations``/``listMessages``, ``listInboundNotices``,
``listAssistedDeliveries``, ``listAgentActions``) and writes ONE JSON object holding **IDs,
states, counts, reason codes and timestamps only**: never message text, names, phone numbers,
thread IDs or tokens. Attach it to the evidence log entry for the run, after
``redaction_check.py`` passes on it.

Stdlib only. ``--self-test`` checks the redaction rules on canned API bodies (no network).
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from collections import Counter
from typing import Any

SAFE_ACTION_DETAIL_KEYS = {"attempt", "retried", "outcome", "kind", "identity", "truncated", "content_hidden", "edited"}


def get(api: str, token: str, path: str, **params: Any) -> Any:
    query = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
    url = f"{api.rstrip('/')}{path}" + (f"?{query}" if query else "")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})  # noqa: S310 - tester URL
    with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310
        return json.loads(resp.read())


def pages(api: str, token: str, path: str, **params: Any) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    cursor = None
    while True:
        body = get(api, token, path, limit=100, cursor=cursor, **params)
        out += body["items"]
        cursor = body.get("next_cursor")
        if not cursor or len(out) > 2000:
            return out


def summarize(
    connection: dict[str, Any],
    outbox: list[dict[str, Any]],
    messages: list[dict[str, Any]],
    notices: list[dict[str, Any]],
    assisted: list[dict[str, Any]],
    actions: list[dict[str, Any]],
    since: str,
) -> dict[str, Any]:
    caps = connection.get("capabilities", {})
    return {
        "connection": {
            "id": connection["id"],
            "platform": connection["platform"],
            "transport": connection.get("transport"),
            "status": connection.get("status"),
            "experimental": connection.get("experimental"),
            "experimental_verified_at": connection.get("experimental_verified_at"),
            "connector_version": caps.get("connector_version"),
            "capabilities": {k: v.get("status") for k, v in caps.get("capabilities", {}).items()},
        },
        "outbox": {
            "by_state": dict(Counter(i["state"] for i in outbox)),
            "max_attempt": max((i["attempt"] for i in outbox), default=0),
        },
        "messages": dict(
            Counter(
                f"{m['direction']}/{m['origin']}/{m['delivery_state']}" for m in messages if m["created_at"] >= since
            )
        ),
        "messages_sent_twice": [
            k
            for k, n in Counter(m.get("external_message_id") for m in messages if m["direction"] == "outbound").items()
            if k and n > 1
        ],
        "notices": dict(Counter(f"{n['kind']}/{n['identity']}" for n in notices if n["created_at"] >= since)),
        "assisted_deliveries": dict(Counter(a["status"] for a in assisted if a["created_at"] >= since)),
        "audit": [
            {
                "type": a["type"],
                "actor": a["actor"],
                "reason_code": a.get("reason_code"),
                "message_id": a.get("message_id"),
                "occurred_at": a["occurred_at"],
                "details": {k: v for k, v in (a.get("details") or {}).items() if k in SAFE_ACTION_DETAIL_KEYS},
            }
            for a in actions
            if a["occurred_at"] >= since
        ],
    }


def self_test() -> int:
    conn = {
        "id": "c1",
        "platform": "sms",
        "transport": "sms_provider",
        "status": "connected",
        "display_name": "Alice's phone +15550100001",
        "capabilities": {
            "connector_version": "0.1.0",
            "capabilities": {"send": {"status": "UNVERIFIED", "notes": "x"}},
        },
    }
    msgs = [
        {
            "direction": "outbound",
            "origin": "approved_draft",
            "delivery_state": "sent",
            "created_at": "2026-11-03T10:01:00Z",
            "text": "PRIVATE TEXT",
            "external_message_id": "sms:out:1",
        }
    ]
    acts = [
        {
            "type": "send_acknowledged",
            "actor": "connector",
            "message_id": "m1",
            "occurred_at": "2026-11-03T10:01:01Z",
            "details": {"attempt": 1, "thread": "sms:+15550100001"},
        }
    ]
    out = json.dumps(summarize(conn, [], msgs, [], [], acts, "2026-11-03T10:00:00Z"))
    for private in ("PRIVATE TEXT", "+15550100001", "Alice"):
        if private in out:
            print(f"self-test FAILED: {private!r} leaked", file=sys.stderr)
            return 1
    print("collect_backend_evidence self-test OK")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--api")
    parser.add_argument("--connection-id")
    parser.add_argument("--since", help="ISO timestamp (UTC, Z) of the run start")
    parser.add_argument("--run-id", help="the evidence log entry id, e.g. R-SMS-2026-11-03")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    token = os.environ.get("WINGMAN_TOKEN")
    if not (args.api and args.connection_id and args.since and args.run_id and token):
        print("need --api, --connection-id, --since, --run-id and WINGMAN_TOKEN", file=sys.stderr)
        return 2
    conn = get(args.api, token, f"/v1/connections/{args.connection_id}")
    outbox = pages(args.api, token, f"/v1/connections/{args.connection_id}/outbox")
    convs = [c for c in pages(args.api, token, "/v1/conversations") if c["connection_id"] == args.connection_id]
    messages = [m for c in convs for m in pages(args.api, token, f"/v1/conversations/{c['id']}/messages")]
    notices = [n for n in pages(args.api, token, "/v1/inbound-notices") if n["connection_id"] == args.connection_id]
    assisted = [
        a
        for st in ("pending", "user_reported_sent", "dismissed", "canceled")
        for a in pages(args.api, token, "/v1/assisted-deliveries", status=st)
        if a["connection_id"] == args.connection_id
    ]
    actions = pages(args.api, token, "/v1/agent-actions")
    record = {
        "run_id": args.run_id,
        "since": args.since,
        **summarize(conn, outbox, messages, notices, assisted, actions, args.since),
    }
    print(json.dumps(record, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
