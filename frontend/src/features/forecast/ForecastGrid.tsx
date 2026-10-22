/**
 * ForecastGrid.tsx - the forecast table used by the Forecaster, Regional Manager and Planner/Admin
 * (PRD FR-FC-04, FR-RM-02, FR-COM-03..05).
 *
 * One component, three modes:
 *  - "forecaster": the published quantity is editable (while the cycle is a draft); the file's original
 *    value is shown when the Forecaster changed it.
 *  - "regional":   published values are read-only for all regions; the revised quantity is editable only
 *    for the manager's own open region(s), with a change % and a reason field when the change is large.
 *  - "readonly":   published and revised values, nothing editable.
 *
 * Edits are collected locally and saved together with one button (all-or-nothing on the server). A 409
 * CONFLICT (someone else changed a row) reloads the data and tells the user, so nothing is overwritten
 * silently. Paging is done by the server (50 products per page); search filters by product name.
 */
import { useCallback, useEffect, useState } from 'react'
import { ApiError, type Api, type Cell, type EditReq, type GridPage, type Perms } from '../../api'

/** A not-yet-saved edit of one cell. */
type Pending = { field: 'PUBLISHED' | 'REVISED'; value: number; reason: string; rowVersion: number; original: number; published: number }

/**
 * @param props.api        API client
 * @param props.cycleId    the cycle to show
 * @param props.mode       "forecaster", "regional" or "readonly"
 * @param props.perms      the caller's permissions (which cells are editable, change threshold)
 * @param props.refreshKey change to force a reload (after a workflow action)
 * @param props.onSaved    called after a successful save
 */
export default function ForecastGrid(props: {
  api: Api; cycleId: string; mode: 'forecaster' | 'regional' | 'readonly'; perms: Perms | null; refreshKey: number; onSaved: () => void
  /** Show only this region's column (null/undefined = all regions); the Total column always sums all regions. */
  onlyRegion?: string | null
}) {
  const { api, cycleId, mode, perms, refreshKey, onSaved, onlyRegion } = props
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [data, setData] = useState<GridPage | null>(null)
  const [pending, setPending] = useState<Record<string, Pending>>({})
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkPct, setBulkPct] = useState('10')
  const [bulkReason, setBulkReason] = useState('')
  const [message, setMessage] = useState<{ kind: 'error' | 'ok'; text: string } | null>(null)
  const [saving, setSaving] = useState(false)
  const size = 50
  const threshold = perms?.changeThresholdPct ?? 20

  /** Loads the current page; pending edits are dropped because they refer to old row versions. */
  const load = useCallback(() => {
    api.grid(cycleId, search, page, size).then(setData).catch((e: Error) => setMessage({ kind: 'error', text: e.message }))
  }, [api, cycleId, search, page])

  useEffect(() => { setPending({}); load() }, [load, refreshKey])

  const allRegionCodes = data && data.rows[0] ? Object.keys(data.rows[0].regions) : ['NORTH', 'EAST', 'WEST', 'SOUTH']
  const regionCodes = onlyRegion ? allRegionCodes.filter((r) => r === onlyRegion) : allRegionCodes
  const editableRevised = new Set(mode === 'regional' ? perms?.editableRegions ?? [] : [])
  const canEditPublished = mode === 'forecaster' && !!perms?.canEditPublished

  /**
   * Records (or removes) the pending edit of one cell.
   * @param cell    the cell as loaded
   * @param field   which column is being edited
   * @param text    what the user typed
   */
  function edit(cell: Cell, field: 'PUBLISHED' | 'REVISED', text: string) {
    const original = field === 'PUBLISHED' ? cell.published : cell.revised ?? cell.published
    const value = text === '' ? NaN : Number(text)
    setPending((p) => {
      const next = { ...p }
      if (Number.isNaN(value) || !Number.isInteger(value) || value < 0 || value === original) delete next[cell.lineId]
      else next[cell.lineId] = { field, value, reason: p[cell.lineId]?.reason ?? cell.reason ?? '', rowVersion: cell.rowVersion, original, published: cell.published }
      return next
    })
  }

  /** @returns percentage change of a pending REVISED edit versus the published value (0 for no change) */
  const changePct = (p: Pending) => (p.value === p.published ? 0 : p.published === 0 ? 1000 : Math.abs(p.value - p.published) / p.published * 100)
  /** @returns true if the edit needs a reason: a revised change above the threshold */
  const needsReason = (p: Pending) => p.field === 'REVISED' && changePct(p) > threshold

  /** Applies a percentage change to every selected product in the manager's editable regions. */
  function applyBulk() {
    const pct = Number(bulkPct)
    if (!data || Number.isNaN(pct)) return
    const next = { ...pending }
    for (const row of data.rows) {
      if (!selected.has(row.productId)) continue
      for (const [region, cell] of Object.entries(row.regions)) {
        if (!editableRevised.has(region)) continue
        const value = Math.max(0, Math.round(cell.published * (1 + pct / 100)))
        const original = cell.revised ?? cell.published
        if (value === original) delete next[cell.lineId]
        else next[cell.lineId] = { field: 'REVISED', value, reason: bulkReason, rowVersion: cell.rowVersion, original, published: cell.published }
      }
    }
    setPending(next)
  }

  /** Sends all pending edits as one batch. */
  async function save() {
    const list: EditReq[] = Object.entries(pending).map(([lineId, p]) => ({ lineId, field: p.field, value: p.value, reason: p.reason.trim() || null, rowVersion: p.rowVersion }))
    if (Object.values(pending).some((p) => needsReason(p) && !p.reason.trim())) {
      setMessage({ kind: 'error', text: `Changes above ${threshold}% need a reason. Fill in the reason fields marked in red.` })
      return
    }
    setSaving(true); setMessage(null)
    try {
      await api.editLines(cycleId, list)
      setMessage({ kind: 'ok', text: `Saved ${list.length} change(s).` })
      setPending({}); load(); onSaved()
    } catch (e) {
      const err = e as ApiError
      if (err.code === 'CONFLICT') {
        setMessage({ kind: 'error', text: 'Someone else changed some of these rows. Nothing was saved; the grid was reloaded, please redo your edits.' })
        setPending({}); load()
      } else {
        setMessage({ kind: 'error', text: err.message })
      }
    } finally {
      setSaving(false)
    }
  }

  const sum = (cells: Cell[], f: (c: Cell) => number | null) => cells.reduce((s, c) => s + (f(c) ?? 0), 0)
  const pages = data ? Math.max(1, Math.ceil(data.total / size)) : 1
  const pendingCount = Object.keys(pending).length

  return (
    <section className="card">
      <h3>Forecast {mode === 'forecaster' ? '(published quantity — editable in draft)' : mode === 'regional' ? '(revise your region)' : '(read-only)'}</h3>
      <p>
        <input aria-label="Search product" placeholder="Search product…" value={search} onChange={(e) => { setPage(0); setSearch(e.target.value) }} />{' '}
        {pendingCount > 0 && <button onClick={save} disabled={saving}>Save {pendingCount} change(s)</button>}{' '}
        {pendingCount > 0 && <button onClick={() => setPending({})}>Discard</button>}
        {mode === 'regional' && editableRevised.size > 0 && (
          <span className="bulk"> Bulk: <input className="narrow" aria-label="Bulk change in percent" value={bulkPct} onChange={(e) => setBulkPct(e.target.value)} />% on selected rows
            <input aria-label="Reason for the bulk change" placeholder={`reason (needed above ${threshold}%)`} value={bulkReason} onChange={(e) => setBulkReason(e.target.value)} />
            <button onClick={applyBulk} disabled={selected.size === 0}>Apply</button></span>
        )}
      </p>
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
      <div className="grid-scroll">
        <table className="grid">
          <thead>
            <tr>
              {mode === 'regional' && <th></th>}
              <th className="sticky">Product</th>
              {regionCodes.map((r) => <th key={r}>{r}</th>)}
              <th>Total</th>
            </tr>
          </thead>
          <tbody>
            {data?.rows.map((row) => {
              const cells = Object.values(row.regions)
              return (
                <tr key={row.productId}>
                  {mode === 'regional' && (
                    <td><input type="checkbox" checked={selected.has(row.productId)} aria-label={`select ${row.product}`}
                      onChange={(e) => setSelected((s) => { const n = new Set(s); if (e.target.checked) n.add(row.productId); else n.delete(row.productId); return n })} /></td>
                  )}
                  <td className="sticky">{row.product}</td>
                  {regionCodes.map((r) => {
                    const cell = row.regions[r]
                    if (!cell) return <td key={r}>—</td>
                    const p = pending[cell.lineId]
                    // forecaster mode: one editable number
                    if (mode === 'forecaster') {
                      return (
                        <td key={r} className={cell.published !== cell.uploaded ? 'changed' : ''} title={cell.published !== cell.uploaded ? `file: ${cell.uploaded}` : ''}>
                          {canEditPublished
                            ? <input className="cellinput" aria-label={`${row.product} ${r} published`} type="number" min={0} value={p ? p.value : cell.published} onChange={(e) => edit(cell, 'PUBLISHED', e.target.value)} />
                            : cell.published}
                          {cell.published !== cell.uploaded && <small className="muted"> (file {cell.uploaded})</small>}
                        </td>
                      )
                    }
                    const canEditThis = editableRevised.has(r)
                    const shown = p ? p.value : cell.revised ?? cell.published
                    const pct = cell.published === 0 ? 0 : ((shown - cell.published) / cell.published) * 100
                    return (
                      <td key={r} className={shown !== cell.published ? 'changed' : ''}>
                        <span className="muted">{cell.published}</span>{' → '}
                        {canEditThis
                          ? <input className="cellinput" aria-label={`${row.product} ${r} revised`} type="number" min={0} value={shown} onChange={(e) => edit(cell, 'REVISED', e.target.value)} />
                          : <b>{cell.revised ?? '—'}</b>}
                        {shown !== cell.published && <small className={Math.abs(pct) > threshold ? 'error' : 'muted'}> {pct > 0 ? '+' : ''}{pct.toFixed(0)}%</small>}
                        {p && needsReason(p) && (
                          <input className={`reason ${p.reason.trim() ? '' : 'missing'}`} aria-label="Reason for the change" placeholder="reason" value={p.reason}
                            onChange={(e) => setPending((all) => ({ ...all, [cell.lineId]: { ...p, reason: e.target.value } }))} />
                        )}
                        {!p && cell.reason && <small className="muted" title={cell.reason}> ✎</small>}
                      </td>
                    )
                  })}
                  <td><b>{mode === 'forecaster' ? sum(cells, (c) => c.published) : sum(cells, (c) => c.revised ?? c.published)}</b></td>
                </tr>
              )
            })}
            {data && data.rows.length === 0 && <tr><td colSpan={7} className="muted">No forecast data for this cycle yet.</td></tr>}
          </tbody>
        </table>
      </div>
      <p className="muted">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>‹ Prev</button>{' '}
        Page {page + 1} of {pages} ({data?.total ?? 0} products){' '}
        <button disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next ›</button>
      </p>
    </section>
  )
}
