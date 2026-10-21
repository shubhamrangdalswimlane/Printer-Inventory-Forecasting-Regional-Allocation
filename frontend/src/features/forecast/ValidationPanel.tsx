/**
 * ValidationPanel.tsx - the Forecaster's validation panel (PRD FR-FC-05): blocking errors and
 * warnings (for example a forecast far above recent sales) found by the Drools forecast rules.
 */
import { useEffect, useState } from 'react'
import type { Api, ValidationFinding } from '../../api'

/**
 * @param props.api        API client
 * @param props.cycleId    the cycle to validate
 * @param props.refreshKey change to re-run the validation (after edits are saved)
 */
export default function ValidationPanel({ api, cycleId, refreshKey }: { api: Api; cycleId: string; refreshKey: number }) {
  const [items, setItems] = useState<ValidationFinding[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.validation(cycleId).then(setItems).catch((e: Error) => setError(e.message))
  }, [api, cycleId, refreshKey])

  const errors = items?.filter((i) => i.severity === 'ERROR').length ?? 0
  return (
    <section className="card">
      <h3>Validation</h3>
      {error && <p className="error">{error}</p>}
      {items && items.length === 0 && <p className="ok">No findings.</p>}
      {items && items.length > 0 && (
        <>
          <p>{errors} blocking error(s), {items.length - errors} warning(s). Errors must be fixed before publishing.</p>
          <ul className="findings">
            {items.slice(0, 30).map((i, n) => <li key={n} className={i.severity === 'ERROR' ? 'error' : 'warn'}><b>{i.code}</b> {i.message}</li>)}
          </ul>
          {items.length > 30 && <p className="muted">Showing the first 30 of {items.length}.</p>}
        </>
      )}
    </section>
  )
}
