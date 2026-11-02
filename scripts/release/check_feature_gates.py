#!/usr/bin/env python3
"""WING-032: release feature-gate check (stdlib only, no network).

    python3 scripts/release/check_feature_gates.py [--repo DIR] [--summary]

Reads ``config/release/feature_gates.json`` and fails (exit 1) when the gate configuration
promises more than the evidence shows:

* every feature has a known ``mode`` (``disabled``, ``assisted_only``, ``experimental``,
  ``autonomous``) and a ``ui_label`` carrying that mode's tag, so nothing unverified is shown as
  fully autonomous;
* a feature in ``autonomous`` mode needs, for EVERY capability in ``requires_verified``, an
  evidence record listed in its ``evidence`` with ``result: PASSED ON DEVICE`` and
  ``verification: VERIFIED``, a date, and an ``evidence_ref`` file that exists and names the
  record id; and its row in the cross-platform matrix must say ``PASSED ON DEVICE``;
* evidence records use only the matrix statuses, and ``VERIFIED`` only with ``PASSED ON DEVICE``;
* every evidence id a feature cites exists.

The backend's real behaviour is cross-checked against the same file by the platform E2E suite
(``tests/e2e/platforms/test_p7_release_gates.py``). Exit: 0 ok, 1 gate violation, 2 unreadable.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

MODES = {
    "disabled": "Not available",
    "assisted_only": "Assisted only",
    "experimental": "Experimental",
    "autonomous": "Autonomous",
}
RESULTS = {"PASSED ON DEVICE", "PASSED WITH MOCK", "FAILED", "BLOCKED", "NOT RUN"}
VERIFICATIONS = {"VERIFIED", "UNVERIFIED", "UNSUPPORTED"}
CAPABILITIES = {"read", "draft", "send", "swipe", "calendar_write", "profile_read"}
GATES = Path("config/release/feature_gates.json")


def check(repo: Path) -> tuple[list[str], dict[str, Any]]:
    path = repo / GATES
    try:
        cfg: dict[str, Any] = json.loads(path.read_text())
    except (OSError, ValueError) as exc:
        raise SystemExit(f"feature gates: cannot read {path}: {exc}") from exc
    errors: list[str] = []
    if cfg.get("schema_version") != "wingman.feature_gates.v1":
        errors.append("schema_version must be wingman.feature_gates.v1")
    records: dict[str, dict[str, Any]] = {}
    for rec in cfg.get("evidence_records", []):
        rid = rec.get("id")
        if not rid or rid in records:
            errors.append(f"evidence record id missing or duplicated: {rid!r}")
            continue
        records[rid] = rec
        if rec.get("result") not in RESULTS:
            errors.append(f"{rid}: result must be one of {sorted(RESULTS)}")
        if rec.get("verification") not in VERIFICATIONS:
            errors.append(f"{rid}: verification must be one of {sorted(VERIFICATIONS)}")
        if rec.get("verification") == "VERIFIED" and rec.get("result") != "PASSED ON DEVICE":
            errors.append(f"{rid}: VERIFIED needs result PASSED ON DEVICE (a mock pass never verifies)")
        unknown = set(rec.get("capabilities", [])) - CAPABILITIES
        if unknown:
            errors.append(f"{rid}: unknown capabilities {sorted(unknown)}")
    matrix_path = repo / str(cfg.get("matrix", ""))
    matrix = matrix_path.read_text() if matrix_path.is_file() else ""
    if not matrix:
        errors.append(f"matrix document missing: {cfg.get('matrix')!r}")
    seen: set[str] = set()
    features = cfg.get("features", [])
    if not features:
        errors.append("no features configured")
    for f in features:
        fid = f.get("id")
        if not fid or fid in seen:
            errors.append(f"feature id missing or duplicated: {fid!r}")
            continue
        seen.add(fid)
        mode = f.get("mode")
        if mode not in MODES:
            errors.append(f"{fid}: mode must be one of {sorted(MODES)}")
            continue
        label = str(f.get("ui_label", ""))
        if not label.startswith(MODES[mode]):
            errors.append(f"{fid}: ui_label must start with {MODES[mode]!r} for mode {mode}")
        for other, tag in MODES.items():
            if other != mode and other != "autonomous" and label.startswith(tag):
                errors.append(f"{fid}: ui_label tag {tag!r} contradicts mode {mode}")
        cited = f.get("evidence", [])
        for rid in cited:
            if rid not in records:
                errors.append(f"{fid}: cites unknown evidence record {rid}")
        if mode != "autonomous":
            continue
        # Autonomous: every required capability needs a VERIFIED device record.
        required = set(f.get("requires_verified", []))
        if not required:
            errors.append(f"{fid}: autonomous needs a non-empty requires_verified")
        covered: set[str] = set()
        for rid in cited:
            rec = records.get(rid)
            if not rec or rec.get("result") != "PASSED ON DEVICE" or rec.get("verification") != "VERIFIED":
                continue
            ref = repo / str(rec.get("evidence_ref", ""))
            if not rec.get("date") or not ref.is_file() or rid not in ref.read_text():
                errors.append(f"{fid}: evidence {rid} needs a date and an evidence_ref file that names {rid}")
                continue
            covered |= set(rec.get("capabilities", []))
        missing = sorted(required - covered)
        if missing:
            errors.append(
                f"{fid}: claims enabled-autonomous without a VERIFIED evidence record (PASSED ON DEVICE) "
                f"for {', '.join(missing)}"
            )
        row = str(f.get("matrix_row", ""))
        lines = [ln for ln in matrix.splitlines() if ln.startswith(f"| {row} |")]
        if not row or not any("PASSED ON DEVICE" in ln for ln in lines):
            errors.append(f"{fid}: autonomous but the matrix row {row!r} does not say PASSED ON DEVICE")
    return errors, cfg


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--summary", action="store_true", help="print one line per feature")
    args = parser.parse_args()
    errors, cfg = check(args.repo)
    if args.summary or not errors:
        for f in cfg.get("features", []):
            print(f"  {f.get('id', '?'):38} {f.get('mode', '?'):14} {f.get('ui_label', '')}")
    if errors:
        print("FEATURE GATES FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1
    autonomous = [f["id"] for f in cfg["features"] if f["mode"] == "autonomous"]
    print(f"feature gates OK: {len(cfg['features'])} features, autonomous: {', '.join(autonomous) or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
