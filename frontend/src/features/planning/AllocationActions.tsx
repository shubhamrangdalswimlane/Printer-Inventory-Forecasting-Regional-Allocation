/**
 * AllocationActions.tsx - the Planner's allocation controls (PRD FR-PL-03..05, FR-PL-09, FR-PL-10):
 * preview what a (re-)run would change, run the Drools allocation, see the validation findings, approve
 * (with a comment and a finalization deadline), and export.
 */
import { useEffect, useState } from 'react'
import type { AllocBasis, AllocFinding, Api, Perms, Proposal } from '../../api'

/**
 * @param props.api        API client
 * @param props.cycleId    the cycle
 * @param props.perms      the caller's permissions
 * @param props.refreshKey change to reload the findings
 * @param props.onDone     called after a run or approval so the page reloads
 */
export default function AllocationActions({ api, cycleId, perms, refreshKey, onDone }: { api: Api; cycleId: string; perms: Perms | null; refreshKey: number; onDone: () => void }) {
  const [preview, setPreview] = useState<Proposal | null>(null)
  const [basis, setBasis] = useState<AllocBasis | ''>('')
  const [findings, setFindings] = useState<AllocFinding[]>([])
  const [comment, setComment] = useState('')
  const [deadline, setDeadline] = useState('')
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const [busy, setBusy] = useState(false)
  const planning = perms?.state === 'PLANNING'

  useEffect(() => {
    if (!perms || !['PLANNING', 'REGIONAL_FINALIZATION', 'FINAL'].includes(perms.state)) return
    api.allocationViolations(cycleId, perms.state === 'PLANNING' ? 'APPROVED' : 'FINAL').then(setFindings).catch(() => setFindings([]))
  }, [api, cycleId, perms, refreshKey])

  /** Runs an async action with busy flag and message. */
  async function act(fn: () => Promise<string>) {
    setBusy(true); setMessage(null)
    try { setMessage({ kind: 'ok', text: await fn() }) } catch (e) { setMessage({ kind: 'error', text: (e as Error).message }) } finally { setBusy(false) }
  }

  if (!perms || !['PLANNING', 'REGIONAL_FINALIZATION', 'FINAL'].includes(perms.state)) return null
  const errors = findings.filter((f) => f.severity === 'ERROR')
  return (
    <section className="card">
      <h3>Allocation</h3>
      {planning && perms.canRunAllocation && (
        <p>
          <label>If stock is short, assign{' '}
            <select value={basis} onChange={(e) => setBasis(e.target.value as AllocBasis | '')}>
              <option value="">using the rule parameters (default)</option>
              <option value="FIXED">equally by region weights (25% each)</option>
              <option value="FORECAST_SHARE">as per each region's forecast</option>
              <option value="SALES_SHARE">as per each region's sales history</option>
            </select>
          </label>{' '}
          <small className="muted">Products with enough stock always get their full forecast.</small>
        </p>
      )}
      {planning && perms.canRunAllocation && (
        <p>
          <button disabled={busy} onClick={() => act(async () => { const p = await api.previewAllocation(cycleId, basis || undefined); setPreview(p); return `Preview: ${p.changedCells} cell(s) would change.` })}>Preview run</button>
          <button disabled={busy} onClick={() => window.confirm('Run the allocation? It replaces the current approved numbers, including your edits.') &&
            act(async () => { const p = await api.runAllocation(cycleId, basis || undefined); setPreview(null); onDone(); return `Allocated ${p.products} products (${p.constrained} constrained) with rules ${p.rulesetVersion}, parameters v${p.parameterVersion}.` })}>
            Run allocation
          </button>
        </p>
      )}
      {preview && (
        <div>
          <p>{preview.changedCells} cell(s) would change ({preview.constrained} constrained products).</p>
          {preview.changes.length > 0 && (
            <table><thead><tr><th>Product</th><th>Region</th><th>Now</th><th>After run</th></tr></thead>
              <tbody>{preview.changes.slice(0, 50).map((c, i) => <tr key={i}><td>{c.product}</td><td>{c.region}</td><td>{c.before ?? '—'}</td><td>{c.after}</td></tr>)}</tbody></table>
          )}
          {preview.changes.length > 50 && <p className="muted">First 50 of {preview.changedCells} changes.</p>}
        </div>
      )}
      <h4>Validation ({errors.length} blocking, {findings.length - errors.length} warnings)</h4>
      {findings.length === 0 ? <p className="ok">No findings.</p> : (
        <ul className="findings">{findings.slice(0, 30).map((f, i) => <li key={i} className={f.severity === 'ERROR' ? 'error' : 'warn'}><b>{f.code}</b> {f.message}</li>)}</ul>
      )}
      {planning && perms.canApproveAllocation && (
        <p>
          <input aria-label="Approval comment" placeholder="Approval comment" value={comment} onChange={(e) => setComment(e.target.value)} />{' '}
          <label>Finalize by <input type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} /></label>{' '}
          <button disabled={busy || errors.length > 0} title={errors.length > 0 ? 'Fix the blocking errors first' : ''}
            onClick={() => act(async () => { await api.transition(cycleId, { action: 'APPROVE_ALLOCATION', comment: comment || undefined, finalizeDeadline: deadline ? new Date(deadline).toISOString() : undefined }); onDone(); return 'Approved. Regional Managers can now finalize.' })}>
            Approve allocation
          </button>
        </p>
      )}
      <p className="muted">Export:{' '}
        <button className="link" onClick={() => api.exportAllocation(cycleId, planning ? 'approved' : 'final').catch((e) => setMessage({ kind: 'error', text: e.message }))}>all regions</button>
        {['NORTH', 'EAST', 'WEST', 'SOUTH'].map((r) => <span key={r}> · <button className="link" onClick={() => api.exportAllocation(cycleId, planning ? 'approved' : 'final', r).catch((e) => setMessage({ kind: 'error', text: e.message }))}>{r}</button></span>)}
      </p>
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
    </section>
  )
}
