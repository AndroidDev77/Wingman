"""I-014: the committed beta Auth0 config (config/beta/) and how the scripts use it.

The values are public identifiers (no secrets). Only scripts/release/build.sh and
``scripts/configure-auth0.sh --beta`` read them; plain Gradle, scripts/android-build.sh, CI
and E2E builds must not, because they rely on the dev-token path and mock builds.
No network: nothing here contacts the tenant (the first real sign-in is the verification).
"""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

from app.security.settings import AuthSettings

REPO = Path(__file__).resolve().parents[2]
BETA = REPO / "config" / "beta"
ENV_FILE = BETA / "auth0.env"
PROPS_FILE = BETA / "auth0.gradle.properties"
BUILD = REPO / "scripts" / "release" / "build.sh"
CONFIGURE = REPO / "scripts" / "configure-auth0.sh"

TENANT = "dev-zumw4z1t7m8hefst.us.auth0.com"
CLIENT_ID = "XYOVrRYWqOQXj2JH5cEyrGTUg0ypq750"
AUDIENCE = "https://api.wingman.app"
OIDC_FLAGS = [
    f"-Pwingman.oidc.issuer=https://{TENANT}/",
    f"-Pwingman.oidc.clientId={CLIENT_ID}",
    "-Pwingman.oidc.redirectUri=com.wingman.auth:/oauth2redirect",
    f"-Pwingman.oidc.audience={AUDIENCE}",
    "-Pwingman.oidc.connection=google-oauth2",
]

needs_bash = pytest.mark.skipif(
    shutil.which("bash") is None or shutil.which("git") is None, reason="needs bash and git"
)


def parse(path: Path) -> dict[str, str]:
    """KEY=VALUE lines; comments and blanks skipped; anything else fails the test."""
    out: dict[str, str] = {}
    for n, raw in enumerate(path.read_text().splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, sep, value = line.partition("=")
        assert sep and key and value and value == value.strip(), f"{path.name}:{n}: {raw!r}"
        assert key not in out, f"{path.name}:{n}: duplicate {key}"
        out[key] = value
    return out


def test_backend_env_parses_as_auth_settings() -> None:
    env = parse(ENV_FILE)
    assert set(env) == {
        "WINGMAN_AUTH_ISSUER",
        "WINGMAN_AUTH_AUDIENCE",
        "WINGMAN_AUTH_JWKS_URL",
        "WINGMAN_AUTH_ALGORITHMS",
    }
    s = AuthSettings(_env_file=str(ENV_FILE))  # type: ignore[call-arg]
    assert s.oidc_configured
    assert s.issuer == f"https://{TENANT}/"
    assert s.audience == AUDIENCE
    assert s.algorithm_list() == ["RS256"]


def test_issuer_has_trailing_slash_and_jwks_derives_from_it() -> None:
    env = parse(ENV_FILE)
    issuer = env["WINGMAN_AUTH_ISSUER"]
    assert issuer.startswith("https://")
    assert issuer.endswith("/")
    assert issuer.count("/") == 3  # https://<domain>/ and nothing after it
    assert env["WINGMAN_AUTH_JWKS_URL"] == issuer + ".well-known/jwks.json"


def test_gradle_properties_parse_and_agree_with_backend() -> None:
    props = parse(PROPS_FILE)
    env = parse(ENV_FILE)
    assert props == {
        "wingman.oidc.issuer": env["WINGMAN_AUTH_ISSUER"],
        "wingman.oidc.clientId": CLIENT_ID,
        "wingman.oidc.redirectUri": "com.wingman.auth:/oauth2redirect",
        "wingman.oidc.audience": env["WINGMAN_AUTH_AUDIENCE"],
        "wingman.oidc.connection": "google-oauth2",
    }


@pytest.mark.parametrize("path", [ENV_FILE, PROPS_FILE], ids=["env", "gradle"])
def test_files_say_public_not_secret_and_carry_no_secret_keys(path: Path) -> None:
    text = path.read_text()
    assert "NOT secrets" in text
    assert "docs/auth0.md" in text
    lowered = "\n".join(line for line in text.lower().splitlines() if not line.startswith("#"))
    for word in ("secret", "password", "private"):
        assert word not in lowered


@pytest.mark.parametrize("path", [ENV_FILE, PROPS_FILE], ids=["env", "gradle"])
def test_files_are_tracked_not_ignored(path: Path) -> None:
    if shutil.which("git") is None:
        pytest.skip("needs git")
    r = subprocess.run(  # noqa: S603
        ["git", "-C", str(REPO), "check-ignore", "-q", str(path)],  # noqa: S607
        check=False,
    )
    assert r.returncode == 1, f"{path} is git-ignored"


# ------------------------------------------------------------------ build.sh dry runs


def _build_dry_run(tmp_path: Path, *args: str) -> subprocess.CompletedProcess[str]:
    """build.sh --dry-run against a throwaway clean git repository (nothing is built)."""
    repo = tmp_path / "repo"
    repo.mkdir()
    git = ["git", "-C", str(repo), "-c", "user.name=t", "-c", "user.email=t@example.invalid"]
    subprocess.run(["git", "init", "-q", str(repo)], check=True)  # noqa: S603, S607
    (repo / "README").write_text("x\n")
    subprocess.run([*git, "add", "README"], check=True)  # noqa: S603
    subprocess.run([*git, "commit", "-q", "-m", "init"], check=True)  # noqa: S603
    vf = tmp_path / "version.env"
    vf.write_text("WINGMAN_RELEASE_VERSION=0.1.0-beta.9\nWINGMAN_RELEASE_VERSION_CODE=9\n")
    env = {
        **os.environ,
        "WINGMAN_RELEASE_REPO": str(repo),
        "WINGMAN_RELEASE_VERSION_FILE": str(vf),
    }
    env.pop("WINGMAN_RELEASE_AUTH0_PROPS", None)
    return subprocess.run(  # noqa: S603
        ["bash", str(BUILD), "--dry-run", "--skip-image", *args],  # noqa: S607
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
        check=False,
    )


def _gradle_line(stdout: str) -> str:
    return next(line for line in stdout.splitlines() if "./gradlew" in line)


@needs_bash
def test_release_dry_run_passes_the_beta_auth0_flags(tmp_path: Path) -> None:
    r = _build_dry_run(tmp_path)
    assert r.returncode == 0, r.stderr
    gradle = _gradle_line(r.stdout)
    assert gradle.endswith(" ".join(OIDC_FLAGS) + ")")
    assert "-Pwingman.useMockApi=false" in gradle
    assert f"sign-in:   Auth0 https://{TENANT}/" in r.stdout


@needs_bash
def test_release_command_line_override_wins(tmp_path: Path) -> None:
    r = _build_dry_run(tmp_path, "-Pwingman.oidc.clientId=OtherClient123")
    assert r.returncode == 0, r.stderr
    gradle = _gradle_line(r.stdout)
    assert "-Pwingman.oidc.clientId=OtherClient123" in gradle
    assert CLIENT_ID not in gradle
    assert gradle.count("-Pwingman.oidc.clientId=") == 1
    assert f"-Pwingman.oidc.issuer=https://{TENANT}/" in gradle


@needs_bash
def test_release_rejects_unknown_or_bad_oidc_values(tmp_path: Path) -> None:
    r = _build_dry_run(tmp_path, "-Pwingman.oidc.clientSecret=x")
    assert r.returncode == 2
    assert "unknown Auth0 property" in r.stderr


@needs_bash
def test_debug_dry_run_without_auth0_has_no_oidc_flags(tmp_path: Path) -> None:
    r = _build_dry_run(tmp_path, "--no-auth0")
    assert r.returncode == 0, r.stderr
    gradle = _gradle_line(r.stdout)
    assert ":app:assembleDebug" in gradle
    assert "wingman.oidc" not in gradle
    assert "dev-token panel only" in r.stdout


def test_dev_ci_and_e2e_builds_do_not_read_the_beta_config() -> None:
    """Debug/dev builds (plain Gradle, android-build.sh, dev-run.sh) and CI/E2E stay unchanged."""
    paths = [
        REPO / "scripts" / "android-build.sh",
        REPO / "scripts" / "dev-run.sh",
        REPO / "docker-compose.yml",
        REPO / "android" / "gradle.properties",
        REPO / "android" / "app" / "build.gradle.kts",
        *sorted((REPO / ".github" / "workflows").glob("*.yml")),
        *sorted((REPO / "scripts" / "e2e").rglob("*.sh")),
    ]
    for path in paths:
        if not path.exists():
            continue
        text = path.read_text()
        assert "config/beta" not in text, path
        assert TENANT not in text, path
        assert CLIENT_ID not in text, path


@needs_bash
def test_configure_script_beta_prints_the_committed_values() -> None:
    r = subprocess.run(  # noqa: S603
        ["bash", str(CONFIGURE), "--beta"],  # noqa: S607
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    assert r.returncode == 0, r.stderr
    lines = r.stdout.splitlines()
    for key, value in parse(ENV_FILE).items():
        assert f"{key}={value}" in lines
    gradle = next(line for line in lines if line.startswith("cd android && ./gradlew"))
    assert gradle.endswith(" ".join(OIDC_FLAGS))


@needs_bash
@pytest.mark.parametrize(
    ("content", "message"),
    [
        pytest.param(
            f"wingman.oidc.issuer=https://{TENANT}\nwingman.oidc.clientId=a\n"
            "wingman.oidc.audience=https://api.wingman.app\n",
            "trailing slash",
            id="issuer-no-slash",
        ),
        pytest.param("WINGMAN_AUTH_ISSUER=https://x/\n", "unexpected line", id="wrong-format"),
        pytest.param("$(false)\n", "unexpected line", id="not-code"),
    ],
)
def test_configure_script_from_rejects_bad_files(
    tmp_path: Path, content: str, message: str
) -> None:
    f = tmp_path / "auth0.gradle.properties"
    f.write_text(content)
    r = subprocess.run(  # noqa: S603
        ["bash", str(CONFIGURE), "--from", str(f)],  # noqa: S607
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    assert r.returncode == 2
    assert message in r.stderr
