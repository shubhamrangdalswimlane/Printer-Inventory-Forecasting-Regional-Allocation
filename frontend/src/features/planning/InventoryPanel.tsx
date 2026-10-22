/**
 * InventoryPanel.tsx - "Inventory vs forecast" table for the Planner, shown in every cycle stage.
 *
 * Until the cycle reaches Planning, the allocation grid (which has the Available / Reserve columns) is not shown, so
 * after uploading an inventory file the Planner would see nothing. This panel closes that gap: it reads the cycle's
 * ACTIVE inventory file and puts each model's available stock next to its total forecast (the revised quantity when
 * there is one, otherwise the published/working quantity), with the gap and coverage, sorted by the biggest shortage.
 *
 * Region filter: stock is held centrally (one number per model, not per region), so choosing a region shows that
 * region's forecast, its share of the model's total forecast, and the stock it would get if the model's stock were
 * split in proportion to the forecast ("as per forecast"). The Gap and Coverage columns keep comparing total stock
 * with the total forecast, because that decides whether a model is constrained.
 */
import { useEffect, useMemo, useState } from 'react'
import type { Api } from '../../api'

/** One table row. */
type Row = { product: string; stock: number; forecast: number; regionForecast: number }

/**
 * @param props.api        API client
 * @param props.cycleId    the selected cycle
 * @param props.refreshKey changes whenever the cycle or its data changed, to reload
 * @param props.region     region to focus on (null/undefined = all regions)
 */
export default function InventoryPanel({ api, cycleId, refreshKey, region }: { api: Api; cycleId: string; refreshKey: number; region?: string | null }) {
  const [rows, setRows] = useState<Row[] | null>(null)
  const [label, setLabel] = useState<string | null>(null)
  const [asOf, setAsOf] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [onlyShort, setOnlyShort] = useState(false)

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const versions = await api.versions('INVENTORY_UPLOAD')
        const active = versions.find((v) => v.cycleId === cycleId && v.active)
        if (!active) { if (!cancelled) { setRows([]); setLabel(null) } return }
        const [lines, grid] = await Promise.all([api.datasetLines(active.id), api.grid(cycleId, '', 0, 500)])
        const forecast = new Map<string, { total: number; byRegion: Record<string, number> }>()
        for (const g of grid.rows) {
          const byRegion: Record<string, number> = {}
          for (const [r, c] of Object.entries(g.regions)) byRegion[r] = c.revised ?? c.published
          forecast.set(g.product, { total: Object.values(byRegion).reduce((s, n) => s + n, 0), byRegion })
        }
        const out: Row[] = lines.map((l) => {
          const f = forecast.get(String(l.product))
          return { product: String(l.product), stock: Number(l.availableQty), forecast: f?.total ?? 0, regionForecast: region ? f?.byRegion[region] ?? 0 : f?.total ?? 0 }
        })
        if (!cancelled) {
          setRows(out)
          setLabel(active.label)
          setAsOf(lines[0]?.asOfDate ? String(lines[0].asOfDate) : null)
          setError(null)
        }
      } catch (e) {
        if (!cancelled) setError((e as Error).message)
      }
    })()
    return () => { cancelled = true }
  }, [api, cycleId, refreshKey, region])

  /** Rows ordered by gap (stock - forecast), most short first; optionally only the short ones. */
  const shown = useMemo(() => {
    const list = (rows ?? []).filter((r) => !onlyShort || r.stock < r.forecast)
    return [...list].sort((a, b) => (a.stock - a.forecast) - (b.stock - b.forecast) || a.product.localeCompare(b.product))
  }, [rows, onlyShort])

  if (error) return <section className="card"><p className="error">{error}</p></section>
  if (!rows) return null
  if (rows.length === 0) return <section className="card"><h3>Inventory vs forecast</h3><p className="muted">No inventory file is active for this cycle yet. Upload one below.</p></section>

  const totalStock = rows.reduce((s, r) => s + r.stock, 0)
  const totalForecast = rows.reduce((s, r) => s + r.forecast, 0)
  const short = rows.filter((r) => r.stock < r.forecast).length

  return (
    <section className="card">
      <h3>Inventory vs forecast</h3>
      <p className="muted">
        {label}{asOf ? `, stock as of ${asOf}` : ''}. {rows.length} products, stock {totalStock.toLocaleString()} vs forecast {totalForecast.toLocaleString()};{' '}
        <b>{short}</b> product(s) have less stock than forecast and will be constrained.
      </p>
      <p><label className="check"><input type="checkbox" checked={onlyShort} onChange={(e) => setOnlyShort(e.target.checked)} /> only products with less stock than forecast</label></p>
      <table>
        <thead><tr>
          <th>Product</th><th className="num">Available stock</th>
          {region && <><th className="num">{region} forecast</th><th className="num">{region} share</th><th className="num">{region} stock if split by forecast</th></>}
          <th className="num">Forecast (all regions)</th><th className="num">Gap</th><th className="num">Coverage</th><th>Outlook</th>
        </tr></thead>
        <tbody>
          {shown.map((r) => {
            const gap = r.stock - r.forecast
            return (
              <tr key={r.product}>
                <td>{r.product}</td>
                <td className="num">{r.stock.toLocaleString()}</td>
                {region && <>
                  <td className="num">{r.regionForecast.toLocaleString()}</td>
                  <td className="num">{r.forecast === 0 ? '—' : `${Math.round((r.regionForecast / r.forecast) * 100)}%`}</td>
                  <td className="num">{r.forecast === 0 ? '—' : Math.round(Math.min(r.stock, r.forecast) * r.regionForecast / r.forecast).toLocaleString()}</td>
                </>}
                <td className="num">{r.forecast.toLocaleString()}</td>
                <td className="num">{gap > 0 ? `+${gap}` : gap}</td>
                <td className="num">{r.forecast === 0 ? '—' : `${Math.round((r.stock / r.forecast) * 100)}%`}</td>
                <td><span className={`pill ${gap < 0 ? 'open-pill' : 'ok-pill'}`}>{gap < 0 ? 'Constrained' : 'Sufficient'}</span></td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </section>
  )
}
