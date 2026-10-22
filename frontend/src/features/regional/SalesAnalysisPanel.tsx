/**
 * SalesAnalysisPanel.tsx - historical sales for the Regional Manager's region (PRD FR-RM-03):
 * 3/6/12/24-month averages, same month last year, a 12-month sparkline, and the published and revised
 * forecast next to them. Clicking a product opens a 24-month drill-down chart.
 */
import { useEffect, useState } from 'react'
import type { Api, SalesPoint, SalesRow } from '../../api'
import { LineChart, Sparkline } from './charts'

/**
 * @param props.api        API client
 * @param props.cycleId    the cycle (supplies the forecast numbers and "same month last year")
 * @param props.regions    regions the user may analyse (a manager can own several)
 * @param props.refreshKey change to reload after revisions are saved
 */
export default function SalesAnalysisPanel({ api, cycleId, regions, refreshKey }: { api: Api; cycleId: string; regions: string[]; refreshKey: number }) {
  const [region, setRegion] = useState(regions[0] ?? 'NORTH')
  const [rows, setRows] = useState<SalesRow[]>([])
  const [drill, setDrill] = useState<{ row: SalesRow; points: SalesPoint[] } | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setDrill(null)
    api.salesAnalysis(cycleId, region).then((r) => { setRows(r); setError(null) }).catch((e: Error) => setError(e.message))
  }, [api, cycleId, region, refreshKey])

  /** Loads the 24-month history of one product for the chart. */
  function open(row: SalesRow) {
    api.salesHistory(row.productId, region).then((points) => setDrill({ row, points })).catch((e: Error) => setError(e.message))
  }

  const f = (n: number) => Math.round(n).toLocaleString()
  return (
    <section className="card">
      <h3>Sales analysis — {region}</h3>
      {regions.length > 1 && (
        <p><label>Region{' '}<select value={region} onChange={(e) => setRegion(e.target.value)}>{regions.map((r) => <option key={r}>{r}</option>)}</select></label></p>
      )}
      {error && <p className="error">{error}</p>}
      <div className="grid-scroll" tabIndex={0} role="region" aria-label="Sales analysis table">
        <table className="grid">
          <thead><tr><th className="sticky">Product</th><th>Avg 3m</th><th>Avg 6m</th><th>Avg 12m</th><th>Avg 24m</th><th>Same month LY</th><th>Trend (12m)</th><th>Published</th><th>Revised</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.productId} className="clickable" onClick={() => open(r)}>
                <td className="sticky">{r.product}</td>
                <td>{f(r.avg3)}</td><td>{f(r.avg6)}</td><td>{f(r.avg12)}</td><td>{f(r.avg24)}</td>
                <td>{r.sameMonthLastYear ?? '—'}</td>
                <td><Sparkline values={r.series} /></td>
                <td>{r.published}</td>
                <td className={r.revised !== null && r.revised !== r.published ? 'changed' : ''}>{r.revised ?? '—'}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={9} className="muted">No data.</td></tr>}
          </tbody>
        </table>
      </div>
      {drill && (
        <div>
          <h4>{drill.row.product} — monthly sales, {region}</h4>
          <LineChart points={drill.points} refs={[
            { label: 'published', value: drill.row.published, color: '#7a8794' },
            ...(drill.row.revised !== null ? [{ label: 'revised', value: drill.row.revised, color: '#b36b00' }] : []),
          ]} />
          <p><button onClick={() => setDrill(null)}>Close</button></p>
        </div>
      )}
      <p className="muted">Click a product to see its 24-month history.</p>
    </section>
  )
}
