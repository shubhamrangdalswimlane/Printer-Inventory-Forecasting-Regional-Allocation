/**
 * AllocationGrid.tsx - demand versus stock and the allocation per region (PRD FR-PL-02, FR-PL-04, FR-RM-06).
 *
 * Modes:
 *  - "planner":  reserve and approved allocation are editable while the cycle is in PLANNING; live totals
 *                against the allocatable stock; a reason is required when a number differs from the proposal.
 *  - "regional": read-only view of all regions' allocation, with the manager's own region(s) highlighted
 *                and their fill rate (allocation / demand) and shortfall.
 *  - "readonly": the same numbers for Admin / Forecaster.
 * In REGIONAL_FINALIZATION and FINAL the "final" column (after transfers) is shown instead of "approved".
 */
import { useCallback, useEffect, useState } from 'react'
import { ApiError, type AllocCell, type AllocPage, type Api, type EditReq, type Perms } from '../../api'

/** A not-yet-saved change of an approved cell. */
type Pending = { value: number; reason: string; rowVersion: number; proposed: number | null }

/**
 * @param props.api        API client
 * @param props.cycleId    the cycle
 * @param props.mode       "planner", "regional" or "readonly"
 * @param props.perms      the caller's permissions
 * @param props.myRegions  the Regional Manager's regions (regional mode)
 * @param props.refreshKey change to reload
 * @param props.onSaved    called after a successful save
 */
export default function AllocationGrid(props: {
  api: Api; cycleId: string; mode: 'planner' | 'regional' | 'readonly'; perms: Perms | null; myRegions?: string[]; refreshKey: number; onSaved: () => void
  /** Show only this region's column (null/undefined = all regions); Total and the stock check still use all regions. */
  onlyRegion?: string | null
}) {
  const { api, cycleId, mode, perms, myRegions = [], refreshKey, onSaved, onlyRegion } = props
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [onlyConstrained, setOnlyConstrained] = useState(false)
  const [data, setData] = useState<AllocPage | null>(null)
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const size = 50
  const stage: 'final' | 'approved' = perms && ['REGIONAL_FINALIZATION', 'FINAL'].includes(perms.state) ? 'final' : 'approved'
  const editable = mode === 'planner' && !!perms?.canEditAllocation

  const load = useCallback(() => {
    api.allocation(cycleId, search, onlyConstrained, page, size).then(setData).catch((e: Error) => setMessage({ kind: 'error', text: e.message }))
  }, [api, cycleId, search, onlyConstrained, page])

  useEffect(() => { setPending({}); load() }, [load, refreshKey])

  /** The number shown for a cell: the pending edit, else final or approved depending on the stage. */
  const value = (c: AllocCell) => pending[c.lineId]?.value ?? (stage === 'final' ? c.finalQty : c.approved)

  /** Records or removes a pending change of an approved cell. */
  function edit(c: AllocCell, text: string) {
    const v = Number(text)
    setPending((p) => {
      const next = { ...p }
      if (text === '' || !Number.isInteger(v) || v < 0 || v === c.approved) delete next[c.lineId]
      else next[c.lineId] = { value: v, reason: p[c.lineId]?.reason ?? '', rowVersion: c.rowVersion, proposed: c.proposed }
      return next
    })
  }

  /** Asks for a new reserve and a reason, then saves it. */
  async function editReserve(productId: string, product: string, current: number, rowVersion: number) {
    const qty = window.prompt(`Reserve for ${product} (units held back):`, String(current))
    if (qty === null) return
    const reason = window.prompt('Reason for the reserve (required):')
    if (!reason?.trim()) return
    try {
      await api.setReserve(cycleId, productId, Number(qty), reason.trim(), rowVersion)
      setMessage({ kind: 'ok', text: `Reserve of ${product} set to ${qty}. Re-run the allocation to use it.` })
      load(); onSaved()
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
      load()
    }
  }

  /** Saves all pending approved-cell changes as one batch (all or nothing). */
  async function save() {
    const missing = Object.values(pending).some((p) => p.value !== p.proposed && !p.reason.trim())
    if (missing) {
      setMessage({ kind: 'error', text: 'Every change from the proposal needs a reason (fields marked in red).' })
      return
    }
    const edits: EditReq[] = Object.entries(pending).map(([lineId, p]) => ({ lineId, field: 'APPROVED', value: p.value, reason: p.reason.trim() || null, rowVersion: p.rowVersion }))
    try {
      await api.editLines(cycleId, edits)
      setMessage({ kind: 'ok', text: `Saved ${edits.length} change(s).` })
      setPending({}); load(); onSaved()
    } catch (e) {
      const err = e as ApiError
      setMessage({ kind: 'error', text: err.code === 'CONFLICT' ? 'Someone else changed these rows; nothing was saved and the grid was reloaded.' : err.message })
      if (err.code === 'CONFLICT') { setPending({}); load() }
    }
  }

  const regions = data && data.rows[0] ? Object.keys(data.rows[0].regions) : ['NORTH', 'EAST', 'WEST', 'SOUTH']
  const shownRegions = onlyRegion ? regions.filter((r) => r === onlyRegion) : regions
  const pages = data ? Math.max(1, Math.ceil(data.total / size)) : 1
  const s = data?.summary

  return (
    <section className="card">
      <h3>{mode === 'regional' ? 'Allocation (all regions)' : 'Demand vs stock and allocation'} — showing {stage}</h3>
      {s && (
        <p className="muted">
          {s.products} products, <b>{s.constrained}</b> constrained. Demand {s.demand.toLocaleString()}, allocatable {s.allocatable.toLocaleString()}.
          {s.proposalAt ? <> Proposal {new Date(s.proposalAt).toLocaleString()} (rules {s.rulesetVersion}, parameters v{s.parameterVersion}).</> : ' No proposal yet.'}
          {s.stale && <b className="error"> Inputs changed since the proposal — re-run it.</b>}
        </p>
      )}
      <p>
        <input aria-label="Search product" placeholder="Search product…" value={search} onChange={(e) => { setPage(0); setSearch(e.target.value) }} />{' '}
        <label className="check"><input type="checkbox" checked={onlyConstrained} onChange={(e) => { setPage(0); setOnlyConstrained(e.target.checked) }} /> constrained only</label>
        {Object.keys(pending).length > 0 && <><button onClick={save}>Save {Object.keys(pending).length} change(s)</button><button onClick={() => setPending({})}>Discard</button></>}
      </p>
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
      <div className="grid-scroll">
        <table className="grid">
          <thead>
            <tr>
              <th className="sticky">Product</th><th>Available</th><th>Reserve</th><th>Allocatable</th><th>Status</th>
              {shownRegions.map((r) => <th key={r} className={myRegions.includes(r) ? 'mine' : ''}>{r}<br /><small>demand → {stage}</small></th>)}
              <th>Total</th><th>Coverage</th>
              {mode === 'regional' && myRegions.length > 0 && <th>My fill rate</th>}
            </tr>
          </thead>
          <tbody>
            {data?.rows.map((row) => {
              const cells = Object.values(row.regions)
              const total = cells.reduce((a, c) => a + (value(c) ?? 0), 0)
              const demand = cells.reduce((a, c) => a + c.demand, 0)
              const over = total > row.stock.allocatable
              const mine = cells.filter((_, i) => myRegions.includes(regions[i]))
              const myDemand = mine.reduce((a, c) => a + c.demand, 0)
              const myAlloc = mine.reduce((a, c) => a + (value(c) ?? 0), 0)
              return (
                <tr key={row.productId}>
                  <td className="sticky">{row.product}</td>
                  <td>{row.stock.available}</td>
                  <td>
                    {row.stock.reserve}
                    {mode === 'planner' && perms?.canEditReserve && <button className="link" title={row.stock.reserveReason ?? ''} onClick={() => editReserve(row.productId, row.product, row.stock.reserve, row.stock.rowVersion)}> edit</button>}
                  </td>
                  <td>{row.stock.allocatable}</td>
                  <td title={row.stock.explanation ?? ''}>
                    <span className={`pill ${row.stock.classification === 'CONSTRAINED' ? 'open-pill' : 'ok-pill'}`}>{row.stock.classification ?? '—'}</span>
                  </td>
                  {shownRegions.map((r) => {
                    const c = row.regions[r]
                    if (!c) return <td key={r}>—</td>
                    const p = pending[c.lineId]
                    return (
                      <td key={r} className={`${myRegions.includes(r) ? 'mine' : ''} ${p ? 'changed' : ''}`}>
                        <span className="muted">{c.demand} → </span>
                        {editable
                          ? <input className="cellinput" aria-label={`${row.product} ${r} approved`} type="number" min={0} value={p ? p.value : c.approved ?? ''} onChange={(e) => edit(c, e.target.value)} />
                          : <b>{value(c) ?? '—'}</b>}
                        {editable && c.proposed !== null && (p ? p.value : c.approved) !== c.proposed && <small className="muted"> (proposed {c.proposed})</small>}
                        {p && p.value !== p.proposed && (
                          <input className={`reason ${p.reason.trim() ? '' : 'missing'}`} aria-label="Reason for the change" placeholder="reason" value={p.reason}
                            onChange={(e) => setPending((all) => ({ ...all, [c.lineId]: { ...p, reason: e.target.value } }))} />
                        )}
                      </td>
                    )
                  })}
                  <td className={over ? 'error' : ''} title={over ? 'More than the allocatable stock (BR-VA-01)' : ''}><b>{total}</b>{over && ' ⚠'}</td>
                  <td>{demand === 0 ? '—' : `${Math.round((100 * row.stock.allocatable) / demand)}%`}</td>
                  {mode === 'regional' && myRegions.length > 0 && (
                    <td>{myDemand === 0 ? '—' : `${Math.round((100 * myAlloc) / myDemand)}%`}{myAlloc < myDemand && <small className="muted"> (short {myDemand - myAlloc})</small>}</td>
                  )}
                </tr>
              )
            })}
            {data && data.rows.length === 0 && <tr><td colSpan={12} className="muted">No data.</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="muted">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Prev</button> Page {page + 1} of {pages} ({data?.total ?? 0} products){' '}
        <button disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next ›</button>
      </p>
    </section>
  )
}
