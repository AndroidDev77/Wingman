"""Worker settings (environment prefix ``WINGMAN_WORKER_``).

The database URL, environment and log level come from :mod:`app.config`
(``WINGMAN_DATABASE_URL``, ``WINGMAN_ENV``, ``WINGMAN_LOG_LEVEL``); only the
scheduling knobs live here.
"""

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class WorkerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WINGMAN_WORKER_", env_file=".env", extra="ignore")

    # How often each job runs (seconds between the end of one tick and the next).
    dispatch_interval_s: float = Field(default=2.0, gt=0, le=3600)
    lease_expiry_interval_s: float = Field(default=15.0, gt=0, le=3600)
    approval_expiry_interval_s: float = Field(default=60.0, gt=0, le=3600)
    # Most due messages one dispatch tick handles (oldest first); the rest wait a tick.
    dispatch_batch: int = Field(default=100, ge=1, le=1000)
    # Dispatch messages on ``mock`` connections too. Off by default: the MockConnector's
    # state is per process (I-016), so a separate worker process has no copy of the
    # threads the API process learned and would dead-letter every mock send. Only the
    # process that owns the mock state (the embedded worker, or a test) turns this on.
    dispatch_mock: bool = False
    # Take a Postgres session-level advisory lock per job and tick, so only one replica
    # runs a given job at a time (the jobs' row locks keep it correct without it).
    advisory_locks: bool = True
    # Bounded exponential backoff after a failed tick: interval * 2**failures, capped.
    backoff_max_s: float = Field(default=60.0, gt=0, le=3600)
    # Tiny HTTP liveness endpoint (GET /health). Unset = disabled; 0 = ephemeral port.
    health_host: str = "0.0.0.0"  # noqa: S104 - container probe target, no app data served
    health_port: int | None = Field(default=None, ge=0, le=65535)
    # /health reports 503 if any job has made no progress (no tick finished) for this long.
    stale_after_s: float = Field(default=300.0, gt=0)
