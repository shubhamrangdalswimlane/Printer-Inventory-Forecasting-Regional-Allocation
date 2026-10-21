# PIFA monitoring — Grafana dashboards

This folder holds the Grafana dashboards for PIFA (`architecture.md` §14, `Phase.md` Phase 5 "Observability").
The alert rules are not here. They ship with the Helm chart (`infra/helm/pifa/templates/prometheusrule.yaml`), so
alerts and the application version are always deployed together.

| File | Dashboard | For |
|---|---|---|
| `grafana/pifa-api.json` | **PIFA - API** | On-call / SRE: traffic, errors, latency, JVM, DB connection pool |
| `grafana/pifa-business.json` | **PIFA - Business** | Support and product owner: ingestion, workflow, allocation, transfers, outbox, deadlines, audit chain |

JSON has no comments, so each dashboard's purpose is in its `description` field and each panel's meaning is in
the panel's `description`. Grafana shows it as the (i) tooltip on the panel title.

## Prerequisites

- Prometheus scraping PIFA. Install the chart with `monitoring.enabled=true`: its ServiceMonitor scrapes the api and
  worker pods on the internal management port 9091 at `/actuator/prometheus`. Every PIFA series has the
  `application="pifa"` label, and the Prometheus Operator adds `namespace` and `pod`.
- Grafana 10.4 or later (the dashboards use `schemaVersion` 39).

## Import

**Grafana UI:** *Dashboards → New → Import → Upload JSON file*, pick the file, and choose your Prometheus data
source for the `DS_PROMETHEUS` input. The dashboards reference the data source only through `${DS_PROMETHEUS}`, so
they work with any data source name.

**Provisioning as code** (recommended, so dashboards are versioned with the app). With kube-prometheus-stack, put
each file in a ConfigMap labelled `grafana_dashboard: "1"`. The Grafana sidecar loads it automatically:

```bash
kubectl -n observability create configmap pifa-dashboards \
  --from-file=infra/monitoring/grafana/pifa-api.json \
  --from-file=infra/monitoring/grafana/pifa-business.json \
  --dry-run=client -o yaml | kubectl label --local -f - grafana_dashboard=1 -o yaml | kubectl apply -f -
```

With sidecar provisioning, `${DS_PROMETHEUS}` is not filled in by an import dialog. Either set the sidecar's
default data source UID to `DS_PROMETHEUS`, or replace the string with your data source's UID before creating the
ConfigMap, e.g. `sed 's/\${DS_PROMETHEUS}/prometheus/g'`.

Both dashboards have a **Namespace** variable (`pifa-dev`, `pifa`, ...), so one Grafana can show every environment.

## PIFA - API (`pifa-api.json`)

| Panel | Meaning | What to watch |
|---|---|---|
| Request rate | API requests per second over all api pods (`http_server_requests_seconds_count`, actuator excluded) | Month-end peaks; a drop to 0 during working hours means users cannot reach the API |
| 5xx error ratio | Share of requests that ended in a server error over 5 min | Alert `PifaApiHighErrorRate` fires above 1% |
| p95 latency | 95th percentile response time over all endpoints, without the long-lived live-refresh stream `/api/v1/events` | Alert `PifaApiHighLatency` fires above 2 s per endpoint |
| Live connections | Browsers connected for live refresh (`pifa_live_connections`) | Roughly the number of open browser tabs |
| Request rate by status | Requests per second per HTTP status | A rise in 403 can mean a role or region mapping problem in Keycloak |
| Error rate (4xx / 5xx) | Client errors vs server errors | 4xx is mostly validation and edit-policy denials; 5xx is a defect or a dependency outage |
| p95 latency by endpoint | p95 per URI template | Find the slow endpoint (grids, exports, allocation) |
| Top 10 endpoints | Busiest URIs | Capacity planning |
| JVM heap used vs max | Heap per pod against its maximum (75% of the memory limit) | A rising floor after GC means a leak, and OOMKill restarts follow |
| GC pause time | Share of time in GC pauses | Above a few percent: more memory needed |
| Process CPU | JVM CPU per pod | The api HPA scales at 70% of the CPU request |
| Live threads | JVM threads per pod | Steady growth means stuck threads |
| Connections active / idle / max | HikariCP pool use per pod | Active staying at max means the pool is saturated |
| Threads waiting for a connection | `hikaricp_connections_pending` | Alert `PifaDbPoolExhausted` fires when above 0 for 5 min |
| Connection acquire time (mean) | Time to get a connection from the pool | Should be milliseconds |
| Connection timeouts | Requests that gave up waiting for a connection | Each one is an error a user saw |

## PIFA - Business (`pifa-business.json`)

| Panel | Meaning | What to watch |
|---|---|---|
| Audit chain | Last nightly hash-chain verification: OK (1), BROKEN (0), UNKNOWN (-1) | BROKEN is critical (possible tampering), runbook `audit-chain-broken.md` |
| Overdue cycle stages | Open cycles past their review or finalization deadline (`pifa_cycle_stage_overdue`) | Chase the regional managers, or extend the deadline |
| Mail outbox pending | E-mails waiting to be sent (`pifa_outbox_pending`) | Should drain to 0. Alert `PifaMailOutboxGrowing` fires if it keeps growing |
| Files rejected (24 h) | Files that failed validation in the last 24 h | Several of one type means an upstream format change |
| Files by status and channel | Files per hour by channel (SFTP, UI, SYNTHETIC) and outcome (VALID, REJECTED) | Expect forecast and inventory files at their monthly times. SYNTHETIC must be 0 in prod |
| Files by type | Forecast vs inventory files per hour | — |
| Ingestion duration (p95 by type) | Time to validate and store a file | 50k-row files should stay well under a minute |
| SFTP poll errors | Failed SFTP polls per 10 min | About 10 polls run per 10 min. 8 or more with no SFTP file ingested fires `PifaSftpNoSuccessfulPoll` |
| Workflow transitions by action | Cycle state changes per hour (`pifa_transitions_total{action}`) | Shows how the cycle is progressing. Reopen actions are worth a look |
| Allocation run duration | p95 and mean time of allocation proposal runs | Regressions after rule changes |
| Transfers by status | Transfer decisions per hour (REQUESTED, ACCEPTED, REJECTED, CANCELLED) | Many rejections can point to a mis-tuned rule |
| Outbox and overdue stages over time | History of the two gauges above | — |
| Audit chain status over time | History of `pifa_audit_chain_ok` | — |

Database-backed gauges (outbox, overdue, audit chain) are reported by every pod with the same value. The
dashboards therefore use `max()` rather than `sum()`.

## Regenerating

The JSON files are plain Grafana exports. Edit them in Grafana, then use *Export → Export for sharing externally*
(this keeps the `${DS_PROMETHEUS}` input) and commit the result over the file here.
