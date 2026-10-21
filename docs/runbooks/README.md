# Runbooks

One page per alert or operational procedure. Each alert in `infra/monitoring` links here through its
`runbook_url`. Every page has the same shape: **What it means**, **Impact**, **Diagnose**, **Fix**,
**Verify**, **Escalate**.

Conventions used below:
- Namespace `pifa`; deployments `pifa-api` (HTTP, `PIFA_JOBS_ENABLED=false`) and `pifa-worker`
  (SFTP polling, mail outbox, nightly jobs, `PIFA_JOBS_ENABLED=true`).
- The management port 9091 (`/actuator/health/readiness`, `/actuator/prometheus`) is reachable only
  inside the cluster: `kubectl -n pifa port-forward deploy/pifa-api 9091`.
- Admin UI pages: **Admin, Overview** (system status), **Admin, Audit**, **Admin, Cycles**.
- Every API response carries `X-Request-Id`; every log line carries `requestId`. Ask users for it.

| Alert / procedure | Page |
|---|---|
| PifaIngestionRejectedSpike | [rejected-file.md](rejected-file.md) |
| PifaSftpNoSuccessfulPoll, PifaWorkerDown | [sftp-outage.md](sftp-outage.md) |
| PifaApiHighErrorRate, PifaApiPodsUnavailable, PifaApiDown | [api-errors.md](api-errors.md) |
| PifaApiHighLatency | [latency.md](latency.md) |
| PifaMailOutboxGrowing | [mail-outbox.md](mail-outbox.md) |
| PifaCycleStageOverdue | [stage-overdue.md](stage-overdue.md) |
| PifaAuditChainBroken, PifaAuditChainUnverified | [audit-chain-broken.md](audit-chain-broken.md) |
| PifaPodRestarting | [pod-restarts.md](pod-restarts.md) |
| PifaDbPoolExhausted | [db-pool.md](db-pool.md) |
| PifaCertificateExpiringSoon, PifaCertificateExpiryCritical | [certificate-expiry.md](certificate-expiry.md) |
| Reprocess a file | [reprocess-file.md](reprocess-file.md) |
| Restore a dataset version | [restore-version.md](restore-version.md) |
| Reopen a finalized cycle | [reopen-cycle.md](reopen-cycle.md) |
| Keycloak outage | [keycloak-outage.md](keycloak-outage.md) |
| Database restore (DR) | [db-restore.md](db-restore.md) |
| Secret / key rotation | [key-rotation.md](key-rotation.md) |
