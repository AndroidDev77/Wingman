"""Optional, dependency-free liveness endpoint for the worker (``GET /health``).

Enabled by ``WINGMAN_WORKER_HEALTH_PORT``. Answers ``200`` while every job has made
progress (a finished tick, or a tick skipped because another replica holds the lock)
within ``WINGMAN_WORKER_STALE_AFTER_S``, otherwise ``503``. The body carries job names
and counters only. Any other path or method gets ``404``.
"""

import asyncio
import json
import logging

from app.worker.runner import Worker

__all__ = ["start_health_server"]

logger = logging.getLogger(__name__)


def _response(status: int, body: dict[str, object]) -> bytes:
    payload = json.dumps(body).encode()
    reason = {200: "OK", 404: "Not Found", 503: "Service Unavailable"}[status]
    head = (
        f"HTTP/1.1 {status} {reason}\r\n"
        "Content-Type: application/json\r\n"
        f"Content-Length: {len(payload)}\r\n"
        "Connection: close\r\n\r\n"
    )
    return head.encode() + payload


def health_body(worker: Worker, stale_after_s: float) -> tuple[int, dict[str, object]]:
    stale = worker.stale_jobs(stale_after_s)
    status = 503 if stale or worker.stopping else 200
    return status, {
        "status": "ok" if status == 200 else "unhealthy",
        "stopping": worker.stopping,
        "stale_jobs": stale,
        "jobs": {
            name: {"ticks": s.ticks, "skipped": s.skipped, "errors": s.errors}
            for name, s in worker.state.items()
        },
    }


async def start_health_server(
    worker: Worker, host: str, port: int, stale_after_s: float
) -> asyncio.Server:
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            line = await asyncio.wait_for(reader.readline(), timeout=5)
            parts = line.decode("latin-1").split()
            if len(parts) >= 2 and parts[0] in ("GET", "HEAD") and parts[1] == "/health":
                status, body = health_body(worker, stale_after_s)
            else:
                status, body = 404, {"status": "not_found"}
            writer.write(_response(status, body))
            await writer.drain()
        except Exception as exc:
            logger.debug("health request failed error_class=%s", type(exc).__name__)
        finally:
            writer.close()

    server = await asyncio.start_server(handle, host, port)
    bound = server.sockets[0].getsockname()[1] if server.sockets else port
    logger.info("worker health endpoint listening port=%d", bound)
    return server
