/**
 * accessibility.spec.ts - WCAG 2.1 AA check (PRD section 13 "Usability & accessibility") with axe-core.
 *
 * Signs in as each role, opens that role's dashboard and scans it. The test fails on violations axe rates
 * "serious" or "critical"; "minor" and "moderate" ones are printed so they can be fixed over time without
 * blocking the pipeline. The Keycloak login page is not scanned: it is Keycloak's own theme, not our UI.
 */
import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Browser } from '@playwright/test'

const PASSWORD = process.env.E2E_PASSWORD ?? 'dev-password' // dev realm test users only

/** Signs in through Keycloak in a fresh context and waits for the dashboard. */
async function login(browser: Browser, username: string) {
  const page = await (await browser.newContext()).newPage()
  await page.goto('/')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(PASSWORD)
  await page.locator('#kc-login').click()
  await expect(page.getByText('Signed in as')).toBeVisible()
  return page
}

for (const user of ['fiona.forecaster', 'anita.north', 'pat.planner', 'alex.admin']) {
  test(`dashboard of ${user} has no serious accessibility violations`, async ({ browser }) => {
    const page = await login(browser, user)
    const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
    for (const v of result.violations) {
      console.log(`[${v.impact}] ${v.id}: ${v.help} (${v.nodes.length} element(s)) e.g. ${v.nodes[0]?.target.join(' ')}`)
    }
    const blocking = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
    expect(blocking.map((v) => `${v.id}: ${v.help}`)).toEqual([])
    await page.context().close()
  })
}
