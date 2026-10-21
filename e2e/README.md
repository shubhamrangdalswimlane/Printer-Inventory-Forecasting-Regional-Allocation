# End-to-end tests (Playwright)

`tests/full-cycle.spec.ts` drives one complete monthly cycle through the real UI with all roles, each logging in
through Keycloak: synthetic upload → publish → revise/submit ×4 → allocate → approve → transfer → finalize ×4 → FINAL.

## Run locally
```bash
docker compose -f infra/docker-compose.yml up -d postgres keycloak   # empty database + realm pifa-dev
mvn -f backend/pom.xml -pl app spring-boot:run                       # API on :8080
cd frontend && npm run dev                                           # UI on :5173
cd e2e && npm ci && npx playwright install chromium && npx playwright test
```
Use an empty database: the test creates "next month" with the synthetic generator and expects to be the only cycle.
Set `E2E_BASE_URL` if the UI is not on `http://localhost:5173`. When the tests run in a container next to the app containers (as in CI), set `E2E_PROXY_TARGET=web:4173` instead: the browser then uses `http://localhost:4173` through `scripts/localhost-proxy.mjs`, because the PKCE login only works on HTTPS or localhost. A failing run keeps a trace in `test-results/`
(`npx playwright show-trace <file>`).
