/**
 * PlannerActions.tsx - the Planner's workflow controls for the forecast stage (PRD FR-PL-06, FR-PL-07):
 * Return one or more regions for rework (in PLANNING), or Proceed after the review deadline when a
 * region has not submitted (in REGIONAL_REVIEW). Both need a comment, which is audited.
 */
import { useState } from 'react'
import type { Api, CycleDetail, Perms } from '../../api'

/**
 * @param props.api     API client
 * @param props.cycleId the cycle
 * @param props.detail  the cycle with region statuses
 * @param props.perms   the caller's permissions
 * @param props.onDone  called after a successful transition so the page reloads
 */
export default function PlannerActions({ api, cycleId, detail, perms, onDone }: { api: Api; cycleId: string; detail: CycleDetail | null; perms: Perms | null; onDone: () => void }) {
  const [comment, setComment] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  const [message, setMessage] = useState<{ kind: 'error' | 'ok'; text: string } | null>(null)
  if (!detail || !perms || (!perms.canReturnRegions && !perms.canProceed)) return null

  /** Runs a transition and reports the outcome. */
  async function run(t: Parameters<Api['transition']>[1], okText: string) {
    setMessage(null)
    try {
      await api.transition(cycleId, t)
      setMessage({ kind: 'ok', text: okText })
      setComment(''); setPicked([])
      onDone()
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    }
  }

  return (
    <section className="card">
      <h3>Forecast workflow</h3>
      <p className="muted">Cycle {detail.cycle.month} is in <b>{detail.cycle.state}</b>.</p>
      <input placeholder="Comment (required)" value={comment} onChange={(e) => setComment(e.target.value)} />
      {perms.canReturnRegions && (
        <p>
          Return to Regional Managers:{' '}
          {detail.regions.map((r) => (
            <label key={r.region} className="check"><input type="checkbox" checked={picked.includes(r.region)}
              onChange={(e) => setPicked((p) => e.target.checked ? [...p, r.region] : p.filter((x) => x !== r.region))} /> {r.region}</label>
          ))}{' '}
          <button disabled={picked.length === 0} onClick={() => run({ action: 'RETURN_REGIONS', regions: picked, comment }, `Returned ${picked.join(', ')}.`)}>Return selected</button>
        </p>
      )}
      {perms.canProceed && (
        <p>
          <button onClick={() => run({ action: 'PROCEED', comment }, 'Proceeded to planning using the current numbers.')}>Proceed after deadline</button>
          <span className="muted"> Only possible once the review deadline has passed; regions that did not submit keep their current numbers.</span>
        </p>
      )}
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
    </section>
  )
}
