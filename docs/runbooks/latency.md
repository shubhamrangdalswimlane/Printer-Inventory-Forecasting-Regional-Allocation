# API latency (PifaApiHighLatency)

## What it means
The p95 API latency was above 2 s for 10 minutes (excluding `/events`, which is a long-lived stream).

## Impact
Grids and actions feel slow; the 10k-product allocation can take longer than the 5 s target.

## Diagnose
1. Which URIs are slow (Grafana "Latency p95 by URI").
2. `pifa_allocation_duration_seconds`: allocation runs normally take under 2 s for 10k products.
3. The database: slow queries (`pg_stat_statements`), locks (`pg_locks` joined to `pg_stat_activity`),
   CPU, IOPS credits on RDS.
4. The JVM: heap and GC (`jvm_gc_pause_seconds`), CPU throttling (container limits).
5. Missing table statistics after a bulk import cause bad plans: run `ANALYZE`.

## Fix
- `ANALYZE` the large tables (`plan_line`, `forecast_line`, `inventory_line`, `plan_product_stock`).
- Scale out the API: `kubectl -n pifa scale deploy/pifa-api --replicas=N` (the HPA does this under CPU load).
- Kill a runaway query: `SELECT pg_cancel_backend(pid)` (only after confirming what it is).

## Verify
p95 back under 1 s.

## Escalate
DBA for database-side issues; developers for a slow code path (attach the URI and timings).
