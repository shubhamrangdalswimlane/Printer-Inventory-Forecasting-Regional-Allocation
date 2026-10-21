/**
 * ForecasterActions.tsx - Approve & publish (with comment and review deadline) and Recall
 * (PRD FR-FC-06, FR-FC-07, FR-FC-09). Buttons appear only when the permissions allow the action.
 */
import { useState } from 'react'
import type { Api, Perms } from '../../api'

/**
 * @param props.api      API client
 * @param props.cycleId  the cycle
 * @param props.perms    the caller's permissions
 * @param props.onDone   called after a successful transition so the page reloads
 */
export default function ForecasterActions({ api, cycleId, perms, onDone }: { api: Api; cycleId: string; perms: Perms | null; onDone: () => void }) {
  const [comment, setComment] = useState('')
  const [deadline, setDeadline] = useState('')
  const [message, setMessage] = useState<{ kind: 'error' | 'ok'; text: string } | null>(null)

  /** Runs a transition and reports the outcome. */
  async function run(action: 'PUBLISH' | 'RECALL') {
    setMessage(null)
    try {
      await api.transition(cycleId, { action, comment: comment || undefined, reviewDeadline: deadline ? new Date(deadline).toISOString() : undefined })
      setMessage({ kind: 'ok', text: action === 'PUBLISH' ? 'Published. Regional managers have been notified.' : 'Recalled to draft.' })
      setComment('')
      onDone()
    } catch (e) {
      setMessage({ kind: 'error', text: (e as Error).message })
    }
  }

  if (!perms || (!perms.canPublish && !perms.canRecall)) return null
  return (
    <section className="card">
      <h3>{perms.canPublish ? 'Approve & publish' : 'Regional review in progress'}</h3>
      {perms.canPublish && (
        <>
          <p className="muted">Publishing freezes these numbers for the regions. It is blocked while the validation shows errors.</p>
          <label>Review deadline (optional) <input type="datetime-local" value={deadline} onChange={(e) => setDeadline(e.target.value)} /></label>{' '}
        </>
      )}
      <input aria-label="Comment" placeholder="Comment" value={comment} onChange={(e) => setComment(e.target.value)} />{' '}
      {perms.canPublish && <button onClick={() => run('PUBLISH')}>Approve &amp; publish</button>}
      {perms.canRecall && <button onClick={() => window.confirm('Recall the forecast to draft? Regional edits will be cleared.') && run('RECALL')}>Recall</button>}
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}
    </section>
  )
}
