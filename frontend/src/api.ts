/**
 * api.ts - thin client for the PIFA backend REST API.
 *
 * Every call sends the OIDC access token as a Bearer header. Errors from the server arrive as
 * RFC 9457 problem+json ({ detail, code }) and are turned into a normal JavaScript Error whose
 * message is the server's explanation, so components can simply show error.message.
 */

/** One dataset version row (mirrors DatasetRepository.VersionRow on the backend). */
export type Version = {
  id: string
  cycleId: string
  cycleMonth: string
  type: 'FORECAST_UPLOAD' | 'INVENTORY_UPLOAD'
  versionNo: number
  label: string
  source: string
  status: string
  active: boolean
  originalFilename: string | null
  sha256: string | null
  rowCount: number
  createdBy: string
  createdAt: string
  deletedBy: string | null
  deletedAt: string | null
  deleteReason: string | null
}

/** One validation finding (mirrors IngestionService.Finding). */
export type Finding = { rowNo: number; column: string | null; code: string; severity: 'ERROR' | 'WARNING'; message: string }

/** Result of an upload (mirrors IngestionService.IngestResult). */
export type IngestResult = {
  jobId: string
  status: 'VALID' | 'REJECTED'
  versionId: string | null
  label: string | null
  activated: boolean
  findings: Finding[]
}

/** One ingestion attempt (mirrors IngestionJobService.JobRow). */
export type Job = {
  id: string
  channel: string
  sourceAccount: string | null
  datasetType: string
  originalFilename: string | null
  status: string
  errorCount: number
  warningCount: number
  versionId: string | null
  createdBy: string
  startedAt: string
}

/** Difference between two versions (mirrors DatasetService.Comparison). */
export type Comparison = { added: string[]; removed: string[]; changed: { key: string; base: number; target: number }[] }

/**
 * Creates the API client for one access token.
 *
 * @param token the OIDC access token; the client is rebuilt when the token is renewed
 * @returns an object with one async function per backend operation
 */
export function createApi(token: string) {
  const auth = { Authorization: `Bearer ${token}` }

  /**
   * Sends a request and parses the JSON answer.
   * @param path   path below /api/v1, e.g. "/datasets"
   * @param init   fetch options (method, body ...)
   * @returns the parsed JSON body, or undefined for 204 No Content
   * @throws Error with the server's message when the status is not 2xx (the 422 upload answer is returned, not thrown)
   */
  async function json<T>(path: string, init: RequestInit = {}, allow422 = false): Promise<T> {
    const r = await fetch(`/api/v1${path}`, { ...init, headers: { ...auth, ...(init.headers ?? {}) } })
    if (r.status === 204) return undefined as T
    if (r.ok || (allow422 && r.status === 422)) return (await r.json()) as T
    let msg = `${r.status} ${r.statusText}`
    try { const p = await r.json(); msg = p.detail ?? msg } catch { /* body was not JSON */ }
    throw new Error(msg)
  }

  /** Sends a JSON body, used by activate / delete / undelete which need a reason. */
  const withReason = (method: string, reason: string): RequestInit => ({
    method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ reason }),
  })

  /**
   * Downloads a file through fetch (so the Authorization header is sent) and hands it to the browser
   * as a normal file download.
   * @param path     API path of the file
   * @param fallback file name used if the server sends none
   */
  async function download(path: string, fallback: string) {
    const r = await fetch(`/api/v1${path}`, { headers: auth })
    if (!r.ok) {
      let msg = `${r.status} ${r.statusText}`
      try { msg = (await r.json()).detail ?? msg } catch { /* not JSON */ }
      throw new Error(msg)
    }
    const name = /filename="?([^";]+)"?/.exec(r.headers.get('Content-Disposition') ?? '')?.[1] ?? fallback
    const url = URL.createObjectURL(await r.blob())
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  }

  return {
    /** Lists versions; Admin may include deleted ones. */
    versions: (type: Version['type'], includeDeleted = false) =>
      json<Version[]>(`/datasets?type=${type}&includeDeleted=${includeDeleted}`),
    /** Uploads a file through the same pipeline as SFTP. Rejections come back as a result, not an error. */
    upload: (kind: 'forecast' | 'inventory', file: File) => {
      const form = new FormData()
      form.append('file', file)
      return json<IngestResult>(`/datasets/${kind}/uploads`, { method: 'POST', body: form }, true)
    },
    /** Makes a version the active one. */
    activate: (id: string, reason: string) => json<void>(`/datasets/${id}/activate`, withReason('POST', reason)),
    /** Soft-deletes a version. */
    remove: (id: string, reason: string) => json<void>(`/datasets/${id}`, withReason('DELETE', reason)),
    /** Restores a soft-deleted version (Admin). */
    undelete: (id: string, reason: string) => json<void>(`/datasets/${id}/undelete`, withReason('POST', reason)),
    /** Permanently removes a soft-deleted version's data (Admin). */
    purge: (id: string) => json<void>(`/datasets/${id}/purge`, { method: 'DELETE' }),
    /** Compares two versions of the same type. */
    compare: (base: string, target: string) => json<Comparison>(`/datasets/compare?base=${base}&target=${target}`),
    /** Downloads a version as the original file, a normalised xlsx, or csv. */
    downloadVersion: (id: string, format: 'original' | 'xlsx' | 'csv', label: string) =>
      download(`/datasets/${id}/download?format=${format}`, `${label}.${format === 'original' ? 'xlsx' : format}`),
    /** Lists recent ingestion attempts for the caller's role. */
    jobs: () => json<Job[]>('/ingestion-jobs?limit=20'),
    /** Downloads the CSV error report of a job. */
    errorReport: (jobId: string) => download(`/ingestion-jobs/${jobId}/error-report`, `errors-${jobId}.csv`),
    /** Downloads the blank Excel template. */
    template: (kind: 'forecast' | 'inventory') => download(`/templates/${kind}`, `${kind}_template.xlsx`),
  }
}

/** The client type, for component props. */
export type Api = ReturnType<typeof createApi>
