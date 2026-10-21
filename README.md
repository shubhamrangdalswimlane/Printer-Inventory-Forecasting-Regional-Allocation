# PIFA — Printer Inventory Forecasting & Regional Allocation

Docs: [PRD](docs/PRD.md) · [Architecture](docs/architecture.md) · [Phases](docs/Phase.md) · [ADRs](docs/adr)

## Status
Phase 0 (foundations), most of Phase 1 (data foundation) and Phase 2 (forecast workflow) are built. See the
"Phase 1 scope" and "Phase 2 scope" sections below for what is and is not done.

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
| `MailOutboxTest` | the compose `mailpit` service | `PIFA_TEST_SMTP_HOST`, `PIFA_TEST_MAILPIT_API` (e.g. `http://localhost:8025`) |

### Trying the ingestion flow
1. Sign in as `alex.admin`, open the Admin dashboard, click **Generate next month** (synthetic forecast + inventory).
2. Sign in as `fiona.forecaster`: upload, compare, activate and delete forecast versions.
3. Sign in as `pat.planner`: the same for inventory.
4. To exercise SFTP, set `PIFA_SFTP_ENABLED=true` and drop an `.xlsx` into `/inbound/forecast` as `sftp-forecast` (password `forecast-dev`, port 2222). Upload as `*.part` and rename when complete.

### Trying the forecast workflow
1. As `alex.admin`: **Generate next month** creates cycle `YYYY-MM` with a forecast and inventory.
2. As `fiona.forecaster`: edit published quantities, check the validation panel, **Approve & publish** (optionally with a review deadline).
3. As `anita.north` (or `evan.east`, `wendy.west`, `sam.south`): study the sales analysis, revise your own region (changes above 20% need a reason), then **Submit**. When all four have submitted the cycle moves to PLANNING.
4. As `pat.planner`: **Return** a region for rework, or **Proceed after deadline** once the deadline has passed.
5. The bell in the header shows in-app notifications; with `PIFA_MAIL_ENABLED=true` they are also e-mailed (to Mailpit in development).

## Phase 2 scope
Done: cycle state machine (publish, recall, submit region, proceed, return regions) with optimistic locking and a
workflow event log; editable working copy with change log; the editability matrix as one `EditPolicy` (tested for
every cell) enforced on every write; Drools forecast and publish-guard rules; immutable snapshots
(`FORECAST_PUBLISHED`, `FORECAST_REVISED` per region and consolidated, `FORECAST_WORKING_SNAPSHOT`) protected by a
database trigger; 409 conflict handling; sales analysis API; in-app notifications and e-mail outbox; Forecaster,
Regional Manager and (minimal) Planner screens.

Not done / differs from the plan:
- The grid is a plain HTML table with server-side paging (50 products per page), not AG Grid with virtual scrolling.
- Admin edit of rule parameters (change threshold, spike factor) is still configuration (`application.yml`); the screen comes in Phase 3.
- E-mail recipients are built from an address template; resolving real users from Keycloak is not done.
- No live refresh (SSE) yet; panels reload after actions and the bell polls every 30 seconds.
- Bulk edit applies a percentage to the published value of selected rows; there is no undo besides "Discard".
- Cycle deadlines can be set at publish only; there is no deadline reminder job.

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
