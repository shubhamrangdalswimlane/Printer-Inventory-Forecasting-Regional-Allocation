/**
 * TransferPanel.tsx - transfers between regions during finalization (PRD FR-RM-07, FR-RM-10, FR-PL-08).
 *
 * A Regional Manager can ask another region for units of a model, offer units to another region, or (when
 * owning both regions) move units directly. The inbox lists what waits for the user's decision; "mine"
 * lists what the user started. The Planner sees every pending transfer and can decide on a region's behalf.
 */
import { useCallback, useEffect, useState } from 'react'
import type { AllocRow, Api, Perms, TransferRow } from '../../api'

/**
 * @param props.api        API client
 * @param props.cycleId    the cycle
 * @param props.perms      the caller's permissions
 * @param props.username   the caller (to find "my" transfers)
 * @param props.myRegions  the caller's regions (empty for the Planner)
 * @param props.refreshKey change to reload
 * @param props.onChanged  called after any action so the allocation grid reloads
 */
export default function TransferPanel(props: { api: Api; cycleId: string; perms: Perms | null; username: string; myRegions: string[]; refreshKey: number; onChanged: () => void }) {
  const { api, cycleId, perms, username, myRegions, refreshKey, onChanged } = props
  const [rows, setRows] = useState<TransferRow[]>([])
  const [products, setProducts] = useState<AllocRow[]>([])
  const [form, setForm] = useState({ productId: '', fromRegion: '', toRegion: myRegions[0] ?? '', qty: '1', reason: '' })
  const [message, setMessage] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)

  const load = useCallback(() => {
    api.transfers(cycleId).then(setRows).catch((e: Error) => setMessage({ kind: 'error', text: e.message }))
  }, [api, cycleId])
  useEffect(load, [load, refreshKey])
  useEffect(() => { if (perms?.canCreateTransfer) api.allocation(cycleId, '', false, 0, 500).then((p) => setProducts(p.rows)).catch(() => setProducts([])) }, [api, cycleId, perms, refreshKey])

  /** Runs an action, reports the result, reloads. */
  async function act(fn: () => Promise<string>) {
    setMessage(null)
    try { setMessage({ kind: 'ok', text: await fn() }); load(); onChanged() } catch (e) { setMessage({ kind: 'error', text: (e as Error).message }) }
  }

  /** @returns true if the caller decides this transfer: donor for a request, receiver for an offer (or Planner). */
  const decides = (t: TransferRow) => perms?.canDecideAnyTransfer || myRegions.includes(t.kind === 'REQUEST' ? t.fromRegion : t.toRegion)
  const pending = rows.filter((t) => t.status === 'REQUESTED')
  const inbox = pending.filter((t) => decides(t) && t.requestedBy !== username)
  const mine = rows.filter((t) => t.requestedBy === username)
  const selected = products.find((p) => p.productId === form.productId)

  return (
    <section className="card">
      <h3>Transfers</h3>
      {perms?.canCreateTransfer && (
        <div className="transfer-form">
          <select aria-label="Product" value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>
            <option value="">Product…</option>
            {products.map((p) => <option key={p.productId} value={p.productId}>{p.product}</option>)}
          </select>{' '}
          from <select aria-label="From region" value={form.fromRegion} onChange={(e) => setForm({ ...form, fromRegion: e.target.value })}>
            <option value="">…</option>{['NORTH', 'EAST', 'WEST', 'SOUTH'].map((r) => <option key={r}>{r}</option>)}
          </select>{' '}
          to <select aria-label="To region" value={form.toRegion} onChange={(e) => setForm({ ...form, toRegion: e.target.value })}>
            <option value="">…</option>{['NORTH', 'EAST', 'WEST', 'SOUTH'].map((r) => <option key={r}>{r}</option>)}
          </select>{' '}
          <input className="narrow" aria-label="Units to move" type="number" min={1} value={form.qty} onChange={(e) => setForm({ ...form, qty: e.target.value })} /> units{' '}
          <input aria-label="Reason for the transfer" placeholder="reason" value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} />{' '}
          <button disabled={!form.productId || !form.fromRegion || !form.toRegion}
            onClick={() => act(async () => {
              const r = await api.createTransfer(cycleId, { productId: form.productId, fromRegion: form.fromRegion, toRegion: form.toRegion, qty: Number(form.qty), reason: form.reason || undefined })
              return (r.transfer.kind === 'DIRECT' ? 'Moved directly between your regions.' : `Sent as ${r.transfer.kind.toLowerCase()}; waiting for the other region.`) + (r.warnings.length ? ' Warning: ' + r.warnings.join('; ') : '')
            })}>
            {myRegions.includes(form.fromRegion) && myRegions.includes(form.toRegion) ? 'Move' : myRegions.includes(form.toRegion) ? 'Request' : 'Offer'}
          </button>
          {selected && (
            <p className="muted">Current {selected.product}: {Object.entries(selected.regions).map(([r, c]) => `${r} ${c.finalQty ?? c.approved ?? 0}/${c.demand}`).join(' · ')} (allocation/demand)</p>
          )}
        </div>
      )}
      {message && <p className={message.kind === 'ok' ? 'ok' : 'error'}>{message.text}</p>}

      <h4>{perms?.canDecideAnyTransfer ? 'Pending transfers (you can decide on a region\'s behalf)' : 'Waiting for your decision'} ({perms?.canDecideAnyTransfer ? pending.length : inbox.length})</h4>
      <TransferTable rows={perms?.canDecideAnyTransfer ? pending : inbox} actions={(t) => (
        <>
          <button className="primary" onClick={() => act(async () => { const r = await api.acceptTransfer(t.id); return 'Accepted.' + (r.warnings.length ? ' Warning: ' + r.warnings.join('; ') : '') })}>Accept</button>
          <button onClick={() => { const why = window.prompt('Reason for rejecting (optional):') ?? undefined; act(async () => { await api.rejectTransfer(t.id, why); return 'Rejected.' }) }}>Reject</button>
        </>
      )} />

      {mine.length > 0 && (
        <>
          <h4>Started by you</h4>
          <TransferTable rows={mine} actions={(t) => t.status === 'REQUESTED' ? <button onClick={() => act(async () => { await api.cancelTransfer(t.id); return 'Cancelled.' })}>Cancel</button> : null} />
        </>
      )}
      <details>
        <summary className="muted">All transfers of this cycle ({rows.length})</summary>
        <TransferTable rows={rows} actions={() => null} />
      </details>
    </section>
  )
}

/** A plain table of transfers with a per-row actions column. */
function TransferTable({ rows, actions }: { rows: TransferRow[]; actions: (t: TransferRow) => React.ReactNode }) {
  if (rows.length === 0) return <p className="muted">None.</p>
  return (
    <table>
      <thead><tr><th>Product</th><th>From</th><th>To</th><th>Units</th><th>Kind</th><th>Status</th><th>By</th><th>Reason</th><th>Decision</th><th></th></tr></thead>
      <tbody>
        {rows.map((t) => (
          <tr key={t.id}>
            <td>{t.product}</td><td>{t.fromRegion}</td><td>{t.toRegion}</td><td>{t.qty}</td><td>{t.kind}</td><td>{t.status}</td>
            <td>{t.requestedBy}</td><td>{t.reason ?? ''}</td><td>{t.decidedBy ? `${t.decidedBy}${t.decisionComment ? ': ' + t.decisionComment : ''}` : ''}</td>
            <td className="actions">{actions(t)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
