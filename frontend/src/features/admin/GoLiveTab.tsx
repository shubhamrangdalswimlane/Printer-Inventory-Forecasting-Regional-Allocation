/**
 * GoLiveTab.tsx - the Admin's go-live tools (Phase 6, docs/go-live/cutover-runbook.md):
 *  - readiness: every go-live checklist item with PASS / WARN / FAIL / MANUAL;
 *  - matching report: which product names in a real upstream file match the master, with suggestions;
 *  - sales-history import: the one-off load of real monthly sales (all-or-nothing);
 *  - reconciliation: the old process's final allocation compared with PIFA's, for the parallel-run cycle.
 */
import { useCallback, useEffect, useState } from 'react'
import type { Api, MatchingReport, Readiness, Reconciliation, SalesImport } from '../../api'
import type { CycleState } from '../cycle/useCycle'

/** CSS class for a status pill: green for PASS, amber for WARN, red for FAIL, grey for MANUAL. */
const pillClass = (s: string) => `pill status-${s}`

/**
 * A file picker styled as a link; calls onFile with the chosen file and resets itself so the same file can be picked again.
 * @param props.label    link text
 * @param props.onFile   called with the chosen file
 * @param props.disabled true to grey it out
 */
function FilePick({ label, onFile, disabled }: { label: string; onFile: (f: File) => void; disabled?: boolean }) {
  return (
    <label className={disabled ? 'link disabled' : 'link'}>{label}
      <input type="file" accept=".xlsx" hidden disabled={disabled} onChange={(e) => {
        const f = e.target.files?.[0]
        if (f) onFile(f)
        e.target.value = ''
      }} />
    </label>
  )
}

/**
 * @param props.api   API client
 * @param props.cycle shared cycle state; the selected cycle is the one reconciled
 */
export default function GoLiveTab({ api, cycle }: { api: Api; cycle: CycleState }) {
  const [ready, setReady] = useState<Readiness | null>(null)
  const [match, setMatch] = useState<MatchingReport | null>(null)
  const [sales, setSales] = useState<SalesImport | null>(null)
  const [recon, setRecon] = useState<Reconciliation | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const loadReadiness = useCallback(() => {
    api.readiness().then(setReady).catch((e: Error) => setError(e.message))
  }, [api])
  useEffect(loadReadiness, [loadReadiness])

  /** Runs one tool call with a busy flag and shared error handling, then refreshes the readiness table. */
  async function run<T>(fn: () => Promise<T>, set: (v: T) => void) {
    setBusy(true)
    setError(null)
    try { set(await fn()); loadReadiness() } catch (e) { setError((e as Error).message) } finally { setBusy(false) }
  }

  const month = cycle.cycles.find((c) => c.id === cycle.selectedId)?.month

  return (
    <>
      {error && <p className="error" role="alert">{error}</p>}

      <section className="card">
        <h3>Readiness {ready && <span className={pillClass(ready.overall)}>{ready.overall}</span>}</h3>
        <p><button onClick={loadReadiness}>Run checks again</button> {ready && <small>Last run {new Date(ready.at).toLocaleString()}</small>}</p>
        {ready && (
          <table>
            <thead><tr><th>#</th><th>Check</th><th>Status</th><th>Details</th></tr></thead>
            <tbody>
              {ready.checks.map((c) => (
                <tr key={c.key}>
                  <td>{c.item || ''}</td>
                  <td>{c.title}</td>
                  <td><span className={pillClass(c.status)}>{c.status}</span></td>
                  <td>{c.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h3>Product matching report</h3>
        <p>Upload a real forecast, inventory, sales or allocation file. Nothing is imported: this only checks the product names.</p>
        <p><FilePick label="Choose file…" disabled={busy} onFile={(f) => run(() => api.matchingReport(f), setMatch)} /></p>
        {match && (
          <>
            <p className={match.ok ? 'ok' : 'error'}>
              {match.fileName}: {match.distinctNames} names, {match.exact} exact, {match.alias} via alias, {match.problems.length} not matched.
            </p>
            {match.problems.length > 0 && (
              <table>
                <thead><tr><th>Name in file</th><th>Problem</th><th>Rows</th><th>Did you mean…</th></tr></thead>
                <tbody>
                  {match.problems.map((p) => (
                    <tr key={p.name}>
                      <td>{p.name}</td>
                      <td>{p.match === 'INACTIVE' ? 'product is inactive' : 'unknown'}</td>
                      <td>{p.rows.join(', ')}{p.occurrences > p.rows.length ? ` … (${p.occurrences})` : ''}</td>
                      <td>{p.suggestions.map((s) => `${s.name} (${Math.round(s.score * 100)}%)`).join('; ') || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </section>

      <section className="card">
        <h3>Sales history import</h3>
        <p>
          One row per product and month, with one column per region. If any row is wrong, nothing is imported.{' '}
          <button className="link" onClick={() => api.goLiveTemplate('sales').catch((e: Error) => setError(e.message))}>Download template</button>
        </p>
        <p><FilePick label="Import sales file…" disabled={busy} onFile={(f) => run(() => api.importSales(f), setSales)} /></p>
        {sales && (sales.problems.length === 0
          ? <p className="ok">Imported {sales.imported} values for {sales.products} products, {sales.firstMonth} to {sales.lastMonth}.</p>
          : <><p className="error">Nothing imported. Fix these rows and try again:</p><ul>{sales.problems.map((p) => <li key={p}>{p}</li>)}</ul></>)}
      </section>

      <section className="card">
        <h3>Parallel-run reconciliation {month && <small>(cycle {month})</small>}</h3>
        <p>
          Upload the old process's final allocation for the selected cycle (sheet "Allocation": Product_name plus one column per region).{' '}
          <button className="link" onClick={() => api.goLiveTemplate('allocation').catch((e: Error) => setError(e.message))}>Download template</button>
        </p>
        <p><FilePick label="Choose file…" disabled={busy || !cycle.selectedId} onFile={(f) => run(() => api.reconcile(cycle.selectedId!, f), setRecon)} /></p>
        {recon && (
          <>
            <p className={recon.ok ? 'ok' : 'error'}>
              Compared with PIFA {recon.pifaStage} numbers: {recon.matching} of {recon.cellsCompared} cells match;
              totals PIFA {recon.pifaTotal} vs file {recon.legacyTotal}.
            </p>
            {recon.onlyInFile.length > 0 && <p>In the file but not in this cycle: {recon.onlyInFile.join(', ')}</p>}
            {recon.onlyInPifa.length > 0 && <p>Allocated by PIFA but missing from the file: {recon.onlyInPifa.join(', ')}</p>}
            {recon.differences.length > 0 && (
              <table>
                <caption>{recon.differenceCount} differing cells{recon.differenceCount > recon.differences.length ? ` (largest ${recon.differences.length} shown)` : ''}</caption>
                <thead><tr><th>Product</th><th>Region</th><th className="num">PIFA</th><th className="num">File</th><th className="num">Difference</th></tr></thead>
                <tbody>
                  {recon.differences.map((d) => (
                    <tr key={d.product + d.region}>
                      <td>{d.product}</td><td>{d.region}</td>
                      <td className="num">{d.pifa}</td><td className="num">{d.legacy}</td><td className="num">{d.diff > 0 ? `+${d.diff}` : d.diff}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </section>
    </>
  )
}
