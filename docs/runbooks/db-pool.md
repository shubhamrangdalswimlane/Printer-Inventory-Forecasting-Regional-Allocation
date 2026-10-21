# Database connection pool exhausted (PifaDbPoolExhausted)

## What it means
`hikaricp_connections_pending` > 0 for 5 minutes: requests are waiting for a database connection.

## Diagnose
1. `SELECT state, count(*) FROM pg_stat_activity WHERE datname = 'pifa' GROUP BY state;`
2. Long transactions: `SELECT pid, now() - xact_start AS age, state, left(query, 120) FROM pg_stat_activity WHERE datname = 'pifa' ORDER BY age DESC NULLS LAST LIMIT 10;`
3. `idle in transaction` for a long time points to a stuck request; follow it in the logs.
4. Total connections: replicas x pool size (default 10) must stay below the RDS `max_connections`.
   Each pod also holds one extra connection for live events (`LISTEN`).

## Fix
- Cancel a stuck query: `SELECT pg_cancel_backend(<pid>);` (or `pg_terminate_backend` if cancel does not help).
- Raise `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` carefully, or scale the database.

## Verify
`hikaricp_connections_pending` is back to 0.

## Escalate
DBA.
