/**
 * playwright.config.ts - end-to-end tests that drive the real UI through real Keycloak logins.
 *
 * Prerequisites (CI starts them; locally see e2e/README.md): PostgreSQL and Keycloak (infra/docker-compose.yml),
 * the API, and the frontend (Vite dev server or `vite preview`) at E2E_BASE_URL. The database should be empty:
 * the test creates its own cycle with the synthetic-data generator.
 *
 * Container mode: set E2E_PROXY_TARGET (e.g. web:4173) instead of E2E_BASE_URL. The browser then talks to
 * http://localhost:4173 and scripts/localhost-proxy.mjs forwards it to the app container, because the PKCE login
 * needs a secure origin and plain http is only "secure" on localhost.
 */
import { defineConfig, devices } from '@playwright/test'

const proxyTarget = process.env.E2E_PROXY_TARGET
const baseURL = proxyTarget ? 'http://localhost:4173' : process.env.E2E_BASE_URL ?? 'http://localhost:5173'

export default defineConfig({
  testDir: './tests',
  timeout: 10 * 60 * 1000,          // one full monthly cycle with seven logins
  expect: { timeout: 20_000 },
  workers: 1,                        // the steps of a cycle depend on each other
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  // E2E_MAP_LOCALHOST_TO (e.g. host.docker.internal): the browser resolves "localhost" to that host, so a test
  // container can drive an app stack published on the host's localhost ports (app, Keycloak) as a real browser would.
  projects: [{
    name: 'chromium',
    use: { ...devices['Desktop Chrome'], launchOptions: { args: process.env.E2E_MAP_LOCALHOST_TO ? [`--host-resolver-rules=MAP localhost ${process.env.E2E_MAP_LOCALHOST_TO}`] : [] } },
  }],
  // Started before the tests and stopped after them; only in container mode.
  webServer: proxyTarget ? { command: 'node scripts/localhost-proxy.mjs', url: baseURL, reuseExistingServer: false, timeout: 60_000 } : undefined,
})
