# API 5xx error rate (PifaApiHighErrorRate, PifaApiPodsUnavailable, PifaApiDown)

## What it means
More than 2% of API requests returned HTTP 5xx over 5 minutes (`http_server_requests_seconds_count{status=~"5.."}`).

## Impact
Users see "Something went wrong" messages with a request id.

## Diagnose
1. Which endpoint: in Grafana (PIFA dashboard, panel "Errors by URI"), or
   `sum by (uri) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))`.
2. `kubectl -n pifa logs deploy/pifa-api --since=15m | grep -E '"level":"ERROR"'`, then follow the `requestId`.
3. Check the dependencies: database (`/actuator/health/readiness`), S3, ClamAV, Keycloak.
4. Did a deployment just happen? `kubectl -n pifa rollout history deploy/pifa-api`.

## Fix
- Bad release: `kubectl -n pifa rollout undo deploy/pifa-api` (Flyway migrations are forward-only. A
  rollback is safe only if the release had no new migration. Otherwise fix forward.)
- Database down or saturated: see [db-pool.md](db-pool.md) and [db-restore.md](db-restore.md).
- Storage (S3) errors: check the bucket policy and the credentials secret.

## Verify
The error rate is back under 1% and the alert resolves.

## Escalate
The on-call developer, with request ids and log excerpts.
