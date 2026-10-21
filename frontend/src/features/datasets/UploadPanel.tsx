/**
 * UploadPanel.tsx - manual upload of a forecast or inventory Excel file (PRD FR-FC-02 / FR-PL-01).
 *
 * The file goes through the same backend pipeline as an SFTP drop. The panel shows the live result:
 * either the new version label, or the list of errors (with a link to the CSV error report).
 */
import { useRef, useState } from 'react'
import type { Api, IngestResult } from '../../api'

/**
 * @param props.api      API client
 * @param props.kind     'forecast' (Forecaster) or 'inventory' (Planner)
 * @param props.onDone   called after every upload attempt so the lists can refresh
 */
export default function UploadPanel({ api, kind, onDone }: { api: Api; kind: 'forecast' | 'inventory'; onDone: () => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<IngestResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  /** Sends the chosen file to the backend and stores the result for display. */
  async function send(file: File | undefined) {
    if (!file) return
    setBusy(true); setError(null); setResult(null)
    try {
      setResult(await api.upload(kind, file))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
      if (input.current) input.current.value = '' // allow choosing the same file again
      onDone()
    }
  }

  return (
    <section className="card">
      <h3>Upload {kind} file</h3>
      <p className="muted">Excel (.xlsx), max 10 MB. Same validation as SFTP.{' '}
        <button className="link" onClick={() => api.template(kind).catch((e) => setError(e.message))}>Download blank template</button>
      </p>
      <input ref={input} aria-label="Choose a file to upload" type="file" accept=".xlsx" disabled={busy} onChange={(e) => send(e.target.files?.[0])} />
      {busy && <p>Validating…</p>}
      {error && <p className="error">{error}</p>}
      {result?.status === 'VALID' && (
        <p className="ok">Accepted as <b>{result.label}</b>{result.activated ? ' and activated' : ' (not activated: cycle is no longer editable)'}.</p>
      )}
      {result?.status === 'REJECTED' && (
        <div>
          <p className="error">File rejected — nothing was loaded.{' '}
            <button className="link" onClick={() => api.errorReport(result.jobId).catch((e) => setError(e.message))}>Download error report</button>
          </p>
        </div>
      )}
      {result && result.findings.length > 0 && (
        <table>
          <thead><tr><th>Severity</th><th>Code</th><th>Row</th><th>Column</th><th>Message</th></tr></thead>
          <tbody>
            {result.findings.slice(0, 50).map((f, i) => (
              <tr key={i} className={f.severity === 'ERROR' ? 'row-error' : 'row-warning'}>
                <td>{f.severity}</td><td>{f.code}</td><td>{f.rowNo || '—'}</td><td>{f.column ?? '—'}</td><td>{f.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {result && result.findings.length > 50 && <p className="muted">Showing the first 50 of {result.findings.length} findings; download the report for all.</p>}
    </section>
  )
}
