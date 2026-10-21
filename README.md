# PIFA — Printer Inventory Forecasting & Regional Allocation

Docs: [PRD](docs/PRD.md) · [Architecture](docs/architecture.md) · [Phases](docs/Phase.md) · [ADRs](docs/adr)

## Status
Phase 0 (foundations) and most of Phase 1 (data foundation) are built. See "Phase 1 scope" below for what is and is not done.

## Layout
```
backend/   Spring Boot app (Maven): API, ingestion, versioning, audit
rules/     pifa-rules: Drools ruleset (DRL + RulesFacade) with tests
frontend/  React + TypeScript (Vite)
infra/     docker-compose, Keycloak realm as code, SFTP helper script
templates/ Excel data-contract templates
```

## Local development
```bash
docker compose -f infra/docker-compose.yml up -d     # postgres, keycloak(:8081), minio, sftp(:2222), clamav, mailpit(:8025)
mvn -f backend/pom.xml verify                         # needs JDK 21 and the Postgres above
mvn -f backend/pom.xml -pl app spring-boot:run        # API on :8080
cd frontend && npm install && npm run dev             # SPA on :5173 (set VITE_API_TARGET if the API is not on :8080)
```
Dev users (realm `pifa-dev`, password `dev-password`): `fiona.forecaster`, `anita.north`, `evan.east`, `wendy.west`, `sam.south`, `pat.planner`, `alex.admin`. Dev-only credentials.

### Tests that need extra services
`mvn verify` always runs the rules tests and the database tests (set `PIFA_DB_URL`, `PIFA_DB_USER`, `PIFA_DB_PASSWORD`
if Postgres is not on localhost). Two tests skip themselves unless an environment variable is set:

| Test | Needs | Variables |
|---|---|---|
| `SftpPollerTest` | the compose `sftp` service | `PIFA_TEST_SFTP_HOST`, `PIFA_TEST_SFTP_PORT` |
| `ClamAvScannerTest` | the compose `clamav` service | `PIFA_TEST_CLAMAV_HOST` |

### Trying the ingestion flow
1. Sign in as `alex.admin`, open the Admin dashboard, click **Generate next month** (synthetic forecast + inventory).
2. Sign in as `fiona.forecaster`: upload, compare, activate and delete forecast versions.
3. Sign in as `pat.planner`: the same for inventory.
4. To exercise SFTP, set `PIFA_SFTP_ENABLED=true` and drop an `.xlsx` into `/inbound/forecast` as `sftp-forecast` (password `forecast-dev`, port 2222). Upload as `*.part` and rename when complete.

## Phase 1 scope
Done: schema and migrations, product master with aliases, Drools ingestion rules, Excel reader with file-safety
checks, versioned ingestion (UI, SFTP, synthetic), duplicate detection, download (original / xlsx / csv),
compare, activate, soft delete / undelete / purge with safeguards, hash-chained audit log, error reports (UI download
and SFTP `/outbound/errors`), synthetic generator and negative test pack, ClamAV client, the three dashboard panels.

Not done / differs from the plan (tracked for follow-up):
- Original files are stored on **local disk**, not S3/MinIO (`FileStore` interface is ready for it).
- Excel is read with the standard POI model, not the streaming SAX reader; fine for the 10 MB / 50k-row limits, not tuned for them.
- Uploads are always processed synchronously (no async job for large files).
- The poller uses a Postgres advisory lock instead of ShedLock, and a plain JSch client instead of Spring Integration.
- No nightly audit-chain verification job yet; the test only checks linkage.
- No "worker killed mid-ingestion" test; atomicity relies on the single-transaction design.
- Version delete/undelete reasons are collected with a browser prompt, not a dialog.
