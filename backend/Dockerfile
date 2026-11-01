# syntax=docker/dockerfile:1
# Wingman backend image (WING-023). Build context: backend/.
#
#   docker build -t wingman-backend backend
#   docker run --rm -p 8000:8000 -e WINGMAN_DATABASE_URL=... wingman-backend
#
# - Two stages: `builder` resolves the locked runtime dependencies with uv; `runtime`
#   copies only the virtualenv plus the Alembic files (no uv, no dev deps, no tests).
# - Runs as an unprivileged user (uid 10001). Code and venv are root-owned, read-only.
# - No secrets are baked in: every setting comes from WINGMAN_* environment variables
#   at run time (see docs/deployment.md). `.dockerignore` keeps `.env` out of the context.
# - The same image runs the API (default CMD) and the migration job
#   (`alembic upgrade head`, see docs/deployment.md).

ARG PYTHON_IMAGE=python:3.12-slim-bookworm
ARG UV_VERSION=0.11.32

FROM ghcr.io/astral-sh/uv:${UV_VERSION} AS uv

# --------------------------------------------------------------------------- builder
FROM ${PYTHON_IMAGE} AS builder
COPY --from=uv /uv /usr/local/bin/uv
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    UV_PROJECT_ENVIRONMENT=/app/.venv
WORKDIR /src
# Dependencies first so this layer is cached until the lockfile changes.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-install-project
# Then the project itself, installed non-editable so the venv is self-contained.
COPY app ./app
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --locked --no-dev --no-editable

# --------------------------------------------------------------------------- runtime
FROM ${PYTHON_IMAGE} AS runtime
RUN groupadd --system --gid 10001 wingman \
    && useradd --system --uid 10001 --gid wingman --no-create-home \
       --home-dir /nonexistent --shell /usr/sbin/nologin wingman
WORKDIR /app
COPY --from=builder /app/.venv /app/.venv
# Alembic needs its config and scripts at run time for the migration job.
COPY alembic.ini ./
COPY alembic ./alembic
ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    WINGMAN_ENV=prod
USER 10001:10001
EXPOSE 8000
# Liveness only: /health does not touch the database. python-slim has no curl.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=4)"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-server-header"]
