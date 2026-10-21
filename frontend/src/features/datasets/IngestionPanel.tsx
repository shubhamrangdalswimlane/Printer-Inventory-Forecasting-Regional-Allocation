/**
 * IngestionPanel.tsx - recent ingestion attempts (SFTP, UI, synthetic) with status and a link to the
 * error report of rejected files (PRD FR-FC-01 / FR-AD-04).
 */
import { useEffect, useState } from 'react'
import type { Api, Job } from '../../api'

/**
 * @param props.api        API client
 * @param props.refreshKey change this value to reload the list (for example after an upload)
 */
export default function IngestionPanel({ api, refreshKey }: { api: Api; refreshKey: number }) {
  const [jobs, setJobs] = useState<Job[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.jobs().then(setJobs).catch((e: Error) => setError(e.message))
  }, [api, refreshKey])

  return (
    <section className="card">
      <h3>Recent ingestion</h3>
      {error && <p className="error">{error}</p>}
      <table>
        <thead><tr><th>When</th><th>Channel</th><th>File</th><th>Status</th><th>Errors</th><th>Warnings</th><th>Report</th></tr></thead>
        <tbody>
          {jobs.map((j) => (
            <tr key={j.id} className={j.status === 'REJECTED' ? 'row-error' : ''}>
              <td>{new Date(j.startedAt).toLocaleString()}</td>
              <td>{j.channel}{j.sourceAccount ? ` (${j.sourceAccount})` : ''}</td>
              <td>{j.originalFilename ?? '—'}</td>
              <td>{j.status}</td>
              <td>{j.errorCount}</td>
              <td>{j.warningCount}</td>
              <td>{(j.errorCount > 0 || j.warningCount > 0) && <button onClick={() => api.errorReport(j.id).catch((e) => setError(e.message))}>CSV</button>}</td>
            </tr>
          ))}
          {jobs.length === 0 && <tr><td colSpan={7} className="muted">No ingestion yet.</td></tr>}
        </tbody>
      </table>
    </section>
  )
}
