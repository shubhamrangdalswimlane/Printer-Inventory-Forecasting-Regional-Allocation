/**
 * FinalizationMonitor.tsx - the Planner's view during REGIONAL_FINALIZATION (PRD FR-PL-08): which regions
 * have finalized, and the button to take the allocation back (reopen) with a comment. Pending transfers and
 * deciding them on a region's behalf are in the TransferPanel shown next to it.
 */
import { useState } from 'react'
import type { Api, CycleDetail, Perms } from '../../api'

/**
 * @param props.api     API client
 * @param props.cycleId the cycle
 * @param props.detail  the cycle with region statuses
 * @param props.perms   the caller's permissions
 * @param props.onDone  called after reopening so the page reloads
 */
export default function FinalizationMonitor({ api, cycleId, detail, perms, onDone }: { api: Api; cycleId: string; detail: CycleDetail | null; perms: Perms | null; onDone: () => void }) {
  const [comment, setComment] = useState('')
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  if (!detail || detail.cycle.state !== 'REGIONAL_FINALIZATION') return null
  const done = detail.regions.filter((r) => r.finalizeStatus === 'FINALIZED').length

  /** Reopens the allocation (back to PLANNING); pending transfers are cancelled by the server. */
  async function reopen() {
    setMessage(null)
    try {
      await api.transition(cycleId, { action: 'REOPEN_ALLOCATION', comment })
      setMessage({ kind: 'ok', text: 'Allocation reopened; the cycle is back in PLANNING.' })
      setComment('')
      onDone()
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    }
  }

  return (
    <section className="card">
      <h3>Finalization monitor — {done} of {detail.regions.length} regions finalized</h3>
      <p>{detail.regions.map((r) => <span key={r.region} className={`pill ${r.finalizeStatus === 'FINALIZED' ? 'ok-pill' : 'open-pill'}`}>{r.region}: {r.finalizeStatus.toLowerCase()}</span>)}</p>
      {detail.cycle.finalizeDeadline && <p className="muted">Deadline: {new Date(detail.cycle.finalizeDeadline).toLocaleString()}</p>}
      {perms?.canReopenAllocation && (
        <p>
          <input aria-label="Reason to reopen" placeholder="Why reopen? (required)" value={comment} onChange={(e) => setComment(e.target.value)} />{' '}
          <button disabled={!comment.trim()} onClick={() => window.confirm('Reopen the allocation? Pending transfers are cancelled and every region must finalize again.') && reopen()}>Reopen allocation</button>
        </p>
      )}
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
    </section>
  )
}
