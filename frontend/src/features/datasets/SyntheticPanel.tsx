/**
 * SyntheticPanel.tsx - Admin controls for synthetic data (PRD FR-AD-10, non-production only).
 * "Generate" drops a synthetic forecast and inventory through the real pipeline; "Purge" removes all of it.
 */
import { useState } from 'react'

/**
 * @param props.token     access token (this panel calls two admin endpoints directly)
 * @param props.onChanged called after generate/purge so the version lists refresh
 */
export default function SyntheticPanel({ token, onChanged }: { token: string; onChanged: () => void }) {
  const [message, setMessage] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  /**
   * Calls an admin synthetic endpoint and reports the outcome.
   * @param path   endpoint below /api/v1/admin/synthetic
   * @param verify text shown on success
   */
  async function call(path: string, verify: (body: unknown) => string) {
    setBusy(true); setMessage(null)
    try {
      const r = await fetch(`/api/v1/admin/synthetic${path}`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } })
      const body = await r.json()
      setMessage(r.ok ? verify(body) : (body.detail ?? `Failed: ${r.status}`))
    } catch (e) {
      setMessage((e as Error).message)
    } finally {
      setBusy(false); onChanged()
    }
  }

  return (
    <section className="card">
      <h3>Synthetic data (non-production)</h3>
      <button disabled={busy} onClick={() => call('/generate', (b) => `Generated: ${(b as { label: string | null; status: string }[]).map((x) => x.label ?? x.status).join(', ')}`)}>
        Generate next month
      </button>{' '}
      <button disabled={busy} onClick={() => window.confirm('Remove ALL synthetic data?') && call('/purge', (b) => `Removed ${(b as { versionsRemoved: number }).versionsRemoved} versions`)}>
        Purge all synthetic data
      </button>
      {message && <p>{message}</p>}
    </section>
  )
}
