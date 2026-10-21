# Pod restarts (PifaPodRestarting)

## What it means
A PIFA pod restarted more than 3 times in 30 minutes.

## Diagnose
1. `kubectl -n pifa get pods`, then `kubectl -n pifa describe pod <pod>`: look at `Last State` (OOMKilled? Error?).
2. `kubectl -n pifa logs <pod> --previous`
3. OOMKilled: the JVM uses 75% of the container limit (`-XX:MaxRAMPercentage=75`) and exits on
   `OutOfMemoryError`. A large upload or allocation may need more memory.
4. Readiness/liveness failures: the readiness probe includes the database; a DB outage makes pods
   not-ready but must not restart them (liveness does not check the DB).
5. Start-up failure: a bad config or secret, a Flyway migration error (`FlywayException` in the log).

## Fix
- Raise `resources.limits.memory` in the Helm values and redeploy.
- Fix the config or secret. For a failed migration: see [db-restore.md](db-restore.md) and contact the developers;
  never edit `flyway_schema_history` by hand without them.

## Verify
The pods are Running and Ready with no new restarts for 30 minutes.

## Escalate
The on-call developer.
