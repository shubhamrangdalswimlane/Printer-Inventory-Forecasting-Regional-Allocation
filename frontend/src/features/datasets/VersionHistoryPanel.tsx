/**
 * VersionHistoryPanel.tsx - the shared list of dataset versions with Download, Compare, Activate and
 * Delete (and Undelete / Purge for Admin). Used on the Forecaster, Planner and Admin dashboards
 * (architecture.md section 11, PRD FR-FC-03 / FR-PL-01 / FR-AD-03).
 *
 * The buttons shown here only reflect the caller's role; the backend re-checks every action.
 */
import { useCallback, useEffect, useState } from 'react'
import type { Api, Comparison, Version } from '../../api'

/**
 * @param props.api          API client
 * @param props.type         which dataset type to list
 * @param props.canActivate  show Activate (Forecaster for forecasts, Planner for inventory, Admin)
 * @param props.canDelete    show Delete
 * @param props.isAdmin      show deleted versions toggle, Undelete and Purge
 * @param props.refreshKey   change this value to make the panel reload (for example after an upload)
 */
export default function VersionHistoryPanel(props: {
  api: Api; type: Version['type']; canActivate: boolean; canDelete: boolean; isAdmin: boolean; refreshKey: number
}) {
  const { api, type, canActivate, canDelete, isAdmin, refreshKey } = props
  const [rows, setRows] = useState<Version[]>([])
  const [showDeleted, setShowDeleted] = useState(false)
  const [picked, setPicked] = useState<string[]>([])
  const [diff, setDiff] = useState<Comparison | null>(null)
  const [error, setError] = useState<string | null>(null)

  /** Reloads the version list from the backend. */
  const load = useCallback(() => {
    api.versions(type, isAdmin && showDeleted).then(setRows).catch((e: Error) => setError(e.message))
  }, [api, type, isAdmin, showDeleted])

  useEffect(load, [load, refreshKey])

  /**
   * Runs an action that needs a reason: asks for one, calls the API, reloads, and shows any error.
   * @param ask    the prompt text
   * @param action the API call, given the reason
   */
  async function withReason(ask: string, action: (reason: string) => Promise<void>) {
    const reason = window.prompt(ask)
    if (!reason?.trim()) return // cancelled or empty: the backend requires a reason
    try { setError(null); await action(reason.trim()); load() } catch (e) { setError((e as Error).message) }
  }

  /** Runs any API action, reloads and shows any error. */
  async function run(action: () => Promise<unknown>) {
    try { setError(null); await action(); load() } catch (e) { setError((e as Error).message) }
  }

  /** Toggles a row in the (max two) comparison selection. */
  function toggle(id: string) {
    setDiff(null)
    setPicked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id].slice(-2)))
  }

  return (
    <section className="card">
      <h3>Version history ({type === 'FORECAST_UPLOAD' ? 'forecast' : 'inventory'})</h3>
      {isAdmin && (
        <label className="muted"><input type="checkbox" checked={showDeleted} onChange={(e) => setShowDeleted(e.target.checked)} /> Show deleted</label>
      )}
      {error && <p className="error">{error}</p>}
      <table>
        <thead>
          <tr><th></th><th>Version</th><th>Cycle</th><th>Status</th><th>Source</th><th>File</th><th>Rows</th><th>By</th><th>When</th><th>Actions</th></tr>
        </thead>
        <tbody>
          {rows.map((v) => (
            <tr key={v.id} className={v.active ? 'row-active' : v.status === 'DELETED' || v.status === 'PURGED' ? 'row-deleted' : ''}>
              <td><input type="checkbox" checked={picked.includes(v.id)} onChange={() => toggle(v.id)} aria-label={`select ${v.label}`} /></td>
              <td>{v.label}{v.active && ' ★'}</td>
              <td>{v.cycleMonth}</td>
              <td>{v.status}</td>
              <td>{v.source}</td>
              <td title={v.sha256 ?? ''}>{v.originalFilename ?? '—'}</td>
              <td>{v.rowCount}</td>
              <td>{v.createdBy}</td>
              <td>{new Date(v.createdAt).toLocaleString()}</td>
              <td className="actions">
                {v.status !== 'PURGED' && (
                  <>
                    <button onClick={() => run(() => api.downloadVersion(v.id, 'xlsx', v.label))}>xlsx</button>
                    <button onClick={() => run(() => api.downloadVersion(v.id, 'csv', v.label))}>csv</button>
                    {v.sha256 && v.status !== 'DELETED' && <button onClick={() => run(() => api.downloadVersion(v.id, 'original', v.label))}>original</button>}
                  </>
                )}
                {canActivate && (v.status === 'VALID' || v.status === 'SUPERSEDED') && (
                  <button onClick={() => withReason('Reason for activating this version?', (r) => api.activate(v.id, r))}>Activate</button>
                )}
                {canDelete && !v.active && v.status !== 'DELETED' && v.status !== 'PURGED' && (
                  <button onClick={() => withReason('Reason for deleting this version?', (r) => api.remove(v.id, r))}>Delete</button>
                )}
                {isAdmin && v.status === 'DELETED' && (
                  <>
                    <button onClick={() => withReason('Reason for restoring?', (r) => api.undelete(v.id, r))}>Undelete</button>
                    <button onClick={() => window.confirm(`Permanently purge ${v.label}? This cannot be undone.`) && run(() => api.purge(v.id))}>Purge</button>
                  </>
                )}
              </td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td colSpan={10} className="muted">No versions yet.</td></tr>}
        </tbody>
      </table>
      <p>
        <button disabled={picked.length !== 2} onClick={() => run(async () => setDiff(await api.compare(picked[0], picked[1])))}>
          Compare selected (older first)
        </button>
      </p>
      {diff && (
        <div>
          <p>{diff.added.length} added, {diff.removed.length} removed, {diff.changed.length} changed.</p>
          {diff.changed.length > 0 && (
            <table>
              <thead><tr><th>Item</th><th>Base</th><th>Target</th></tr></thead>
              <tbody>{diff.changed.slice(0, 100).map((c) => <tr key={c.key}><td>{c.key}</td><td>{c.base}</td><td>{c.target}</td></tr>)}</tbody>
            </table>
          )}
        </div>
      )}
    </section>
  )
}
