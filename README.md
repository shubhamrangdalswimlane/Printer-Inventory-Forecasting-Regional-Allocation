# PIFA — Printer Inventory Forecasting & Regional Allocation

Docs: [PRD](docs/PRD.md) · [Architecture](docs/architecture.md) · [Phases](docs/Phase.md) · [ADRs](docs/adr)

## Layout
```
backend/   Spring Boot app (api + worker profiles) — Maven
rules/     pifa-rules: Drools ruleset + tests
frontend/  React + TypeScript (Vite)
infra/     docker-compose, Keycloak realm as code
templates/ Excel data-contract templates
```

## Local development
```bash
docker compose -f infra/docker-compose.yml up -d     # postgres, keycloak(:8081), minio, sftp(:2222), clamav, mailpit(:8025)
mvn -f backend/pom.xml verify                         # needs JDK 21
mvn -f backend/pom.xml -pl app spring-boot:run        # API on :8080
cd frontend && npm install && npm run dev             # SPA on :5173
```
Dev users (realm `pifa-dev`, password `dev-password`): `fiona.forecaster`, `anita.north`, `evan.east`, `wendy.west`, `sam.south`, `pat.planner`, `alex.admin`. Dev-only credentials.
