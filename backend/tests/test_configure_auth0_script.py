"""scripts/configure-auth0.sh (I-014): validation, printed output, --write/--dry-run, --check.

No network: ``--check`` runs against a stub ``curl`` placed first on ``PATH``. Every write
goes to a temp directory (never the work tree's ``backend/.env``).
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from app.security.settings import AuthSettings

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "configure-auth0.sh"
DOMAIN = "wingman.us.auth0.com"
CLIENT = "AbCdEf0123456789_-xyz"
API = "https://api.wingman.app"
EXPECTED_ENV = [
    "WINGMAN_AUTH_ISSUER=https://wingman.us.auth0.com/",
    "WINGMAN_AUTH_AUDIENCE=https://api.wingman.app",
    "WINGMAN_AUTH_JWKS_URL=https://wingman.us.auth0.com/.well-known/jwks.json",
    "WINGMAN_AUTH_ALGORITHMS=RS256",
]

pytestmark = pytest.mark.skipif(shutil.which("bash") is None, reason="needs bash")


def run(
    *args: str, env: dict[str, str] | None = None, cwd: Path | None = None
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(  # noqa: S603
        ["bash", str(SCRIPT), *args],  # noqa: S607
        capture_output=True,
        text=True,
        env={**os.environ, **(env or {})},
        cwd=cwd,
        timeout=60,
        check=False,
    )


def test_prints_backend_env_and_gradle_command() -> None:
    r = run(DOMAIN, CLIENT, API)
    assert r.returncode == 0, r.stderr
    lines = r.stdout.splitlines()
    for expected in EXPECTED_ENV:
        assert expected in lines
    gradle = next(line for line in lines if line.startswith("cd android && ./gradlew"))
    assert gradle == (
        "cd android && ./gradlew :app:assembleDebug -Pwingman.useMockApi=false"
        " -Pwingman.oidc.issuer=https://wingman.us.auth0.com/"
        f" -Pwingman.oidc.clientId={CLIENT}"
        " -Pwingman.oidc.redirectUri=com.wingman.auth:/oauth2redirect"
        " -Pwingman.oidc.audience=https://api.wingman.app"
    )
    assert "wingman.oidc.connection" not in r.stdout
    assert "Allowed Callback URLs and Allowed Logout URLs = com.wingman.auth:/oauth2redirect" in (
        r.stdout
    )


def test_connection_is_passed_to_gradle() -> None:
    r = run(DOMAIN, CLIENT, API, "google-oauth2")
    assert r.returncode == 0, r.stderr
    assert " -Pwingman.oidc.connection=google-oauth2" in r.stdout
    assert "\nwingman.oidc.connection=google-oauth2\n" in r.stdout


@pytest.mark.parametrize(
    ("args", "message"),
    [
        pytest.param(("https://wingman.us.auth0.com", CLIENT, API), "without a scheme", id="https"),
        pytest.param(("http://wingman.us.auth0.com", CLIENT, API), "without a scheme", id="http"),
        pytest.param(("wingman.us.auth0.com/", CLIENT, API), "must not contain '/'", id="slash"),
        pytest.param(("Wingman.US.auth0.com", CLIENT, API), "lowercase", id="uppercase"),
        pytest.param(("wingman", CLIENT, API), "host name", id="no-dot"),
        pytest.param(("wing_man.auth0.com", CLIENT, API), "host name", id="underscore"),
        pytest.param(("bad-.auth0.com", CLIENT, API), "host name", id="dash-end-label"),
        pytest.param((DOMAIN, "", API), "must not be empty", id="empty-client"),
        pytest.param((DOMAIN, "a b", API), "client ID may contain", id="space-client"),
        pytest.param((DOMAIN, CLIENT, "api.wingman.app"), "URL-like", id="bare-api-id"),
        pytest.param((DOMAIN, CLIENT, ""), "URL-like", id="empty-api-id"),
        pytest.param((DOMAIN, CLIENT, "https://api wingman"), "URL-like", id="space-api-id"),
        pytest.param(
            (DOMAIN, CLIENT, f"https://{DOMAIN}/userinfo"), "not the /userinfo", id="userinfo"
        ),
        pytest.param((DOMAIN, CLIENT, API, "google oauth2"), "connection", id="bad-connection"),
        pytest.param((DOMAIN, CLIENT), "expected", id="too-few"),
        pytest.param((DOMAIN, CLIENT, API, "a", "b"), "expected", id="too-many"),
        pytest.param(("--nope", DOMAIN, CLIENT, API), "unknown option", id="bad-flag"),
    ],
)
def test_bad_inputs_rejected(args: tuple[str, ...], message: str) -> None:
    r = run(*args)
    assert r.returncode == 2
    assert message in r.stderr
    assert "WINGMAN_AUTH_ISSUER" not in r.stdout


def test_dry_run_write_changes_nothing(tmp_path: Path) -> None:
    target = tmp_path / ".env"
    target.write_text("WINGMAN_ENV=dev\nWINGMAN_AUTH_ISSUER=https://old.example/\n")
    before = target.read_text()
    r = run("--write", "--dry-run", "--env-file", str(target), DOMAIN, CLIENT, API)
    assert r.returncode == 0, r.stderr
    assert target.read_text() == before
    assert not (tmp_path / ".env.bak").exists()
    assert "would write" in r.stdout
    assert "WINGMAN_AUTH_ISSUER=https://old.example/" in r.stdout  # shown as replaced
    assert list(tmp_path.iterdir()) == [target]


def test_write_replaces_auth_lines_and_keeps_the_rest(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    target = tmp_path / ".env"
    target.write_text(
        "WINGMAN_ENV=dev\n"
        "WINGMAN_AUTH_ISSUER=https://old.example/\n"
        "WINGMAN_AUTH_DEV_STUB_ENABLED=true\n"
        "WINGMAN_AUTH_ALGORITHMS=RS256,ES256"  # no trailing newline
    )
    for _ in range(2):  # idempotent: a second run gives the same file
        r = run("--write", "--env-file", str(target), DOMAIN, CLIENT, API)
        assert r.returncode == 0, r.stderr
    text = target.read_text()
    lines = text.splitlines()
    assert "WINGMAN_ENV=dev" in lines
    assert "WINGMAN_AUTH_DEV_STUB_ENABLED=true" in lines
    assert "https://old.example/" not in text
    assert "RS256,ES256" not in text
    for expected in EXPECTED_ENV:
        assert lines.count(expected) == 1
    assert text.count("# Auth0 (I-014") == 1
    assert (target.stat().st_mode & 0o777) == 0o600
    assert (tmp_path / ".env.bak").exists()

    # The written file is a valid backend auth config.
    for name in list(os.environ):
        if name.startswith("WINGMAN_AUTH_"):
            monkeypatch.delenv(name)
    s = AuthSettings(_env_file=str(target))  # type: ignore[call-arg]
    assert s.oidc_configured
    assert (s.issuer, s.audience, s.jwks_url) == (
        "https://wingman.us.auth0.com/",
        API,
        "https://wingman.us.auth0.com/.well-known/jwks.json",
    )
    assert s.algorithm_list() == ["RS256"]


def _git(repo: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True)  # noqa: S603, S607


@pytest.mark.skipif(shutil.which("git") is None, reason="needs git")
def test_write_refuses_a_file_git_would_commit(tmp_path: Path) -> None:
    _git(tmp_path, "init", "-q")
    (tmp_path / ".gitignore").write_text(".env\n")
    tracked = tmp_path / "auth.env"
    r = run("--write", "--env-file", str(tracked), DOMAIN, CLIENT, API)
    assert r.returncode == 2
    assert "git does not ignore it" in r.stderr
    assert not tracked.exists()

    ignored = tmp_path / ".env"
    r = run("--write", "--env-file", str(ignored), DOMAIN, CLIENT, API)
    assert r.returncode == 0, r.stderr
    assert "WINGMAN_AUTH_ISSUER=https://wingman.us.auth0.com/" in ignored.read_text()


@pytest.mark.parametrize(
    "path", ["backend/.env", "backend/.env.bak", "backend/.env.auth0.AbC123", ".env.bak"]
)
def test_repo_backend_env_is_git_ignored(path: str) -> None:
    """The default --write target, its .bak copy and the temp file never reach a commit."""
    repo = SCRIPT.parents[1]
    if not (repo / ".git").exists():
        pytest.skip("not a git checkout")
    r = subprocess.run(  # noqa: S603
        ["git", "-C", str(repo), "check-ignore", "-q", path],  # noqa: S607
        check=False,
    )
    assert r.returncode == 0


def test_repo_env_example_is_not_ignored() -> None:
    repo = SCRIPT.parents[1]
    if not (repo / ".git").exists():
        pytest.skip("not a git checkout")
    r = subprocess.run(  # noqa: S603
        ["git", "-C", str(repo), "check-ignore", "-q", ".env.example"],  # noqa: S607
        check=False,
    )
    assert r.returncode == 1


@pytest.mark.skipif(shutil.which("git") is None, reason="needs git")
def test_write_refuses_when_backup_would_be_committed(tmp_path: Path) -> None:
    """An existing .env is copied to .env.bak; refuse if git would not ignore that copy."""
    _git(tmp_path, "init", "-q")
    (tmp_path / ".gitignore").write_text(".env\n")  # ignores .env but not .env.bak
    target = tmp_path / ".env"
    target.write_text("WINGMAN_DATABASE_URL=postgresql://u:secret@db/x\n")
    r = run("--write", "--env-file", str(target), DOMAIN, CLIENT, API)
    assert r.returncode == 2
    assert "backup" in r.stderr
    assert not (tmp_path / ".env.bak").exists()
    assert target.read_text() == "WINGMAN_DATABASE_URL=postgresql://u:secret@db/x\n"


def _fake_curl(tmp_path: Path, body: str, rc: int) -> dict[str, str]:
    bindir = tmp_path / "bin"
    bindir.mkdir()
    curl = bindir / "curl"
    curl.write_text(
        "#!/usr/bin/env bash\n"
        f'echo "$@" > "{tmp_path}/curl-args"\n'
        f"printf '%s' '{body}'\n"
        f"exit {rc}\n"
    )
    curl.chmod(0o755)
    return {"PATH": f"{bindir}{os.pathsep}{os.environ['PATH']}"}


def test_check_reports_ok_for_an_rs256_jwks(tmp_path: Path) -> None:
    env = _fake_curl(tmp_path, '{"keys":[{"kty":"RSA","alg":"RS256","kid":"k1","use":"sig"}]}', 0)
    r = run("--check", DOMAIN, CLIENT, API, env=env)
    assert r.returncode == 0, r.stderr
    assert "OK: the JWKS lists signing keys" in r.stdout
    assert (
        "https://wingman.us.auth0.com/.well-known/jwks.json" in (tmp_path / "curl-args").read_text()
    )


@pytest.mark.parametrize(
    ("body", "rc", "message"),
    [
        pytest.param(
            "curl: (56) CONNECT tunnel failed, response 403", 56, "could not fetch", id="blocked"
        ),
        pytest.param("<html>login</html>", 0, "not with a JWKS", id="not-jwks"),
        pytest.param('{"keys":[{"kty":"EC","alg":"ES256"}]}', 0, "RS256", id="no-rsa"),
    ],
)
def test_check_failure_is_unverified_not_a_crash(
    tmp_path: Path, body: str, rc: int, message: str
) -> None:
    env = _fake_curl(tmp_path, body, rc)
    r = run("--check", DOMAIN, CLIENT, API, env=env)
    assert r.returncode == 3
    assert message in r.stderr
    # Everything else was still printed.
    assert "WINGMAN_AUTH_ISSUER=https://wingman.us.auth0.com/" in r.stdout


def test_help() -> None:
    r = run("--help")
    assert r.returncode == 0
    assert "<tenant-domain> <client-id> <api-identifier> [connection]" in r.stdout
