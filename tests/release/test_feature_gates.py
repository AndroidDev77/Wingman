"""WING-032: tests for scripts/release/check_feature_gates.py (stdlib only, no network).

    python3 -m unittest discover -s tests/release -v

Each case edits a throwaway copy of the real gate file and matrix, so the committed files are
never changed. The real repository must pass as committed.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
CHECKER = REPO / "scripts" / "release" / "check_feature_gates.py"
GATES = "config/release/feature_gates.json"
MATRIX = "docs/integrations/cross_platform_test_matrix.md"
DEVICE_RECORD = {
    "id": "E-SMS-D-TEST",
    "platform": "sms",
    "capabilities": ["read", "send"],
    "result": "PASSED ON DEVICE",
    "verification": "VERIFIED",
    "date": "2026-12-01",
    "evidence_ref": MATRIX,
}


class FeatureGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="wing032-gates-"))
        for rel in (GATES, MATRIX):
            (self.tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(REPO / rel, self.tmp / rel)
        self.cfg: dict[str, Any] = json.loads((self.tmp / GATES).read_text())

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def run_checker(self) -> subprocess.CompletedProcess[str]:
        (self.tmp / GATES).write_text(json.dumps(self.cfg))
        return subprocess.run(  # noqa: S603
            [sys.executable, str(CHECKER), "--repo", str(self.tmp)], capture_output=True, text=True, check=False
        )

    def gate(self, fid: str) -> dict[str, Any]:
        return next(f for f in self.cfg["features"] if f["id"] == fid)

    def make_autonomous(self, fid: str = "sms.autopilot") -> dict[str, Any]:
        g = self.gate(fid)
        g["mode"] = "autonomous"
        g["ui_label"] = "Autonomous: replies are sent without approval"
        return g

    def test_committed_gates_pass_and_nothing_is_autonomous(self) -> None:
        r = subprocess.run(  # noqa: S603
            [sys.executable, str(CHECKER), "--repo", str(REPO)], capture_output=True, text=True, check=False
        )
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("autonomous: none", r.stdout)
        modes = {f["mode"] for f in self.cfg["features"]}
        self.assertNotIn("autonomous", modes)

    def test_autonomous_without_evidence_fails(self) -> None:
        self.make_autonomous()
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)
        self.assertIn("sms.autopilot: claims enabled-autonomous without a VERIFIED evidence record", r.stderr)

    def test_a_mock_pass_is_never_enough(self) -> None:
        g = self.make_autonomous()
        g["evidence"] = ["E-SMS-MOCK"]
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)
        self.assertIn("for read, send", r.stderr)

    def test_verified_needs_a_device_pass(self) -> None:
        rec = next(r for r in self.cfg["evidence_records"] if r["id"] == "E-SMS-MOCK")
        rec["verification"] = "VERIFIED"
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)
        self.assertIn("E-SMS-MOCK: VERIFIED needs result PASSED ON DEVICE", r.stderr)

    def test_device_evidence_must_be_recorded_in_the_matrix(self) -> None:
        g = self.make_autonomous()
        self.cfg["evidence_records"].append(dict(DEVICE_RECORD))
        g["evidence"] = ["E-SMS-D-TEST"]
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)
        self.assertIn("needs a date and an evidence_ref file that names E-SMS-D-TEST", r.stderr)

    def test_autonomous_passes_only_with_verified_device_evidence_and_matrix_row(self) -> None:
        g = self.make_autonomous()
        self.cfg["evidence_records"].append(dict(DEVICE_RECORD))
        g["evidence"] = ["E-SMS-D-TEST"]
        matrix = self.tmp / MATRIX
        matrix.write_text(matrix.read_text() + "\nE-SMS-D-TEST recorded here.\n")
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)  # the matrix rows still say NOT RUN / PASSED WITH MOCK
        self.assertIn("matrix row 'Android SMS' does not say PASSED ON DEVICE", r.stderr)
        lines = [
            ln.replace("PASSED WITH MOCK", "PASSED ON DEVICE").replace("BLOCKED", "PASSED ON DEVICE")
            if ln.startswith("| Android SMS |")
            else ln
            for ln in matrix.read_text().splitlines()
        ]
        matrix.write_text("\n".join(lines) + "\n")
        r = self.run_checker()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("autonomous: sms.autopilot", r.stdout)

    def test_label_must_match_mode(self) -> None:
        self.gate("tinder.notification_assisted_reply")["ui_label"] = "Autonomous replies in Tinder"
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)
        self.assertIn("ui_label must start with 'Assisted only'", r.stderr)

    def test_unknown_mode_and_evidence(self) -> None:
        self.gate("swipe.execute")["mode"] = "enabled"
        self.gate("discovery.review")["evidence"] = ["E-NOPE"]
        r = self.run_checker()
        self.assertEqual(r.returncode, 1)
        self.assertIn("swipe.execute: mode must be one of", r.stderr)
        self.assertIn("discovery.review: cites unknown evidence record E-NOPE", r.stderr)

    def test_missing_file_is_an_error(self) -> None:
        (self.tmp / GATES).unlink()
        r = subprocess.run(  # noqa: S603
            [sys.executable, str(CHECKER), "--repo", str(self.tmp)], capture_output=True, text=True, check=False
        )
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("cannot read", r.stderr)


if __name__ == "__main__":
    unittest.main()
