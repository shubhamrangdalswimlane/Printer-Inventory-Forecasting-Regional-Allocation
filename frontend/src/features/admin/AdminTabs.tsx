/**
 * AdminTabs.tsx - the individual tabs of the Admin dashboard. Each tab loads its own data from the
 * /api/v1/admin endpoints (and /rule-parameters), so tabs that are not open cost nothing.
 */
import { useCallback, useEffect, useState } from 'react'
import type { Api, AuditEntry, AuditFilter, CycleAnalytics, KcUser, MasterProduct, Overview, ParamValues, ParamVersion } from '../../api'
import type { CycleState } from '../cycle/useCycle'

/** Small helper: a message line that is either ok (green) or an error (red). */
type Msg = { kind: 'ok' | 'error'; text: string } | null
const MsgLine = ({ m }: { m: Msg }) => (m ? <p className={m.kind === 'ok' ? 'ok' : 'error'}>{m.text}</p> : null)

// ---------------------------------------------------------------- Overview (FR-AD-01, FR-AD-04)

/** Current cycle progress, open issues and system health. */
export function OverviewTab({ api, cycleId, refreshKey }: { api: Api; cycleId: string; refreshKey: number }) {
  const [o, setO] = useState<Overview | null>(null)
  const [m, setM] = useState<Msg>(null)
  useEffect(() => { api.overview(cycleId).then(setO).catch((e: Error) => setM({ kind: 'error', text: e.message })) }, [api, cycleId, refreshKey])
  if (!o) return <MsgLine m={m} />
  const i = o.issues
  const flags: [boolean, string][] = [
    [i.rejectedFilesLast7Days > 0, `${i.rejectedFilesLast7Days} rejected file(s) in the last 7 days`],
    [i.pendingTransfers > 0, `${i.pendingTransfers} pending transfer(s)`],
    [i.regionsNotSubmitted.length > 0, `Not yet submitted: ${i.regionsNotSubmitted.join(', ')}`],
    [i.regionsNotFinalized.length > 0, `Not yet finalized: ${i.regionsNotFinalized.join(', ')}`],
    [i.allocationStale, 'Allocation inputs changed since the last proposal'],
    [i.inventoryChangedAfterApproval, 'Inventory changed after the allocation was approved (stored, not applied)'],
    [i.reviewOverdue, 'Regional review deadline has passed'],
    [i.finalizeOverdue, 'Finalization deadline has passed'],
  ]
  const open = flags.filter(([on]) => on)
  return (
    <>
      <section className="card">
        <h3>Open issues — {o.cycle.cycle.month} ({o.cycle.cycle.state})</h3>
        {open.length === 0 ? <p className="ok">Nothing needs attention.</p> : <ul className="findings">{open.map(([, t]) => <li key={t} className="warn">{t}</li>)}</ul>}
        <p>{o.cycle.regions.map((r) => <span key={r.region} className="pill">{r.region}: review {r.reviewStatus.toLowerCase()}, finalize {r.finalizeStatus.toLowerCase()}</span>)}</p>
      </section>
      <section className="card">
        <h3>System health</h3>
        <table><tbody>
          {Object.entries(o.system).map(([k, v]) => typeof v === 'number'
            ? <tr key={k}><td>{k}</td><td>{v}</td><td></td></tr>
            : <tr key={k}><td>{k}</td><td className={v.ok === false ? 'error' : v.ok === true ? 'ok' : ''}>{v.value}</td><td className="muted">{new Date(v.updatedAt).toLocaleString()}</td></tr>)}
        </tbody></table>
      </section>
    </>
  )
}

// ---------------------------------------------------------------- Analytics (FR-AD-02)

/** A grouped bar chart: one group per region, one bar per series. Plain SVG, no chart library. */
function GroupedBars({ groups, series }: { groups: string[]; series: { name: string; color: string; values: (number | null)[] }[] }) {
  const w = 640, h = 220, pad = 30
  const max = Math.max(1, ...series.flatMap((s) => s.values.map((v) => v ?? 0)))
  const gw = (w - pad * 2) / groups.length
  const bw = (gw - 12) / series.length
  return (
    <svg width={w} height={h + 20} role="img" aria-label="Totals by region and stage">
      <line x1={pad} y1={h} x2={w - pad} y2={h} stroke="#9aa5b1" />
      {groups.map((g, gi) => (
        <g key={g}>
          {series.map((s, si) => {
            const v = s.values[gi] ?? 0
            const bh = (v / max) * (h - pad)
            return <rect key={s.name} x={pad + gi * gw + 6 + si * bw} y={h - bh} width={bw - 2} height={bh} fill={s.color}><title>{`${g} ${s.name}: ${v}`}</title></rect>
          })}
          <text x={pad + gi * gw + gw / 2} y={h + 14} fontSize="11" textAnchor="middle">{g}</text>
        </g>
      ))}
      {series.map((s, si) => <g key={s.name}><rect x={pad + si * 110} y={4} width={10} height={10} fill={s.color} /><text x={pad + si * 110 + 14} y={13} fontSize="11">{s.name}</text></g>)}
    </svg>
  )
}

/** Published vs revised vs approved vs final, fill rate, constrained share, transfer flows, stage times, accuracy. */
export function AnalyticsTab({ api }: { api: Api }) {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [data, setData] = useState<CycleAnalytics[]>([])
  const [m, setM] = useState<Msg>(null)
  const load = useCallback(() => { api.analytics(from || undefined, to || undefined).then(setData).catch((e: Error) => setM({ kind: 'error', text: e.message })) }, [api, from, to])
  useEffect(load, [load])
  return (
    <>
      <section className="card">
        <h3>Analytics</h3>
        <p><label>From <input placeholder="YYYY-MM" value={from} onChange={(e) => setFrom(e.target.value)} /></label>{' '}
          <label>To <input placeholder="YYYY-MM" value={to} onChange={(e) => setTo(e.target.value)} /></label></p>
        <MsgLine m={m} />
        <table>
          <thead><tr><th>Cycle</th><th>State</th><th>Constrained</th><th>Fill rate by region</th><th>Days: draft / review / planning / finalization</th><th>Forecast error vs actual (WAPE)</th></tr></thead>
          <tbody>
            {data.map((c) => (
              <tr key={c.cycleId}>
                <td>{c.month}</td><td>{c.state}</td>
                <td>{c.constrainedProducts}{c.constrainedSharePct !== null && ` (${c.constrainedSharePct}%)`}</td>
                <td>{Object.entries(c.regions).map(([r, v]) => `${r} ${v.fillRatePct ?? '—'}%`).join(' · ')}</td>
                <td>{[c.stageHours.draft, c.stageHours.regionalReview, c.stageHours.planning, c.stageHours.finalization].map((h) => (h === null ? '—' : (h / 24).toFixed(1))).join(' / ')}</td>
                <td>{c.accuracy.available ? `published ${c.accuracy.wapePublishedPct}%, revised ${c.accuracy.wapeRevisedPct}%, allocation ${c.accuracy.wapeAllocationPct}% (${c.accuracy.source})` : 'no actuals yet'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      {data.map((c) => (
        <section key={c.cycleId} className="card">
          <h3>{c.month}: totals by stage</h3>
          <GroupedBars groups={Object.keys(c.regions)} series={[
            { name: 'published', color: '#9aa5b1', values: Object.values(c.regions).map((v) => v.published) },
            { name: 'revised', color: '#1a56a8', values: Object.values(c.regions).map((v) => v.revised) },
            { name: 'approved', color: '#b36b00', values: Object.values(c.regions).map((v) => v.approved) },
            { name: 'final', color: '#1d7a3a', values: Object.values(c.regions).map((v) => v.final) },
          ]} />
          {c.transferFlows.length > 0 && (
            <>
              <h4>Transfer flows</h4>
              <table><thead><tr><th>From</th><th>To</th><th>Units</th><th>Transfers</th></tr></thead>
                <tbody>{c.transferFlows.map((f) => <tr key={f.from + f.to}><td>{f.from}</td><td>→ {f.to}</td><td>{f.units}</td><td>{f.transfers}</td></tr>)}</tbody></table>
            </>
          )}
        </section>
      ))}
    </>
  )
}

// ---------------------------------------------------------------- Master data (FR-AD-05)

/** Products and aliases: search, add, rename, (de)activate, aliases, bulk import. */
export function MasterDataTab({ api }: { api: Api }) {
  const [q, setQ] = useState('')
  const [all, setAll] = useState(false)
  const [rows, setRows] = useState<MasterProduct[]>([])
  const [name, setName] = useState('')
  const [m, setM] = useState<Msg>(null)
  const load = useCallback(() => { api.products(q, all).then(setRows).catch((e: Error) => setM({ kind: 'error', text: e.message })) }, [api, q, all])
  useEffect(load, [load])
  async function act(fn: () => Promise<unknown>, ok: string) {
    setM(null)
    try { await fn(); setM({ kind: 'ok', text: ok }); load() } catch (e) { setM({ kind: 'error', text: (e as Error).message }) }
  }
  return (
    <section className="card">
      <h3>Products</h3>
      <p>
        <input placeholder="Search name or alias…" value={q} onChange={(e) => setQ(e.target.value)} />{' '}
        <label className="check"><input type="checkbox" checked={all} onChange={(e) => setAll(e.target.checked)} /> include inactive</label>{' '}
        <input placeholder="New product name" value={name} onChange={(e) => setName(e.target.value)} />
        <button disabled={!name.trim()} onClick={() => act(() => api.addProduct(name), `Added ${name}`).then(() => setName(''))}>Add</button>{' '}
        <label className="link">Import Excel (sheet "Products": Product_name, Aliases, Active, Product_code)
          <input type="file" accept=".xlsx" hidden onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) api.importProducts(f).then((r) => setM(r.problems.length ? { kind: 'error', text: 'Nothing imported: ' + r.problems.join('; ') } : { kind: 'ok', text: `Imported: ${r.created} new, ${r.updated} updated, ${r.aliasesAdded} aliases` })).then(load).catch((x: Error) => setM({ kind: 'error', text: x.message }))
            e.target.value = ''
          }} />
        </label>
      </p>
      <MsgLine m={m} />
      <table>
        <thead><tr><th>Product</th><th>Code</th><th>Aliases</th><th>Active</th><th>Source</th><th></th></tr></thead>
        <tbody>
          {rows.map((p) => (
            <tr key={p.id} className={p.active ? '' : 'row-deleted'}>
              <td>{p.name}</td>
              <td>{p.code ?? ''}</td>
              <td>{p.aliases.map((a) => <span key={a.id} className="pill">{a.alias} <button className="link" title="remove alias" onClick={() => act(() => api.removeAlias(p.id, a.id), 'Alias removed')}>×</button></span>)}</td>
              <td>{p.active ? 'yes' : 'no'}</td>
              <td>{p.source}</td>
              <td className="actions">
                <button onClick={() => { const a = window.prompt(`Alias for ${p.name}:`); if (a?.trim()) act(() => api.addAlias(p.id, a), 'Alias added') }}>+ alias</button>
                <button onClick={() => { const c = window.prompt(`Product_code for ${p.name} (empty removes it):`, p.code ?? ''); if (c !== null && c !== (p.code ?? '')) act(() => api.updateProduct(p.id, { code: c }), c.trim() ? 'Code set' : 'Code removed') }}>Code</button>
                <button onClick={() => { const n = window.prompt('New name:', p.name); if (n?.trim() && n !== p.name) act(() => api.updateProduct(p.id, { name: n }), 'Renamed') }}>Rename</button>
                <button onClick={() => act(() => api.updateProduct(p.id, { active: !p.active }), p.active ? 'Deactivated' : 'Activated')}>{p.active ? 'Deactivate' : 'Activate'}</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

// ---------------------------------------------------------------- Rule parameters (FR-AD-06)

/** Current parameters, a form to save a new version (Admin) and the version history. */
export function RuleParametersTab({ api, editable }: { api: Api; editable: boolean }) {
  const [data, setData] = useState<{ current: ParamVersion; history: ParamVersion[]; rulesetVersion: string } | null>(null)
  const [form, setForm] = useState<ParamValues | null>(null)
  const [reason, setReason] = useState('')
  const [from, setFrom] = useState('')
  const [m, setM] = useState<Msg>(null)
  const load = useCallback(() => { api.ruleParameters().then((d) => { setData(d); setForm(d.current.values) }).catch((e: Error) => setM({ kind: 'error', text: e.message })) }, [api])
  useEffect(load, [load])
  if (!data || !form) return <MsgLine m={m} />
  const total = Object.values(form.regionWeights).reduce((a, b) => a + b, 0)
  return (
    <section className="card">
      <h3>Rule parameters — in force: v{data.current.version} (Drools ruleset {data.rulesetVersion})</h3>
      <table>
        <tbody>
          <tr><td>Constrained split weights (%)</td><td>{Object.keys(form.regionWeights).map((r) => (
            <label key={r} className="check">{r} <input className="narrow" type="number" disabled={!editable} value={form.regionWeights[r]}
              onChange={(e) => setForm({ ...form, regionWeights: { ...form.regionWeights, [r]: Number(e.target.value) } })} /></label>))}
            <span className={total === 100 ? 'muted' : 'error'}> total {total}%</span></td></tr>
          <tr><td>Constrained split basis</td><td>
            <select disabled={!editable} value={form.weightMode} onChange={(e) => setForm({ ...form, weightMode: e.target.value as ParamValues['weightMode'] })}>
              <option value="FIXED">Fixed weights above, for every product</option>
              <option value="FORECAST_SHARE">In proportion to each region's forecast</option>
              <option value="SALES_SHARE">Each product's own sales share by region</option>
            </select>
            {form.weightMode === 'SALES_SHARE' && <> over the last <input className="narrow" type="number" min={1} max={36} disabled={!editable} value={form.shareMonths}
              onChange={(e) => setForm({ ...form, shareMonths: Number(e.target.value) })} /> months (products without sales use the fixed weights)</>}
          </td></tr>
          <tr><td>Revision threshold (% change needing a reason)</td><td><input className="narrow" type="number" disabled={!editable} value={form.changeThresholdPct} onChange={(e) => setForm({ ...form, changeThresholdPct: Number(e.target.value) })} /></td></tr>
          <tr><td>Minimum region floor (units a donor keeps)</td><td><input className="narrow" type="number" disabled={!editable} value={form.minRegionFloor} onChange={(e) => setForm({ ...form, minRegionFloor: Number(e.target.value) })} /></td></tr>
          <tr><td>Spike factor (× 6-month average sales)</td><td><input className="narrow" type="number" step="0.1" disabled={!editable} value={form.spikeFactor} onChange={(e) => setForm({ ...form, spikeFactor: Number(e.target.value) })} /></td></tr>
          <tr><td>Cap constrained regions at demand</td><td><input type="checkbox" disabled={!editable} checked={form.capAtDemand} onChange={(e) => setForm({ ...form, capAtDemand: e.target.checked })} /></td></tr>
          <tr><td>Auto-activate new forecast files in draft</td><td><input type="checkbox" disabled={!editable} checked={form.autoActivateForecast} onChange={(e) => setForm({ ...form, autoActivateForecast: e.target.checked })} /></td></tr>
        </tbody>
      </table>
      {editable && (
        <p>
          <input placeholder="Reason (required)" value={reason} onChange={(e) => setReason(e.target.value)} />{' '}
          <label>Effective from <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} /></label>{' '}
          <button disabled={!reason.trim() || total !== 100} onClick={() => api.saveRuleParameters(form, reason, from ? new Date(from).toISOString() : undefined)
            .then((v) => { setM({ kind: 'ok', text: `Saved as v${v.version}` }); setReason(''); load() }).catch((e: Error) => setM({ kind: 'error', text: e.message }))}>Save new version</button>
        </p>
      )}
      <MsgLine m={m} />
      <h4>History</h4>
      <table>
        <thead><tr><th>Version</th><th>Effective from</th><th>Weights</th><th>Split basis</th><th>Threshold</th><th>Floor</th><th>Spike</th><th>Cap</th><th>By</th><th>Reason</th></tr></thead>
        <tbody>{data.history.map((h) => (
          <tr key={h.version}><td>v{h.version}</td><td>{new Date(h.effectiveFrom).toLocaleString()}</td><td>{Object.values(h.values.regionWeights).join('/')}</td>
            <td>{h.values.weightMode === 'SALES_SHARE' ? `sales share ${h.values.shareMonths}m` : 'fixed'}</td>
            <td>{h.values.changeThresholdPct}%</td><td>{h.values.minRegionFloor}</td><td>{h.values.spikeFactor}</td><td>{h.values.capAtDemand ? 'on' : 'off'}</td><td>{h.changedBy}</td><td>{h.reason}</td></tr>
        ))}</tbody>
      </table>
    </section>
  )
}

// ---------------------------------------------------------------- Audit (FR-AD-07)

/** Search, export and chain verification of the audit log. */
export function AuditTab({ api }: { api: Api }) {
  const [f, setF] = useState<AuditFilter>({})
  const [page, setPage] = useState(0)
  const [data, setData] = useState<{ total: number; rows: AuditEntry[] } | null>(null)
  const [m, setM] = useState<Msg>(null)
  const load = useCallback(() => { api.audit(f, page).then(setData).catch((e: Error) => setM({ kind: 'error', text: e.message })) }, [api, f, page])
  useEffect(load, [load])
  const field = (k: keyof AuditFilter, label: string) => <input placeholder={label} value={f[k] ?? ''} onChange={(e) => { setPage(0); setF({ ...f, [k]: e.target.value }) }} />
  return (
    <section className="card">
      <h3>Audit log</h3>
      <p>{field('actor', 'user')} {field('action', 'action')} {field('entityType', 'entity type')} {field('entityId', 'entity id')} {field('q', 'text in details')}</p>
      <p>
        <button onClick={() => api.exportAudit(f, 'xlsx').catch((e: Error) => setM({ kind: 'error', text: e.message }))}>Export xlsx</button>
        <button onClick={() => api.exportAudit(f, 'csv').catch((e: Error) => setM({ kind: 'error', text: e.message }))}>Export csv</button>
        <button onClick={() => api.verifyAudit().then((r) => setM({ kind: r.ok ? 'ok' : 'error', text: r.message })).catch((e: Error) => setM({ kind: 'error', text: e.message }))}>Verify integrity</button>
      </p>
      <MsgLine m={m} />
      <table>
        <thead><tr><th>When</th><th>User</th><th>Action</th><th>Entity</th><th>Details</th></tr></thead>
        <tbody>{data?.rows.map((r) => (
          <tr key={r.id}><td>{new Date(r.at).toLocaleString()}</td><td>{r.actor}</td><td>{r.action}</td><td>{r.entityType} {r.entityId ? <small className="muted">{r.entityId.slice(0, 8)}</small> : ''}</td>
            <td className="actions"><small>{r.detail.length > 160 ? r.detail.slice(0, 160) + '…' : r.detail}</small></td></tr>
        ))}</tbody>
      </table>
      <p className="muted"><button disabled={page === 0} onClick={() => setPage(page - 1)}>‹</button> page {page + 1} of {Math.max(1, Math.ceil((data?.total ?? 0) / 50))} ({data?.total ?? 0} entries) <button disabled={(page + 1) * 50 >= (data?.total ?? 0)} onClick={() => setPage(page + 1)}>›</button></p>
    </section>
  )
}

// ---------------------------------------------------------------- Cycles (FR-AD-08)

/** Create the next cycle, set deadlines of the selected one, reopen a FINAL cycle. */
export function CyclesTab({ api, cycle }: { api: Api; cycle: CycleState }) {
  const [month, setMonth] = useState('')
  const [review, setReview] = useState('')
  const [fin, setFin] = useState('')
  const [reason, setReason] = useState('')
  const [m, setM] = useState<Msg>(null)
  const iso = (v: string) => (v ? new Date(v).toISOString() : undefined)
  async function act(fn: () => Promise<unknown>, ok: string) {
    setM(null)
    try { await fn(); setM({ kind: 'ok', text: ok }); cycle.reload() } catch (e) { setM({ kind: 'error', text: (e as Error).message }) }
  }
  return (
    <section className="card">
      <h3>Cycles</h3>
      <p><input placeholder="YYYY-MM" value={month} onChange={(e) => setMonth(e.target.value)} />{' '}
        <button className="primary" disabled={!month} onClick={() => act(() => api.createCycle(month, iso(review), iso(fin)), `Cycle ${month} created`)}>Create cycle</button></p>
      {cycle.detail && (
        <>
          <h4>{cycle.detail.cycle.month} ({cycle.detail.cycle.state})</h4>
          <p>
            <label>Review deadline <input type="datetime-local" value={review} onChange={(e) => setReview(e.target.value)} /></label>{' '}
            <label>Finalize deadline <input type="datetime-local" value={fin} onChange={(e) => setFin(e.target.value)} /></label>{' '}
            <button onClick={() => act(() => api.setDeadlines(cycle.detail!.cycle.id, iso(review), iso(fin)), 'Deadlines saved')}>Save deadlines</button>
          </p>
          {cycle.perms?.canReopenCycle && (
            <p>
              <input placeholder="Reason (required, audited)" value={reason} onChange={(e) => setReason(e.target.value)} />{' '}
              <button disabled={!reason.trim()} onClick={() => window.confirm('Reopen this FINAL cycle? Everyone is notified.') && act(() => api.transition(cycle.detail!.cycle.id, { action: 'REOPEN_CYCLE', comment: reason }), 'Cycle reopened')}>Reopen FINAL cycle</button>
            </p>
          )}
        </>
      )}
      <MsgLine m={m} />
    </section>
  )
}

// ---------------------------------------------------------------- Users (FR-AD-09)

/** Read-only list of users, roles, regions and last login, from Keycloak. */
export function UsersTab({ api }: { api: Api }) {
  const [data, setData] = useState<{ users: KcUser[]; consoleUrl: string | null } | null>(null)
  const [m, setM] = useState<Msg>(null)
  useEffect(() => { api.users().then(setData).catch((e: Error) => setM({ kind: 'error', text: e.message })) }, [api])
  return (
    <section className="card">
      <h3>Users &amp; roles (managed in Keycloak)</h3>
      <MsgLine m={m} />
      {data?.consoleUrl && <p><a href={data.consoleUrl} target="_blank" rel="noreferrer">Open the Keycloak admin console</a></p>}
      {data && (
        <table>
          <thead><tr><th>User</th><th>Name</th><th>Email</th><th>Roles</th><th>Regions</th><th>Enabled</th><th>Last login</th></tr></thead>
          <tbody>{data.users.map((u) => (
            <tr key={u.username}><td>{u.username}</td><td>{u.name}</td><td>{u.email}</td><td>{u.roles.join(', ')}</td><td>{u.regions.join(', ')}</td>
              <td>{u.enabled ? 'yes' : 'no'}</td><td>{u.lastLogin ? new Date(u.lastLogin).toLocaleString() : '—'}</td></tr>
          ))}</tbody>
        </table>
      )}
    </section>
  )
}
