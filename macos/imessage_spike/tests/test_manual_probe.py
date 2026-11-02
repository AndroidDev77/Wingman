"""The manual probe must refuse to act off-Mac and must never send without confirmation."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

PROBE = Path(__file__).resolve().parents[1] / "manual" / "mac_probe.py"


@pytest.mark.skipif(sys.platform == "darwin", reason="Linux/CI behaviour only")
def test_env_refuses_off_mac() -> None:
    proc = subprocess.run([sys.executable, str(PROBE), "env"], capture_output=True, text=True, check=False)
    assert proc.returncode == 2
    assert "Not macOS" in proc.stderr


def test_send_rejects_bad_input_before_any_prompt() -> None:
    proc = subprocess.run(
        [sys.executable, str(PROBE), "send", "--chat-guid", 'x" & quit', "--text", "hi"],
        capture_output=True,
        text=True,
        input="SEND\n",
        check=False,
    )
    assert proc.returncode != 0
    assert "SendCommandError" in proc.stderr
    assert "Type SEND" not in proc.stdout


def test_send_aborts_without_typed_confirmation(monkeypatch: pytest.MonkeyPatch) -> None:
    sys.path.insert(0, str(PROBE.parent))
    import mac_probe  # type: ignore[import-not-found]

    ran: list[object] = []
    monkeypatch.setattr(mac_probe.subprocess, "run", lambda *a, **k: ran.append(a))
    monkeypatch.setattr("builtins.input", lambda _: "no")
    args = type("A", (), {"chat_guid": "iMessage;-;+15550100001", "text": "hi"})()
    assert mac_probe.cmd_send(args) == 1
    assert ran == []
