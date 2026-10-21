/**
 * full-cycle.spec.ts - Phase 4 exit criterion: one full monthly cycle through the UI with all roles:
 * upload (synthetic) -> publish -> revise/submit x4 -> allocate -> approve -> transfer -> finalize x4 -> FINAL.
 *
 * Every user logs in through Keycloak in their own browser context (separate cookies), exactly like real people.
 */
import { expect, test, type Browser, type Page } from '@playwright/test'

const PASSWORD = process.env.E2E_PASSWORD ?? 'dev-password' // dev realm test users only

/** Opens a fresh browser context, signs the user in through Keycloak and returns the page. */
async function login(browser: Browser, username: string): Promise<Page> {
  const ctx = await browser.newContext()
  const page = await ctx.newPage()
  // Confirm and prompt dialogs: accept, answering prompts with a fixed text.
  page.on('dialog', (d) => d.accept(d.type() === 'prompt' ? 'e2e' : undefined))
  await page.goto('/')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(PASSWORD)
  await page.locator('#kc-login').click()
  await expect(page.getByText('Signed in as')).toBeVisible()
  return page
}

/** Waits until the cycle banner shows the given state label. */
async function expectState(page: Page, label: string) {
  await expect(page.locator('.banner .pill').first()).toHaveText(label, { timeout: 30_000 })
}

test('a full monthly cycle across all roles', async ({ browser }) => {
  // 1. Admin generates a synthetic forecast and inventory (same path as an SFTP drop).
  const admin = await login(browser, 'alex.admin')
  await admin.getByRole('button', { name: 'Data', exact: true }).click()
  await admin.getByRole('button', { name: 'Generate next month' }).click()
  await expect(admin.getByText(/Generated: FC-.*-v1, INV-.*-v1/)).toBeVisible()
  await admin.context().close()

  // 2. Forecaster approves and publishes.
  const forecaster = await login(browser, 'fiona.forecaster')
  await expectState(forecaster, 'Forecast draft')
  await forecaster.getByPlaceholder('Comment').fill('E2E publish')
  await forecaster.getByRole('button', { name: 'Approve & publish' }).click()
  await expect(forecaster.getByText('Published. Regional managers have been notified.')).toBeVisible()
  await expectState(forecaster, 'Regional review')
  await forecaster.context().close()

  // 3. Each Regional Manager submits their region (North also revises one cell first).
  for (const [user, region] of [['anita.north', 'NORTH'], ['evan.east', 'EAST'], ['wendy.west', 'WEST'], ['sam.south', 'SOUTH']] as const) {
    const rm = await login(browser, user)
    await expectState(rm, 'Regional review')
    if (region === 'NORTH') {
      const cell = rm.locator('section.card', { hasText: 'Forecast (revise your region)' }).locator('input.cellinput').first()
      await cell.fill(String(Number(await cell.inputValue()) + 1)) // +1: within the 20% threshold, no reason needed
      await rm.getByRole('button', { name: /Save 1 change/ }).click()
      await expect(rm.getByText('Saved 1 change(s).')).toBeVisible()
    }
    await rm.getByRole('button', { name: `Submit ${region}` }).click()
    await expect(rm.getByText(`${region} submitted.`)).toBeVisible()
    await rm.context().close()
  }

  // 4. Planner runs the Drools allocation and approves it.
  const planner = await login(browser, 'pat.planner')
  await expectState(planner, 'Planning')
  await planner.getByRole('button', { name: 'Run allocation' }).click()
  await expect(planner.getByText(/Allocated \d+ products/)).toBeVisible()
  await planner.getByPlaceholder('Approval comment').fill('E2E approve')
  await planner.getByRole('button', { name: 'Approve allocation' }).click()
  await expect(planner.getByText('Approved. Regional Managers can now finalize.')).toBeVisible()
  await expectState(planner, 'Regional finalization')
  await planner.context().close()

  // 5. North requests 1 unit of the first product from East ...
  const north = await login(browser, 'anita.north')
  await expectState(north, 'Regional finalization')
  const form = north.locator('.transfer-form')
  await form.locator('select').nth(0).selectOption({ index: 1 })
  await form.locator('select').nth(1).selectOption('EAST')
  await form.locator('select').nth(2).selectOption('NORTH')
  await form.locator('input[type=number]').fill('1')
  await form.getByRole('button', { name: 'Request' }).click()
  await expect(north.getByText(/Sent as request/)).toBeVisible()

  // ... and East accepts it.
  const east = await login(browser, 'evan.east')
  await east.locator('section.card', { hasText: 'Waiting for your decision' }).getByRole('button', { name: 'Accept' }).click()
  await expect(east.getByText(/^Accepted\./)).toBeVisible()

  // 6. All four regions finalize; the last one makes the cycle FINAL.
  await east.getByRole('button', { name: 'Finalize EAST' }).click()
  await expect(east.getByText('EAST finalized.')).toBeVisible()
  await east.context().close()
  // Tokens live in memory only (frontend/src/auth.ts), so a reload shows "Sign in" again; the Keycloak SSO
  // session is still valid, so signing in returns straight to the dashboard without the login form.
  await north.reload()
  await north.getByRole('button', { name: 'Sign in' }).click()
  await expect(north.getByText('Signed in as')).toBeVisible()
  await north.getByRole('button', { name: 'Finalize NORTH' }).click()
  await expect(north.getByText('NORTH finalized.')).toBeVisible()
  await north.context().close()
  for (const [user, region] of [['wendy.west', 'WEST'], ['sam.south', 'SOUTH']] as const) {
    const rm = await login(browser, user)
    await rm.getByRole('button', { name: `Finalize ${region}` }).click()
    await expect(rm.getByText(`${region} finalized.`)).toBeVisible()
    if (region === 'SOUTH') await expectState(rm, 'Final')
    await rm.context().close()
  }

  // 7. Admin sees a FINAL cycle and the transfer in the analytics.
  const admin2 = await login(browser, 'alex.admin')
  await expectState(admin2, 'Final')
  await admin2.getByRole('button', { name: 'Analytics' }).click()
  await expect(admin2.locator('table', { hasText: 'From' }).getByText('NORTH')).toBeVisible()
  await admin2.context().close()
})
