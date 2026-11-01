#!/usr/bin/env bash
# Reference deployment of the Wingman backend to Azure Container Apps.
#
#   STATUS: UNVERIFIED SAMPLE. This script has never been run against an Azure
#   subscription. It is shellcheck-clean and every command is a documented `az`
#   command, but treat each step as a starting point: run steps one at a time, read
#   the output, and fix names/SKUs for your subscription. See docs/deployment.md.
#
# Usage (after `az login` and loading your copy of infra/azure/sample.env):
#   infra/azure/deploy.sh infra     # RG, ACR, Key Vault, identity, Postgres, ACA environment
#   infra/azure/deploy.sh image     # build + push the image in ACR (no local Docker needed)
#   infra/azure/deploy.sh migrate   # run `alembic upgrade head` as a one-off Container Apps job
#   infra/azure/deploy.sh app       # create/update the API container app (probes on /health)
#   infra/azure/deploy.sh all       # all of the above, in order
#
# Secrets: the Postgres admin password is generated here and written straight to Key
# Vault together with the full WINGMAN_DATABASE_URL; neither is printed. The container
# app and the migration job read them through Key Vault references using a
# user-assigned managed identity. Nothing secret is baked into the image or the repo.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"

require() {
  local missing=()
  for v in "$@"; do [[ -n "${!v:-}" ]] || missing+=("$v"); done
  if ((${#missing[@]})); then
    echo "missing settings: ${missing[*]} (see infra/azure/sample.env)" >&2
    exit 2
  fi
}

require AZ_SUBSCRIPTION AZ_LOCATION AZ_RESOURCE_GROUP AZ_ACR_NAME AZ_KEYVAULT_NAME \
  AZ_PG_SERVER AZ_PG_ADMIN_USER AZ_PG_DATABASE AZ_PG_SKU AZ_PG_TIER AZ_CA_ENV \
  AZ_APP_NAME AZ_MIGRATE_JOB AZ_IDENTITY_NAME WINGMAN_ENV WINGMAN_LLM_PROVIDER \
  WINGMAN_LOG_LEVEL

IMAGE_TAG="${IMAGE_TAG:-$(git -C "$repo_root" rev-parse --short=12 HEAD)}"
IMAGE="${AZ_ACR_NAME}.azurecr.io/wingman-backend:${IMAGE_TAG}"
RG=(--resource-group "$AZ_RESOURCE_GROUP")

log() { printf '\n==> %s\n' "$*"; }

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

identity_id() {
  az identity show "${RG[@]}" --name "$AZ_IDENTITY_NAME" --query id -o tsv
}

kv_secret_uri() {
  # Versionless URI so a rotated secret is picked up on the next revision/restart.
  printf 'https://%s.vault.azure.net/secrets/%s' "$AZ_KEYVAULT_NAME" "$1"
}

kv_has_secret() {
  az keyvault secret show --vault-name "$AZ_KEYVAULT_NAME" --name "$1" \
    --query id -o tsv >/dev/null 2>&1
}

# ------------------------------------------------------------------------------ infra
step_infra() {
  log "resource group"
  az group create --name "$AZ_RESOURCE_GROUP" --location "$AZ_LOCATION" -o none

  log "container registry (admin user disabled; pulls use the managed identity)"
  az acr create "${RG[@]}" --name "$AZ_ACR_NAME" --sku Basic --admin-enabled false -o none

  log "user-assigned managed identity"
  az identity create "${RG[@]}" --name "$AZ_IDENTITY_NAME" -o none
  local principal acr_id kv_id
  principal=$(az identity show "${RG[@]}" --name "$AZ_IDENTITY_NAME" --query principalId -o tsv)

  log "key vault (RBAC mode)"
  az keyvault create "${RG[@]}" --name "$AZ_KEYVAULT_NAME" --location "$AZ_LOCATION" \
    --enable-rbac-authorization true -o none
  kv_id=$(az keyvault show --name "$AZ_KEYVAULT_NAME" --query id -o tsv)
  acr_id=$(az acr show --name "$AZ_ACR_NAME" --query id -o tsv)

  log "role assignments: identity -> AcrPull, Key Vault Secrets User"
  az role assignment create --assignee-object-id "$principal" \
    --assignee-principal-type ServicePrincipal --role AcrPull --scope "$acr_id" -o none
  az role assignment create --assignee-object-id "$principal" \
    --assignee-principal-type ServicePrincipal --role "Key Vault Secrets User" \
    --scope "$kv_id" -o none
  # The operator running this script needs to write secrets.
  local me
  me=$(az ad signed-in-user show --query id -o tsv)
  az role assignment create --assignee-object-id "$me" --assignee-principal-type User \
    --role "Key Vault Secrets Officer" --scope "$kv_id" -o none

  log "PostgreSQL Flexible Server 16"
  if az postgres flexible-server show "${RG[@]}" --name "$AZ_PG_SERVER" -o none 2>/dev/null; then
    echo "server exists; leaving password and settings unchanged"
  else
    local pg_password
    # Upper+lower+digit prefix satisfies Azure complexity; token_urlsafe is URL-safe.
    pg_password=$(python3 -c 'import secrets; print("Wm7" + secrets.token_urlsafe(32))')
    # Public access limited to Azure services (0.0.0.0). For production use VNet
    # integration / private access instead (docs/deployment.md, "Hardening").
    az postgres flexible-server create "${RG[@]}" --name "$AZ_PG_SERVER" \
      --location "$AZ_LOCATION" --version 16 --tier "$AZ_PG_TIER" --sku-name "$AZ_PG_SKU" \
      --storage-size 32 --admin-user "$AZ_PG_ADMIN_USER" --admin-password "$pg_password" \
      --public-access 0.0.0.0 --yes -o none
    az postgres flexible-server db create "${RG[@]}" --server-name "$AZ_PG_SERVER" \
      --database-name "$AZ_PG_DATABASE" -o none

    log "store database URL in Key Vault (not printed)"
    local url="postgresql+asyncpg://${AZ_PG_ADMIN_USER}:${pg_password}@${AZ_PG_SERVER}.postgres.database.azure.com:5432/${AZ_PG_DATABASE}?ssl=require"
    az keyvault secret set --vault-name "$AZ_KEYVAULT_NAME" --name wingman-database-url \
      --value "$url" -o none
    unset pg_password url
  fi

  log "container apps environment (consumption)"
  az containerapp env create "${RG[@]}" --name "$AZ_CA_ENV" --location "$AZ_LOCATION" -o none
}

# ------------------------------------------------------------------------------ image
step_image() {
  log "build $IMAGE in ACR (cloud build; uploads backend/ honoring .dockerignore)"
  az acr build --registry "$AZ_ACR_NAME" --image "wingman-backend:${IMAGE_TAG}" \
    --file "$repo_root/backend/Dockerfile" "$repo_root/backend"
}

# ---------------------------------------------------------------------- shared config
# Secrets section for YAML: the DB URL always; the LLM key only if it exists in Key Vault.
secrets_yaml() {
  local id
  id=$(identity_id)
  cat <<EOF
    - name: database-url
      keyVaultUrl: $(kv_secret_uri wingman-database-url)
      identity: ${id}
EOF
  if kv_has_secret wingman-llm-api-key; then
    cat <<EOF
    - name: llm-api-key
      keyVaultUrl: $(kv_secret_uri wingman-llm-api-key)
      identity: ${id}
EOF
  fi
}

env_yaml() {
  cat <<EOF
        - name: WINGMAN_ENV
          value: "${WINGMAN_ENV}"
        - name: WINGMAN_LLM_PROVIDER
          value: "${WINGMAN_LLM_PROVIDER}"
        - name: WINGMAN_LOG_LEVEL
          value: "${WINGMAN_LOG_LEVEL}"
        - name: WINGMAN_DATABASE_URL
          secretRef: database-url
EOF
  if kv_has_secret wingman-llm-api-key; then
    cat <<EOF
        - name: WINGMAN_LLM_API_KEY
          secretRef: llm-api-key
EOF
  fi
}

env_id() {
  az containerapp env show "${RG[@]}" --name "$AZ_CA_ENV" --query id -o tsv
}

# ---------------------------------------------------------------------------- migrate
step_migrate() {
  local id yaml="$workdir/migrate.yaml"
  id=$(identity_id)
  cat >"$yaml" <<EOF
location: ${AZ_LOCATION}
identity:
  type: UserAssigned
  userAssignedIdentities:
    ${id}: {}
properties:
  environmentId: $(env_id)
  configuration:
    triggerType: Manual
    replicaTimeout: 600
    replicaRetryLimit: 0
    manualTriggerConfig:
      parallelism: 1
      replicaCompletionCount: 1
    registries:
    - server: ${AZ_ACR_NAME}.azurecr.io
      identity: ${id}
    secrets:
$(secrets_yaml)
  template:
    containers:
    - name: migrate
      image: ${IMAGE}
      command: ["alembic"]
      args: ["upgrade", "head"]
      resources:
        cpu: 0.5
        memory: 1Gi
      env:
$(env_yaml)
EOF
  log "migration job $AZ_MIGRATE_JOB -> $IMAGE"
  if az containerapp job show "${RG[@]}" --name "$AZ_MIGRATE_JOB" -o none 2>/dev/null; then
    az containerapp job update "${RG[@]}" --name "$AZ_MIGRATE_JOB" --yaml "$yaml" -o none
  else
    az containerapp job create "${RG[@]}" --name "$AZ_MIGRATE_JOB" --yaml "$yaml" -o none
  fi

  log "start migration and wait (rollout must not proceed unless this succeeds)"
  local execution status=""
  execution=$(az containerapp job start "${RG[@]}" --name "$AZ_MIGRATE_JOB" --query name -o tsv)
  for _ in $(seq 1 60); do
    status=$(az containerapp job execution show "${RG[@]}" --name "$AZ_MIGRATE_JOB" \
      --job-execution-name "$execution" --query properties.status -o tsv)
    case "$status" in
      Succeeded) echo "migration $execution: Succeeded"; return 0 ;;
      Failed | Stopped | Degraded)
        echo "migration $execution: $status. Inspect logs:" >&2
        echo "  az containerapp job logs show -g $AZ_RESOURCE_GROUP -n $AZ_MIGRATE_JOB --execution $execution --container migrate" >&2
        return 1 ;;
    esac
    sleep 10
  done
  echo "migration $execution did not finish in 10 minutes (last status: $status)" >&2
  return 1
}

# -------------------------------------------------------------------------------- app
step_app() {
  local id yaml="$workdir/app.yaml"
  id=$(identity_id)
  cat >"$yaml" <<EOF
location: ${AZ_LOCATION}
identity:
  type: UserAssigned
  userAssignedIdentities:
    ${id}: {}
properties:
  environmentId: $(env_id)
  configuration:
    activeRevisionsMode: Single
    ingress:
      external: true
      targetPort: 8000
      transport: http
      allowInsecure: false
    registries:
    - server: ${AZ_ACR_NAME}.azurecr.io
      identity: ${id}
    secrets:
$(secrets_yaml)
  template:
    scale:
      minReplicas: 1
      maxReplicas: 2
    containers:
    - name: api
      image: ${IMAGE}
      resources:
        cpu: 0.5
        memory: 1Gi
      env:
$(env_yaml)
      # Container Apps ignores the image's Docker HEALTHCHECK; these probes replace it.
      # /health is liveness only (it does not check Postgres).
      probes:
      - type: Startup
        httpGet: { path: /health, port: 8000 }
        initialDelaySeconds: 2
        periodSeconds: 3
        failureThreshold: 20
      - type: Liveness
        httpGet: { path: /health, port: 8000 }
        periodSeconds: 30
        timeoutSeconds: 5
        failureThreshold: 3
      - type: Readiness
        httpGet: { path: /health, port: 8000 }
        periodSeconds: 10
        timeoutSeconds: 5
        failureThreshold: 3
EOF
  log "container app $AZ_APP_NAME -> $IMAGE"
  if az containerapp show "${RG[@]}" --name "$AZ_APP_NAME" -o none 2>/dev/null; then
    az containerapp update "${RG[@]}" --name "$AZ_APP_NAME" --yaml "$yaml" -o none
  else
    az containerapp create "${RG[@]}" --name "$AZ_APP_NAME" --yaml "$yaml" -o none
  fi
  local fqdn
  fqdn=$(az containerapp show "${RG[@]}" --name "$AZ_APP_NAME" \
    --query properties.configuration.ingress.fqdn -o tsv)
  echo "smoke test: curl -fsS https://${fqdn}/health"
}

main() {
  case "${1:-}" in
    infra | image | migrate | app | all) ;;
    *) sed -n '2,20p' "$0"; exit 2 ;;
  esac
  az account set --subscription "$AZ_SUBSCRIPTION"
  case "$1" in
    infra) step_infra ;;
    image) step_image ;;
    migrate) step_migrate ;;
    app) step_app ;;
    all) step_infra; step_image; step_migrate; step_app ;;
  esac
}

main "$@"
