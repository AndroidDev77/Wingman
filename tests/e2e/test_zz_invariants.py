"""Run-wide invariants, checked LAST (file name sorts after every scenario file).

"Mock end-to-end flow passes with zero duplicate sends" is asserted here over everything
every scenario of this run did (both concurrency and restarts included), not per test.
The database is only read.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

from tests.e2e.conftest import ARTIFACTS, RUN_ID
from tests.e2e.harness import Db, Server
from tests.e2e.test_failures import DEVICES


def _rows(db: Db, sql: str) -> list[dict]:
    return db.fetch(sql, f"e2e-{RUN_ID}-%")


RUN_USERS = "SELECT id FROM users WHERE auth_subject LIKE $1"


def test_zz_zero_duplicate_sends_over_the_whole_run(db: Db, server: Server) -> None:
    users = _rows(db, RUN_USERS)
    assert len(users) >= 10, "the scenarios did not run before the invariants"

    # 1. No outbound message was acknowledged (sent) more than once.
    double_ack = _rows(
        db,
        "SELECT message_id, count(*) AS n FROM agent_actions "
        f"WHERE user_id IN ({RUN_USERS}) AND type = 'send_acknowledged' "
        "GROUP BY message_id HAVING count(*) > 1",
    )
    # 2. No (message, attempt) was handed to a transport more than once.
    double_attempt = _rows(
        db,
        "SELECT message_id, details->>'attempt' AS attempt, count(*) AS n FROM agent_actions "
        f"WHERE user_id IN ({RUN_USERS}) AND type = 'send_attempted' "
        "GROUP BY message_id, details->>'attempt' HAVING count(*) > 1",
    )
    # 3. No device instruction key was leased twice, and no message has two live instructions.
    double_instruction = _rows(
        db,
        "SELECT outbound_message_id, attempt, count(*) AS n FROM outbox_instructions "
        f"WHERE user_id IN ({RUN_USERS}) GROUP BY outbound_message_id, attempt HAVING count(*) > 1",
    )
    # 4. One draft per inbound message, and at most one outbound message per approval.
    double_draft = _rows(
        db,
        "SELECT trigger_message_id, count(*) AS n FROM drafts "
        f"WHERE user_id IN ({RUN_USERS}) AND trigger_message_id IS NOT NULL "
        "GROUP BY trigger_message_id HAVING count(*) > 1",
    )
    double_approval_send = _rows(
        db,
        "SELECT approval_id, count(*) AS n FROM messages "
        f"WHERE user_id IN ({RUN_USERS}) AND approval_id IS NOT NULL "
        "GROUP BY approval_id HAVING count(*) > 1",
    )
    # 5. No conversation got more automated (agent) sends than it has inbound messages.
    over_replied = _rows(
        db,
        "SELECT conversation_id, "
        "count(*) FILTER (WHERE direction = 'outbound' AND origin IN ('autopilot', 'approved_draft') "
        "AND delivery_state IN ('sent', 'delivered')) AS agent_sent, "
        "count(*) FILTER (WHERE direction = 'inbound') AS inbound "
        f"FROM messages WHERE user_id IN ({RUN_USERS}) GROUP BY conversation_id "
        "HAVING count(*) FILTER (WHERE direction = 'outbound' AND origin IN ('autopilot', 'approved_draft') "
        "AND delivery_state IN ('sent', 'delivered')) > count(*) FILTER (WHERE direction = 'inbound')",
    )
    # 6. Every simulated device made at most one radio attempt per instruction key.
    radio_dups = {k: n for d in DEVICES for k, n in d.radio.items() if n > 1}

    totals = _rows(
        db,
        "SELECT "
        f"(SELECT count(*) FROM users WHERE auth_subject LIKE $1) AS users, "
        f"(SELECT count(*) FROM messages WHERE user_id IN ({RUN_USERS}) AND direction = 'inbound') AS inbound, "
        f"(SELECT count(*) FROM messages WHERE user_id IN ({RUN_USERS}) AND direction = 'outbound') AS outbound, "
        f"(SELECT count(*) FROM messages WHERE user_id IN ({RUN_USERS}) AND direction = 'outbound' "
        "AND delivery_state IN ('sent', 'delivered')) AS sent, "
        f"(SELECT count(*) FROM agent_actions WHERE user_id IN ({RUN_USERS}) AND type = 'send_acknowledged') AS acks, "
        f"(SELECT count(*) FROM agent_actions WHERE user_id IN ({RUN_USERS}) AND type = 'send_attempted') AS attempts, "
        f"(SELECT count(*) FROM outbox_instructions WHERE user_id IN ({RUN_USERS})) AS instructions",
    )[0]
    summary = {
        "run_id": RUN_ID,
        **{k: int(v) for k, v in totals.items()},
        "simulated_device_radio_attempts": sum(n for d in DEVICES for n in d.radio.values()),
        "server_starts": server.starts,
        "duplicate_acknowledgements": len(double_ack),
        "duplicate_transport_attempts": len(double_attempt),
        "duplicate_instructions": len(double_instruction),
        "duplicate_drafts": len(double_draft),
        "duplicate_approval_sends": len(double_approval_send),
        "conversations_over_replied": len(over_replied),
        "duplicate_radio_attempts": len(radio_dups),
    }
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    (ARTIFACTS / "zero_duplicate_sends.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("ZERO-DUPLICATE-SENDS SUMMARY " + json.dumps(summary, sort_keys=True))
    assert not double_ack, double_ack
    assert not double_attempt, double_attempt
    assert not double_instruction, double_instruction
    assert not double_draft, double_draft
    assert not double_approval_send, double_approval_send
    assert not over_replied, over_replied
    assert not radio_dups, radio_dups
    # Every acknowledgement corresponds to exactly one sent/delivered message.
    assert summary["acks"] == summary["sent"], summary


def test_zz_server_log_has_no_unexpected_errors(server: Server) -> None:
    """No unhandled exception, integrity error or failed post-commit dispatch in the
    server log of the whole run (the WING-025 mock-ID bug showed up only here)."""
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
    assert os.environ.get("WINGMAN_DATABASE_URL")
    assert Path(server.log_path).exists()
