# ruff: noqa: S608  (fixed SQL built from module constants; the run prefix is always the bound $1)
"""Run-wide invariants of the platform suite, checked LAST (the file name sorts after every
scenario file). The database is only read.

1. Zero duplicate sends (as WING-025's ZZ1): no message acknowledged twice, no (message,
   attempt) handed to a transport twice, no instruction key leased twice, one draft per inbound
   message, at most one outbound message per approval, no conversation with more agent sends
   than inbound messages, no simulated device with two transport attempts on one key.
2. No false send claims: nothing on a notification (send UNSUPPORTED) connection is ever
   ``sent``/``delivered`` or has an instruction; every ``user_reported_sent`` message has no
   ``sent_at``; no ``autopilot`` message was ever sent on a non-mock platform; no profile action
   is ``executed`` (or even ``queued``).

Writes ``platform_invariants.json`` and ``scenario_results.json`` (the mock pass table).
"""

from __future__ import annotations

import json

from tests.e2e.harness import Db, Server
from tests.e2e.platforms.conftest import SUBJECT_PREFIX
from tests.e2e.platforms.platform_harness import DEVICES, RESULTS, write_artifact

USERS = "SELECT id FROM users WHERE auth_subject LIKE $1"


def _q(db: Db, sql: str) -> list[dict]:
    return db.fetch(sql, f"{SUBJECT_PREFIX}%")


def _count(db: Db, sql: str) -> int:
    return len(_q(db, sql))


def test_zz_zero_duplicate_sends_and_no_false_send_claims(db: Db, server: Server) -> None:
    users = _q(db, USERS)
    assert len(users) >= 20, "the scenarios did not run before the invariants"
    w = f"user_id IN ({USERS})"
    checks = {
        "duplicate_acknowledgements": "SELECT message_id FROM agent_actions WHERE "
        + w
        + " AND type = 'send_acknowledged' GROUP BY message_id HAVING count(*) > 1",
        "duplicate_transport_attempts": "SELECT message_id FROM agent_actions WHERE "
        + w
        + " AND type = 'send_attempted' GROUP BY message_id, details->>'attempt' HAVING count(*) > 1",
        "duplicate_instructions": "SELECT outbound_message_id FROM outbox_instructions WHERE "
        + w
        + " GROUP BY outbound_message_id, attempt HAVING count(*) > 1",
        "duplicate_drafts": "SELECT trigger_message_id FROM drafts WHERE "
        + w
        + " AND trigger_message_id IS NOT NULL GROUP BY trigger_message_id HAVING count(*) > 1",
        "duplicate_approval_sends": "SELECT approval_id FROM messages WHERE "
        + w
        + " AND approval_id IS NOT NULL GROUP BY approval_id HAVING count(*) > 1",
        "conversations_over_replied": "SELECT conversation_id FROM messages WHERE "
        + w
        + " GROUP BY conversation_id HAVING count(*) FILTER (WHERE direction = 'outbound' AND origin IN "
        "('autopilot', 'approved_draft') AND delivery_state IN ('sent', 'delivered')) > "
        "count(*) FILTER (WHERE direction = 'inbound')",
        "notification_connection_sends": "SELECT m.id FROM messages m JOIN conversations c ON c.id = m.conversation_id "
        "JOIN platform_connections pc ON pc.id = c.connection_id WHERE m."
        + w
        + " AND pc.transport = 'notification' AND m.delivery_state IN ('sent', 'delivered', 'sending', 'queued')",
        "notification_connection_instructions": "SELECT o.id FROM outbox_instructions o JOIN platform_connections pc "
        "ON pc.id = o.connection_id WHERE o." + w + " AND pc.transport = 'notification'",
        "user_reported_sent_with_sent_at": "SELECT id FROM messages WHERE "
        + w
        + " AND delivery_state = 'user_reported_sent' AND (sent_at IS NOT NULL OR delivered_at IS NOT NULL)",
        "autopilot_sent_on_real_platform": "SELECT id FROM messages WHERE "
        + w
        + " AND origin = 'autopilot' AND platform <> 'mock' AND delivery_state IN ('sent', 'delivered', 'sending')",
        "profile_actions_executed_or_queued": "SELECT id FROM profile_actions WHERE "
        + w
        + " AND execution IN ('executed', 'queued')",
    }
    found = {name: _q(db, sql) for name, sql in checks.items()}
    radio_dups = {k: n for d in DEVICES for k, n in d.radio.items() if n > 1}
    totals = _q(
        db,
        "SELECT "
        f"(SELECT count(*) FROM users WHERE auth_subject LIKE $1) AS users, "
        f"(SELECT count(*) FROM messages WHERE {w} AND direction = 'inbound') AS inbound, "
        f"(SELECT count(*) FROM inbound_notices WHERE {w}) AS notices, "
        f"(SELECT count(*) FROM messages WHERE {w} AND direction = 'outbound') AS outbound, "
        f"(SELECT count(*) FROM messages WHERE {w} AND delivery_state IN ('sent', 'delivered')) AS sent, "
        f"(SELECT count(*) FROM messages WHERE {w} AND delivery_state = 'user_reported_sent') AS user_reported_sent, "
        f"(SELECT count(*) FROM assisted_deliveries WHERE {w}) AS assisted_deliveries, "
        f"(SELECT count(*) FROM agent_actions WHERE {w} AND type = 'send_acknowledged') AS acks, "
        f"(SELECT count(*) FROM agent_actions WHERE {w} AND type = 'lease_expired') AS lease_expired, "
        f"(SELECT count(*) FROM outbox_instructions WHERE {w}) AS instructions, "
        f"(SELECT count(*) FROM profile_actions WHERE {w}) AS profile_actions",
    )[0]
    summary = {
        "run_id": SUBJECT_PREFIX.rstrip("-"),
        **{k: int(v) for k, v in totals.items()},
        "simulated_device_transport_attempts": sum(n for d in DEVICES for n in d.radio.values()),
        "server_starts": server.starts,
        **{k: len(v) for k, v in found.items()},
        "duplicate_radio_attempts": len(radio_dups),
    }
    write_artifact("platform_invariants.json", summary)
    write_artifact("scenario_results.json", RESULTS)
    print("PLATFORM INVARIANTS " + json.dumps(summary, sort_keys=True))
    for name, rows in found.items():
        assert not rows, (name, rows[:5])
    assert not radio_dups, radio_dups
    assert summary["acks"] == summary["sent"], summary


def test_zz_server_log_has_no_unexpected_errors(server: Server) -> None:
    text = server.log_text()
    bad = [
        line
        for line in text.splitlines()
        if "Traceback" in line
        or "IntegrityError" in line
        or "dispatch after commit failed" in line
        or "automation pipeline failed" in line
        or " 500 " in line
    ]
    assert not bad, "\n".join(bad[:20])
