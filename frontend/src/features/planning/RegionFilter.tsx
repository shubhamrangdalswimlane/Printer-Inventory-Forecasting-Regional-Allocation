/**
 * RegionFilter.tsx - the Planner's "Region" selector. It narrows the inventory-vs-forecast table, the forecast grid
 * and the allocation grid to one region (or all). It only changes what is shown: totals and the stock checks keep
 * using all regions.
 */

/**
 * @param props.regions  region codes to offer, e.g. NORTH, EAST, WEST, SOUTH
 * @param props.value    the selected region, or null for all regions
 * @param props.onChange called with the new selection (null = all regions)
 */
export default function RegionFilter({ regions, value, onChange }: { regions: string[]; value: string | null; onChange: (r: string | null) => void }) {
  return (
    <section className="card">
      <label>Region{' '}
        <select value={value ?? ''} onChange={(e) => onChange(e.target.value || null)}>
          <option value="">All regions</option>
          {regions.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </label>{' '}
      <small className="muted">Filters the inventory, forecast and allocation tables below.</small>
    </section>
  )
}
