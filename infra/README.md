# Infrastructure

Owner: WING-023. Full guide: [`docs/deployment.md`](../docs/deployment.md).

| Path | What | Status |
|---|---|---|
| `azure/deploy.sh` | az CLI reference deployment to Azure Container Apps: ACR, Key Vault, managed identity, PostgreSQL Flexible Server 16, a migration job, the API app with `/health` probes, and the background worker app (I-015: no ingress, 1 replica, probes on the worker's own health port) | **UNVERIFIED sample**: never run against Azure |
| `azure/sample.env` | Names and non-secret settings for `deploy.sh`. Copy it outside the repo before editing | sample |

Local services are in the root `docker-compose.yml`. The backend image is `backend/Dockerfile`.
No Kubernetes, by design (WING-023 constraint).
