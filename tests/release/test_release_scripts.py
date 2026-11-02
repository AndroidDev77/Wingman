"""WING-026: tests for scripts/release/ (version bump, build plan, pre-release gate).

Stdlib only, no network, no Docker, no Android SDK:

    python3 -m unittest discover -s tests/release -v

Every case runs the real scripts against throwaway copies (a temp version file, a temp git
repository, or a fake repository whose check scripts are stubs that record the order they
ran in), so nothing in the work tree is changed and nothing real is built.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RELEASE = REPO / "scripts" / "release"
CHECKS = (
    "scripts/backend-check.sh",
    "contracts/validate.sh",
    "scripts/migration-check.sh",
    "scripts/e2e/run.sh",
    "scripts/e2e/platforms/run.sh",
)
# WING-032: check.sh and build.sh refuse to run without valid release feature gates.
GATE_FILES = ("config/release/feature_gates.json", "docs/integrations/cross_platform_test_matrix.md")


def copy_gates(repo: Path) -> None:
    for rel in GATE_FILES:
        (repo / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(REPO / rel, repo / rel)


def make_gate_autonomous_without_evidence(repo: Path) -> None:
    import json

    path = repo / GATE_FILES[0]
    cfg = json.loads(path.read_text())
    gate = next(f for f in cfg["features"] if f["id"] == "sms.autopilot")
    gate["mode"] = "autonomous"
    gate["ui_label"] = "Autonomous: SMS autopilot"
    path.write_text(json.dumps(cfg))
G5_BLOCKED = "| G5 | One real connector verified on a physical device | ⛔ SMS is UNVERIFIED |\n"
G5_MET = "| G5 | One real connector verified on a physical device | ✅ device run 2026-12-01 |\n"


def run(args: list[str], env: dict[str, str] | None = None, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    full_env = {**os.environ, **(env or {})}
    return subprocess.run(  # noqa: S603
        ["bash", *args], capture_output=True, text=True, env=full_env, cwd=cwd, timeout=60, check=False
    )


class VersionScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="wing026-version-"))
        self.file = self.tmp / "version.env"
        self.file.write_text((RELEASE / "version.env").read_text())
        self.env = {"WINGMAN_RELEASE_VERSION_FILE": str(self.file)}

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def version(self, *args: str) -> subprocess.CompletedProcess[str]:
        return run([str(RELEASE / "version.sh"), *args], self.env)

    def set_version(self, version: str, code: str) -> None:
        self.file.write_text(f"WINGMAN_RELEASE_VERSION={version}\nWINGMAN_RELEASE_VERSION_CODE={code}\n")

    def test_committed_version_is_the_first_beta(self) -> None:
        r = self.version("show")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), "0.1.0-beta.1 2")

    def test_bump_kinds(self) -> None:
        cases = {
            "beta": ("0.1.0-beta.1", "0.1.0-beta.2"),
            "patch": ("0.1.0-beta.3", "0.1.1-beta.1"),
            "minor": ("0.1.4-beta.2", "0.2.0-beta.1"),
        }
        for kind, (before, after) in cases.items():
            with self.subTest(kind=kind):
                self.set_version(before, "7")
                r = self.version("bump", kind)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertEqual(self.version("show").stdout.strip(), f"{after} 8")

    def test_dry_run_changes_nothing(self) -> None:
        before = self.file.read_text()
        r = self.version("bump", "beta", "--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("0.1.0-beta.1 (2) -> 0.1.0-beta.2 (3)", r.stdout)
        self.assertEqual(self.file.read_text(), before)

    def test_rejects_bad_input(self) -> None:
        self.assertEqual(self.version("bump", "major").returncode, 2)
        self.assertEqual(self.version("frobnicate").returncode, 2)
        self.assertEqual(self.version().returncode, 2)
        for version, code in (("0.1.0", "2"), ("0.1.0-beta.0", "2"), ("0.1.0-rc.1", "2"), ("0.1.0-beta.1", "x")):
            with self.subTest(version=version, code=code):
                self.set_version(version, code)
                r = self.version("show")
                self.assertEqual(r.returncode, 2)
                self.assertIn("version.sh:", r.stderr)

    def test_file_is_data_not_code(self) -> None:
        marker = self.tmp / "pwned"
        self.file.write_text(
            f"WINGMAN_RELEASE_VERSION=0.1.0-beta.1\nWINGMAN_RELEASE_VERSION_CODE=2\ntouch {marker}\n"
        )
        self.assertEqual(self.version("show").returncode, 0)
        self.assertFalse(marker.exists(), "version.env must be parsed, never sourced")

    def test_missing_file(self) -> None:
        r = run([str(RELEASE / "version.sh"), "show"], {"WINGMAN_RELEASE_VERSION_FILE": str(self.tmp / "nope")})
        self.assertEqual(r.returncode, 2)


class BuildScriptTest(unittest.TestCase):
    """build.sh --dry-run: the plan (tags, Gradle properties) and the refusals, building nothing."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="wing026-build-"))
        self.repo = self.tmp / "repo"
        self.repo.mkdir()
        subprocess.run(["git", "init", "-q", str(self.repo)], check=True)  # noqa: S603,S607
        (self.repo / "README").write_text("x\n")
        copy_gates(self.repo)
        git = ["git", "-C", str(self.repo), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
        subprocess.run([*git, "add", "README", "config", "docs"], check=True)  # noqa: S603
        subprocess.run([*git, "commit", "-q", "-m", "init"], check=True)  # noqa: S603
        self.sha = subprocess.run(  # noqa: S603
            ["git", "-C", str(self.repo), "rev-parse", "--short=12", "HEAD"],  # noqa: S607
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        vf = self.tmp / "version.env"
        vf.write_text("WINGMAN_RELEASE_VERSION=0.3.1-beta.4\nWINGMAN_RELEASE_VERSION_CODE=17\n")
        self.env = {
            "WINGMAN_RELEASE_REPO": str(self.repo),
            "WINGMAN_RELEASE_VERSION_FILE": str(vf),
            "WINGMAN_RELEASE_GATES_REPO": str(self.repo),
        }

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def build(self, *args: str) -> subprocess.CompletedProcess[str]:
        return run([str(RELEASE / "build.sh"), *args], self.env)

    def test_dry_run_plan_carries_version_into_both_artifacts(self) -> None:
        r = self.build("--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn(f"docker build -t wingman-backend:0.3.1-beta.4-{self.sha}", r.stdout)
        self.assertIn("-Pwingman.versionName=0.3.1-beta.4", r.stdout)
        self.assertIn("-Pwingman.versionCode=17", r.stdout)
        self.assertIn("-Pwingman.useMockApi=false", r.stdout)
        self.assertIn("version.init.gradle", r.stdout)
        self.assertIn(":app:assembleDebug", r.stdout)
        self.assertNotIn("apiBaseUrl", r.stdout)
        self.assertIn("dry run: nothing built", r.stdout)
        self.assertFalse((self.repo / "build").exists())

    def test_api_base_url(self) -> None:
        ok = ("https://wingman-beta.example.net/", "http://10.0.2.2:8000/", "http://127.0.0.1:8000/", "http://localhost:8000/")
        for url in ok:
            with self.subTest(url=url):
                r = self.build("--dry-run", "--api-base-url", url)
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertIn(f"-Pwingman.apiBaseUrl={url}", r.stdout)
        bad = ("http://192.168.1.20:8000/", "https://no-trailing-slash.example", "ftp://x/", "", "https://a b/")
        for url in bad:
            with self.subTest(url=url):
                self.assertEqual(self.build("--dry-run", "--api-base-url", url).returncode, 2)

    def test_skip_flags(self) -> None:
        r = self.build("--dry-run", "--skip-image")
        self.assertNotIn("docker build", r.stdout)
        self.assertIn("gradlew", r.stdout)
        r = self.build("--dry-run", "--skip-apk")
        self.assertIn("docker build", r.stdout)
        self.assertNotIn("gradlew", r.stdout)

    def test_refuses_dirty_tree(self) -> None:
        (self.repo / "README").write_text("changed\n")
        r = self.build("--dry-run")
        self.assertEqual(r.returncode, 2)
        self.assertIn("uncommitted changes", r.stderr)
        self.assertEqual(self.build("--dry-run", "--allow-dirty").returncode, 0)

    def test_unknown_argument(self) -> None:
        self.assertEqual(self.build("--release").returncode, 2)

    def test_gates_are_recorded_in_the_plan(self) -> None:
        r = self.build("--dry-run")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("gates:     sms.approved_reply=assisted_only", r.stdout)
        self.assertNotIn("=autonomous", r.stdout)

    def test_refuses_to_build_with_an_unverified_autonomous_gate(self) -> None:
        make_gate_autonomous_without_evidence(self.repo)
        r = self.build("--dry-run", "--allow-dirty")
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("sms.autopilot: claims enabled-autonomous without a VERIFIED evidence record", r.stderr)
        self.assertNotIn("would run", r.stdout)


class CheckScriptTest(unittest.TestCase):
    """check.sh against a fake repository whose four checks are recording stubs."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="wing026-check-"))
        self.repo = self.tmp / "repo"
        self.log = self.tmp / "ran.log"
        (self.repo / "docs").mkdir(parents=True)
        (self.repo / "docs" / "STATUS.md").write_text("| Gate | Description | Status |\n|---|---|---|\n" + G5_BLOCKED)
        copy_gates(self.repo)
        for c in CHECKS:
            self.stub(c, 0)
        self.env = {"WINGMAN_RELEASE_REPO": str(self.repo)}

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def stub(self, rel: str, code: int) -> None:
        path = self.repo / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f'#!/usr/bin/env bash\necho "{rel}" >> "{self.log}"\nexit {code}\n')
        path.chmod(0o755)

    def ran(self) -> list[str]:
        return self.log.read_text().split() if self.log.exists() else []

    def check(self, *args: str) -> subprocess.CompletedProcess[str]:
        return run([str(RELEASE / "check.sh"), *args], self.env)

    def test_all_pass_in_order(self) -> None:
        r = self.check()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(self.ran(), list(CHECKS))
        self.assertIn("PRE-RELEASE CHECKS PASSED (5/5)", r.stdout)
        self.assertIn("LIMITED PRIVATE BETA only", r.stdout)

    def test_stops_at_first_failure(self) -> None:
        for i, failing in enumerate(CHECKS):
            with self.subTest(failing=failing):
                self.log.unlink(missing_ok=True)
                for c in CHECKS:
                    self.stub(c, 1 if c == failing else 0)
                r = self.check()
                self.assertEqual(r.returncode, 1)
                self.assertEqual(self.ran(), list(CHECKS[: i + 1]), "later checks must not run")
                self.assertIn(f"PRE-RELEASE CHECK FAILED at [{i + 1}/5] {failing}", r.stderr)
                self.assertNotIn("PASSED", r.stdout)

    def test_missing_check_script_fails(self) -> None:
        (self.repo / "scripts" / "migration-check.sh").unlink()
        r = self.check()
        self.assertEqual(r.returncode, 1)
        self.assertIn("missing or not executable", r.stderr)
        self.assertEqual(self.ran(), list(CHECKS[:2]))

    def test_paid_launch_refused_while_g5_blocked(self) -> None:
        r = self.check("--paid")
        self.assertEqual(r.returncode, 3)
        self.assertIn("PAID LAUNCH REFUSED", r.stderr)
        self.assertEqual(self.ran(), [], "a refused paid launch runs nothing")

    def test_paid_launch_refused_when_g5_unknown(self) -> None:
        (self.repo / "docs" / "STATUS.md").write_text("no gates here\n")
        self.assertEqual(self.check("--paid", "--dry-run").returncode, 3)

    def test_paid_launch_allowed_once_g5_met(self) -> None:
        (self.repo / "docs" / "STATUS.md").write_text(G5_MET)
        r = self.check("--paid")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("gate G5 (real connector verified on a physical device): met", r.stdout)
        self.assertNotIn("LIMITED PRIVATE BETA", r.stdout)
        self.assertEqual(self.ran(), list(CHECKS))

    def test_dry_run_runs_nothing(self) -> None:
        r = self.check("--dry-run")
        self.assertEqual(r.returncode, 0)
        self.assertEqual(self.ran(), [])
        for c in CHECKS:
            self.assertIn(f"would run: {c}", r.stdout)

    def test_unknown_argument(self) -> None:
        self.assertEqual(self.check("--force").returncode, 2)

    def test_unverified_autonomous_gate_refuses_the_release(self) -> None:
        make_gate_autonomous_without_evidence(self.repo)
        for args in ((), ("--dry-run",)):
            with self.subTest(args=args):
                r = self.check(*args)
                self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
                self.assertIn("RELEASE REFUSED", r.stderr)
                self.assertEqual(self.ran(), [], "nothing runs when the gates are violated")

    def test_missing_gate_config_refuses_the_release(self) -> None:
        (self.repo / GATE_FILES[0]).unlink()
        self.assertEqual(self.check("--dry-run").returncode, 4)

    def test_real_repository_reports_g5_blocked(self) -> None:
        """The real dashboard: G5 is blocked today, so a paid launch is refused."""
        r = run([str(RELEASE / "check.sh"), "--dry-run", "--paid"], {"WINGMAN_RELEASE_REPO": str(REPO)})
        self.assertEqual(r.returncode, 3, r.stdout + r.stderr)
        self.assertIn("G5 is blocked", r.stderr)


class ShellcheckTest(unittest.TestCase):
    def test_release_scripts_pass_shellcheck(self) -> None:
        exe = shutil.which("shellcheck")
        if exe is None:
            self.skipTest("shellcheck not installed (run: uvx --from shellcheck-py shellcheck scripts/release/*.sh)")
        r = subprocess.run(  # noqa: S603
            [exe, *sorted(str(p) for p in RELEASE.glob("*.sh"))], capture_output=True, text=True, check=False
        )
        self.assertEqual(r.returncode, 0, r.stdout)


if __name__ == "__main__":
    unittest.main()
