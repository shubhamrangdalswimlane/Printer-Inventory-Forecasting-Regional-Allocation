/**
 * RegionalActions.tsx - "My tasks" and the Submit buttons of the Regional Manager (PRD FR-RM-01, FR-RM-05).
 * Shows what needs action in the selected cycle and lets the manager submit each of their open regions.
 */
import { useState } from 'react'
import type { Api, CycleDetail, Perms } from '../../api'

/**
 * @param props.api     API client
 * @param props.cycleId the cycle
 * @param props.detail  the cycle with region statuses
 * @param props.perms   the caller's permissions
 * @param props.regions the manager's own regions
 * @param props.onDone  called after a successful submit so the page reloads
 */
export default function RegionalActions({ api, cycleId, detail, perms, regions, onDone }: {
  api: Api; cycleId: string; detail: CycleDetail | null; perms: Perms | null; regions: string[]; onDone: () => void
}) {
  const [message, setMessage] = useState<{ kind: 'error' | 'ok'; text: string } | null>(null)
  if (!detail || !perms) return null
  const state = detail.cycle.state
  const mine = detail.regions.filter((r) => regions.includes(r.region))

  /** Runs a region transition (finalize / un-finalize) and reports the outcome. */
  async function run(action: 'FINALIZE_REGION' | 'UNFINALIZE_REGION', region: string, okText: string) {
    setMessage(null)
    try {
      await api.transition(cycleId, { action, region })
      setMessage({ kind: 'ok', text: okText })
      onDone()
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    }
  }

  /** Submits one region; the server locks that region's edits and creates the snapshot. */
  async function submit(region: string) {
    setMessage(null)
    try {
      await api.transition(cycleId, { action: 'SUBMIT_REGION', region })
      setMessage({ kind: 'ok', text: `${region} submitted.` })
      onDone()
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    }
  }

  return (
    <section className="card">
      <h3>My tasks — {detail.cycle.month}</h3>
      {state === 'FORECAST_DRAFT' && <p className="muted">The Forecaster has not published yet. Nothing to do.</p>}
      {state === 'REGIONAL_REVIEW' && mine.length === 0 && <p className="muted">No regions are assigned to you.</p>}
      {state === 'REGIONAL_REVIEW' && mine.map((r) => (
        <p key={r.region}>
          <b>{r.region}</b>: {r.reviewStatus === 'OPEN'
            ? <>review the forecast{detail.cycle.reviewDeadline ? <> by <b>{new Date(detail.cycle.reviewDeadline).toLocaleString()}</b></> : ''}, then{' '}
                <button disabled={!perms.submittableRegions.includes(r.region)} onClick={() => window.confirm(`Submit ${r.region}? You will not be able to edit it afterwards unless the Planner returns it.`) && submit(r.region)}>Submit {r.region}</button></>
            : <span className="ok">submitted{r.actor ? ` by ${r.actor}` : ''} ✓</span>}
        </p>
      ))}
      {state === 'PLANNING' && <p className="muted">Regional review is complete. The Planner is working on the allocation.</p>}
      {state === 'REGIONAL_FINALIZATION' && mine.map((r) => (
        <p key={r.region}>
          <b>{r.region}</b>: {r.finalizeStatus === 'FINALIZED'
            ? <><span className="ok">finalized ✓</span> <button onClick={() => run('UNFINALIZE_REGION', r.region, `${r.region} reopened for changes.`)}>Un-finalize</button></>
            : <>review the allocation, agree any transfers, then{' '}
                <button disabled={!perms.finalizableRegions.includes(r.region)} onClick={() => window.confirm(`Finalize ${r.region}? Pending transfers must be settled first.`) && run('FINALIZE_REGION', r.region, `${r.region} finalized.`)}>Finalize {r.region}</button>
                {detail.cycle.finalizeDeadline && <span className="muted"> (deadline {new Date(detail.cycle.finalizeDeadline).toLocaleString()})</span>}</>}
          {' '}<button className="link" onClick={() => api.exportAllocation(cycleId, 'final', r.region).catch((e) => setMessage({ kind: 'error', text: e.message }))}>Download {r.region} (xlsx)</button>
        </p>
      ))}
      {state === 'FINAL' && <p className="ok">The cycle is FINAL. {mine.map((r) => <button key={r.region} className="link" onClick={() => api.exportAllocation(cycleId, 'final', r.region)}>Download {r.region}</button>)}</p>}
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
    </section>
  )
}
