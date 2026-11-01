"""Background worker for the periodic WING-011 jobs (I-015).

``python -m app.worker`` runs, each on its own interval:

* ``dispatch_due``: queued outbound messages whose ``send_after`` has passed (reply
  delays, retry backoff) through ``Dispatcher.dispatch``, one transaction each;
* ``expire_leases``: lapsed device outbox leases -> ``expired`` (ADR 0006);
* ``expire_approvals``: pending approvals past ``expires_at`` -> ``expired``.

Safe with several replicas: per-job advisory locks make one replica do the work, and the
services' own row locks keep it correct even without them. See ``docs/deployment.md``.
"""

from app.worker.config import WorkerSettings
from app.worker.runner import Job, TickResult, Worker, build_jobs

__all__ = ["Job", "TickResult", "Worker", "WorkerSettings", "build_jobs"]
