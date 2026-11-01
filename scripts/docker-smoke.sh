#!/usr/bin/env bash
# Build the backend image and smoke-test it: non-root user, /health answers, and the
# Docker HEALTHCHECK reports healthy. Needs a running Docker daemon.
#
#   scripts/docker-smoke.sh [image-tag]          (default: wingman-backend:local)
#
# Env: DOCKERFILE overrides the Dockerfile path (build context is always backend/);
#      DOCKER_PULL=1 adds --pull to refresh base images (CI sets it).
# /health is liveness only and does not touch Postgres, so no database is started.
set -euo pipefail
cd "$(dirname "$0")/../backend"
tag="${1:-wingman-backend:local}"
name="wingman-smoke-$$"

pull=()
[[ "${DOCKER_PULL:-0}" == "1" ]] && pull=(--pull)
docker build "${pull[@]}" -f "${DOCKERFILE:-Dockerfile}" -t "$tag" .

cleanup() { docker rm -f "$name" >/dev/null 2>&1 || true; }
trap cleanup EXIT

user=$(docker image inspect "$tag" --format '{{.Config.User}}')
if [[ -z "$user" || "$user" == "0" || "$user" == root* ]]; then
  echo "FAIL: image runs as root (User='$user')" >&2
  exit 1
fi
echo "image user: $user"

if docker run --rm --entrypoint sh "$tag" -c 'command -v uv || command -v pytest || test -e /app/.env'; then
  echo "FAIL: image contains uv, pytest or /app/.env" >&2
  exit 1
fi

docker run -d --name "$name" -p 127.0.0.1::8000 "$tag" >/dev/null
port=$(docker port "$name" 8000/tcp | head -n1 | sed 's/.*://')

for _ in $(seq 1 30); do
  if body=$(curl -fsS "http://127.0.0.1:${port}/health" 2>/dev/null); then
    echo "GET /health -> $body"
    break
  fi
  sleep 1
done
if [[ -z "${body:-}" ]]; then
  echo "FAIL: /health did not answer" >&2
  docker logs "$name" >&2 || true
  exit 1
fi

# WINGMAN_ENV=prod is the image default: the unauthenticated demo router must be absent.
demo=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/dev/connectors/mock/capabilities")
echo "GET /dev/connectors/mock/capabilities -> $demo"
if [[ "$demo" != "404" ]]; then
  echo "FAIL: demo connector router is mounted in the default (prod) image" >&2
  exit 1
fi

# The image's own HEALTHCHECK (first probe runs at ~start, then every 30s).
for _ in $(seq 1 45); do
  status=$(docker inspect "$name" --format '{{.State.Health.Status}}')
  [[ "$status" == "healthy" ]] && break
  sleep 1
done
echo "docker HEALTHCHECK: $status"
if [[ "$status" != "healthy" ]]; then
  docker logs "$name" >&2 || true
  exit 1
fi

echo "DOCKER SMOKE TEST PASSED ($tag)"
