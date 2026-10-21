/**
 * CycleBar.tsx - cycle selector and status banner shown at the top of every dashboard
 * (PRD FR-COM-02): which cycle, its state, the review deadline and each region's progress.
 */
import type { CycleState } from './useCycle'

/** Human-friendly names of the cycle states. */
const STATE_LABEL: Record<string, string> = {
  FORECAST_DRAFT: 'Forecast draft',
  REGIONAL_REVIEW: 'Regional review',
  PLANNING: 'Planning',
  REGIONAL_FINALIZATION: 'Regional finalization',
  FINAL: 'Final',
}

/** @param props.cycle the state returned by useCycle() */
export default function CycleBar({ cycle }: { cycle: CycleState }) {
  const { cycles, selectedId, select, detail, error } = cycle
  if (error) return <p className="error">{error}</p>
  if (cycles.length === 0) return <section className="card"><p className="muted">No planning cycle yet. A forecast upload creates one.</p></section>
  const c = detail?.cycle
  return (
    <section className="card banner">
      <label>Cycle{' '}
        <select value={selectedId ?? ''} onChange={(e) => select(e.target.value)}>
          {cycles.map((x) => <option key={x.id} value={x.id}>{x.month}</option>)}
        </select>
      </label>
      {c && <span className={`pill state-${c.state}`}>{STATE_LABEL[c.state] ?? c.state}</span>}
      {c?.reviewDeadline && <span className="muted">Review deadline: {new Date(c.reviewDeadline).toLocaleString()}</span>}
      {detail && detail.regions.length > 0 && (
        <span className="regions">
          {detail.regions.map((r) => (
            <span key={r.region} className={`pill ${r.reviewStatus === 'SUBMITTED' ? 'ok-pill' : 'open-pill'}`} title={r.actor ? `${r.actor} ${new Date(r.updatedAt).toLocaleString()}` : ''}>
              {r.region}: {r.reviewStatus === 'SUBMITTED' ? 'submitted' : 'open'}
            </span>
          ))}
        </span>
      )}
    </section>
  )
}
