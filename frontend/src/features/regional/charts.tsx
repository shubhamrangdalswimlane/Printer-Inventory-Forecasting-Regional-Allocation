/**
 * charts.tsx - two tiny dependency-free SVG charts for the Regional Manager's sales analysis:
 * a Sparkline (trend inside a table cell) and a LineChart (the drill-down view).
 */

/**
 * A small trend line without axes.
 * @param props.values the points, oldest first
 * @param props.width  pixel width (default 90)
 * @param props.height pixel height (default 24)
 */
export function Sparkline({ values, width = 90, height = 24 }: { values: number[]; width?: number; height?: number }) {
  if (values.length < 2) return <span className="muted">—</span>
  const max = Math.max(...values, 1)
  const pts = values.map((v, i) => `${(i / (values.length - 1)) * (width - 2) + 1},${height - 2 - (v / max) * (height - 4)}`).join(' ')
  return <svg width={width} height={height} role="img" aria-label="12-month trend"><polyline points={pts} fill="none" stroke="#1a56a8" strokeWidth="1.5" /></svg>
}

/**
 * A line chart with a baseline and labelled first/last month, plus optional horizontal reference lines
 * (the published and revised forecast).
 * @param props.points the monthly points, oldest first
 * @param props.refs   reference lines to draw, e.g. {label: 'published', value: 120}
 */
export function LineChart({ points, refs }: { points: { month: string; qty: number }[]; refs: { label: string; value: number; color: string }[] }) {
  const w = 560, h = 180, pad = 28
  if (points.length < 2) return <p className="muted">Not enough history.</p>
  const max = Math.max(...points.map((p) => p.qty), ...refs.map((r) => r.value), 1)
  const x = (i: number) => pad + (i / (points.length - 1)) * (w - pad * 2)
  const y = (v: number) => h - pad - (v / max) * (h - pad * 2)
  return (
    <svg width={w} height={h} role="img" aria-label="Monthly sales">
      <line x1={pad} y1={h - pad} x2={w - pad} y2={h - pad} stroke="#9aa5b1" />
      <polyline fill="none" stroke="#1a56a8" strokeWidth="2" points={points.map((p, i) => `${x(i)},${y(p.qty)}`).join(' ')} />
      {points.map((p, i) => <circle key={p.month} cx={x(i)} cy={y(p.qty)} r="2.5" fill="#1a56a8"><title>{p.month}: {p.qty}</title></circle>)}
      {refs.map((r) => (
        <g key={r.label}>
          <line x1={pad} x2={w - pad} y1={y(r.value)} y2={y(r.value)} stroke={r.color} strokeDasharray="4 3" />
          <text x={w - pad} y={y(r.value) - 3} fontSize="10" textAnchor="end" fill={r.color}>{r.label} {r.value}</text>
        </g>
      ))}
      <text x={pad} y={h - 8} fontSize="10" fill="#5b6773">{points[0].month}</text>
      <text x={w - pad} y={h - 8} fontSize="10" textAnchor="end" fill="#5b6773">{points[points.length - 1].month}</text>
      <text x={4} y={pad} fontSize="10" fill="#5b6773">{max}</text>
    </svg>
  )
}
