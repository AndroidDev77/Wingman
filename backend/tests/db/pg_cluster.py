"""Throwaway PostgreSQL cluster for tests (no Docker needed).

Used only when ``WINGMAN_TEST_DATABASE_URL`` is not set. Finds ``initdb``/``pg_ctl``
(``WINGMAN_PG_BIN``, ``PATH``, or ``/usr/lib/postgresql/<ver>/bin``), initialises a
cluster in a temp dir, starts it on a free localhost port with ``trust`` auth, and
tears it down afterwards. PostgreSQL refuses to run as root, so under root the server
runs as the ``postgres`` OS user via ``runuser``.
"""

import contextlib
import os
import pwd
import shutil
import socket
import subprocess  # noqa: S404 - runs local postgres binaries only
import tempfile
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path


class ClusterUnavailableError(RuntimeError):
    pass


def find_pg_bin() -> Path | None:
    explicit = os.environ.get("WINGMAN_PG_BIN")
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    which = shutil.which("pg_ctl")
    if which:
        candidates.append(Path(which).parent)
    base = Path("/usr/lib/postgresql")
    if base.is_dir():
        versions = sorted(
            (p for p in base.iterdir() if p.name.isdigit()), key=lambda p: int(p.name), reverse=True
        )
        candidates.extend(v / "bin" for v in versions)
    for c in candidates:
        if (c / "initdb").is_file() and (c / "pg_ctl").is_file():
            return c
    return None


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        port: int = s.getsockname()[1]
        return port


@dataclass
class Cluster:
    bin_dir: Path
    root: Path
    port: int
    run_as: str | None

    @property
    def data_dir(self) -> Path:
        return self.root / "data"

    def _cmd(self, *args: str) -> list[str]:
        cmd = [str(self.bin_dir / args[0]), *args[1:]]
        if self.run_as:
            runuser = shutil.which("runuser")
            if runuser is None:
                raise ClusterUnavailableError("running as root but `runuser` is unavailable")
            return [runuser, "-u", self.run_as, "--", *cmd]
        return cmd

    def _run(self, *args: str) -> None:
        proc = subprocess.run(  # noqa: S603 - fixed local binaries, no shell
            self._cmd(*args), capture_output=True, text=True, check=False
        )
        if proc.returncode != 0:
            output = proc.stderr.strip() or proc.stdout.strip()
            raise ClusterUnavailableError(f"{args[0]} failed ({proc.returncode}): {output}")

    def start(self) -> None:
        self._run(
            "initdb",
            "-D",
            str(self.data_dir),
            "-U",
            "postgres",
            "--auth=trust",
            "--encoding=UTF8",
            "--locale=C",
            "--no-sync",
        )
        opts = (
            f"-c listen_addresses=127.0.0.1 -c port={self.port} "
            f"-c unix_socket_directories={self.root} -c fsync=off "
            "-c synchronous_commit=off -c full_page_writes=off -c timezone=UTC"
        )
        self._run(
            "pg_ctl",
            "-D",
            str(self.data_dir),
            "-l",
            str(self.root / "server.log"),
            "-o",
            opts,
            "-w",
            "-t",
            "60",
            "start",
        )

    def stop(self) -> None:
        with contextlib.suppress(ClusterUnavailableError):
            self._run("pg_ctl", "-D", str(self.data_dir), "-m", "immediate", "-w", "stop")

    @property
    def url(self) -> str:
        return f"postgresql+asyncpg://postgres@127.0.0.1:{self.port}/postgres"


@contextlib.contextmanager
def temporary_cluster() -> Iterator[str]:
    """Yield an asyncpg URL for a fresh cluster; raise ClusterUnavailableError if impossible."""
    bin_dir = find_pg_bin()
    if bin_dir is None:
        raise ClusterUnavailableError(
            "no PostgreSQL server binaries found (set WINGMAN_PG_BIN or WINGMAN_TEST_DATABASE_URL)"
        )
    run_as: str | None = None
    if os.geteuid() == 0:
        run_as = os.environ.get("WINGMAN_PG_OS_USER", "postgres")
        try:
            pw = pwd.getpwnam(run_as)
        except KeyError as exc:
            raise ClusterUnavailableError(
                f"running as root and OS user {run_as!r} missing"
            ) from exc
    root = Path(tempfile.mkdtemp(prefix="wingman-pg-"))
    try:
        if run_as is not None:
            os.chown(root, pw.pw_uid, pw.pw_gid)
            root.chmod(0o700)
        cluster = Cluster(bin_dir=bin_dir, root=root, port=_free_port(), run_as=run_as)
        cluster.start()
        try:
            yield cluster.url
        finally:
            cluster.stop()
    finally:
        shutil.rmtree(root, ignore_errors=True)
