"""``python -m app.worker``: run the periodic WING-011 jobs until SIGTERM/SIGINT.

``python -m app.worker --once`` runs one tick of every job and exits (cron, debugging).
"""

import argparse
import asyncio
import logging
import signal
import sys

from app.config import get_settings
from app.db.session import dispose_engine, get_engine, get_session_factory
from app.worker.config import WorkerSettings
from app.worker.health import start_health_server
from app.worker.runner import Worker, build_jobs

logger = logging.getLogger("app.worker")


def _configure_logging() -> None:
    level = get_settings().log_level.upper()
    logging.basicConfig(
        level=getattr(logging, level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    # SQL echo would print bound parameters (message text); keep it off.
    logging.getLogger("sqlalchemy.engine").setLevel(logging.WARNING)


async def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.worker")
    parser.add_argument("--once", action="store_true", help="run one tick of every job, then exit")
    args = parser.parse_args(argv)

    settings = WorkerSettings()
    worker = Worker(
        build_jobs(settings, get_session_factory()),
        engine=get_engine(),
        advisory_locks=settings.advisory_locks,
        backoff_max_s=settings.backoff_max_s,
    )
    try:
        if args.once:
            await worker.run_once()
            return 0
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, worker.request_stop)
        server = None
        if settings.health_port is not None:
            server = await start_health_server(
                worker, settings.health_host, settings.health_port, settings.stale_after_s
            )
        try:
            await worker.run()
        finally:
            if server is not None:
                server.close()
                await server.wait_closed()
        return 0
    finally:
        await dispose_engine()


def run() -> None:  # pragma: no cover - process entry point (covered by a subprocess test)
    _configure_logging()
    sys.exit(asyncio.run(main()))


if __name__ == "__main__":  # pragma: no cover
    run()
