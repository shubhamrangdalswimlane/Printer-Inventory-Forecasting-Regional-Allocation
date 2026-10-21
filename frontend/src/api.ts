/**
 * api.ts - thin client for the PIFA backend REST API.
 *
 * Every call sends the OIDC access token as a Bearer header. Errors from the server arrive as
 * RFC 9457 problem+json ({ detail, code, ... }) and are turned into an {@link ApiError}: its message is
 * the server's explanation (so components can show error.message) and its `code` / `body` allow
 * special handling, for example the 409 CONFLICT of an optimistic-lock failure.
 */

/** An error answer from the backend. */
export class ApiError extends Error {
  /** @param status HTTP status @param code stable server error code @param message explanation @param body the full problem+json body */
  constructor(public status: number, public code: string, message: string, public body: Record<string, unknown>) {
    super(message)
  }
}

/** One dataset version row (mirrors DatasetRepository.VersionRow on the backend). */
export type Version = {
  id: string
  cycleId: string
  cycleMonth: string
  type: 'FORECAST_UPLOAD' | 'INVENTORY_UPLOAD' | string
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
  scope: string | null
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

/** A planning cycle in the selector. */
export type CycleSummary = { id: string; month: string; state: string }

/** Progress of one region inside a cycle. */
export type RegionStatus = { region: string; reviewStatus: 'OPEN' | 'SUBMITTED'; finalizeStatus: string; actor: string | null; updatedAt: string }

/** A cycle with its regions' progress (mirrors CycleWorkflowService.CycleDetail). */
export type CycleDetail = {
  cycle: { id: string; month: string; state: string; rowVersion: number; reviewDeadline: string | null; finalizeDeadline: string | null }
  regions: RegionStatus[]
}

/** What the caller may do in the cycle right now (mirrors EditPolicy.permissions). */
export type Perms = {
  state: string
  canEditPublished: boolean
  editableRegions: string[]
  canPublish: boolean
  canRecall: boolean
  submittableRegions: string[]
  canProceed: boolean
  canReturnRegions: boolean
  changeThresholdPct: number
}

/** One cell of the forecast grid (mirrors ForecastQueryService.Cell). */
export type Cell = { lineId: string; uploaded: number; published: number; revised: number | null; reason: string | null; rowVersion: number }

/** One product row: a cell per region. */
export type GridRow = { productId: string; product: string; regions: Record<string, Cell> }

/** A page of grid rows. */
export type GridPage = { total: number; rows: GridRow[] }

/** One requested cell edit (mirrors ForecastEditService.EditRequest). */
export type EditReq = { lineId: string; field: 'PUBLISHED' | 'REVISED'; value: number; reason: string | null; rowVersion: number }

/** A validation finding of the working copy. */
export type ValidationFinding = { code: string; severity: 'ERROR' | 'WARNING'; column: string; message: string }

/** A transition request (mirrors CycleWorkflowService.TransitionRequest). */
export type Transition = {
  action: 'PUBLISH' | 'RECALL' | 'SUBMIT_REGION' | 'PROCEED' | 'RETURN_REGIONS'
  region?: string
  regions?: string[]
  comment?: string
  rowVersion?: number
  reviewDeadline?: string
}

/** One product's sales analysis (mirrors SalesAnalysisService.Row). */
export type SalesRow = {
  productId: string; product: string; avg3: number; avg6: number; avg12: number; avg24: number
  sameMonthLastYear: number | null; series: number[]; published: number; revised: number | null
}

/** A monthly sales point. */
export type SalesPoint = { month: string; qty: number }

/** An in-app notification. */
export type Notice = { id: number; title: string; body: string | null; cycleId: string | null; createdAt: string; unread: boolean }

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
   * @param path     path below /api/v1, e.g. "/datasets"
   * @param init     fetch options (method, body ...)
   * @param allow422 return a 422 body as a normal result instead of throwing (used by file upload)
   * @returns the parsed JSON body, or undefined for 204 No Content
   * @throws ApiError with the server's message when the status is not 2xx
   */
  async function json<T>(path: string, init: RequestInit = {}, allow422 = false): Promise<T> {
    const r = await fetch(`/api/v1${path}`, { ...init, headers: { ...auth, ...(init.headers ?? {}) } })
    if (r.status === 204) return undefined as T
    if (r.ok || (allow422 && r.status === 422)) return (await r.json()) as T
    let body: Record<string, unknown> = {}
    try { body = await r.json() } catch { /* body was not JSON */ }
    throw new ApiError(r.status, String(body.code ?? ''), String(body.detail ?? `${r.status} ${r.statusText}`), body)
  }

  /** Sends a JSON body with the given method. */
  const send = (method: string, body: unknown): RequestInit => ({
    method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
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
    // ---- datasets (Phase 1)
    /** Lists versions; Admin may include deleted ones. */
    versions: (type: string, includeDeleted = false) => json<Version[]>(`/datasets?type=${type}&includeDeleted=${includeDeleted}`),
    /** Uploads a file through the same pipeline as SFTP. Rejections come back as a result, not an error. */
    upload: (kind: 'forecast' | 'inventory', file: File) => {
      const form = new FormData()
      form.append('file', file)
      return json<IngestResult>(`/datasets/${kind}/uploads`, { method: 'POST', body: form }, true)
    },
    /** Makes a version the active one. */
    activate: (id: string, reason: string) => json<void>(`/datasets/${id}/activate`, send('POST', { reason })),
    /** Soft-deletes a version. */
    remove: (id: string, reason: string) => json<void>(`/datasets/${id}`, send('DELETE', { reason })),
    /** Restores a soft-deleted version (Admin). */
    undelete: (id: string, reason: string) => json<void>(`/datasets/${id}/undelete`, send('POST', { reason })),
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

    // ---- cycles and forecast workflow (Phase 2)
    /** Lists all cycles, newest month first. */
    cycles: () => json<CycleSummary[]>('/cycles'),
    /** One cycle with its per-region progress. */
    cycle: (id: string) => json<CycleDetail>(`/cycles/${id}`),
    /** What the caller may do now (which buttons and cells to enable). */
    permissions: (id: string) => json<Perms>(`/cycles/${id}/permissions`),
    /** Performs a workflow transition (publish, recall, submit, proceed, return). */
    transition: (id: string, t: Transition) => json<CycleDetail>(`/cycles/${id}/transitions`, send('POST', t)),
    /** One page of the forecast grid. */
    grid: (id: string, q: string, page: number, size = 50) =>
      json<GridPage>(`/cycles/${id}/forecast?q=${encodeURIComponent(q)}&page=${page}&size=${size}`),
    /** Applies cell edits (all or nothing). A stale row gives ApiError code CONFLICT. */
    editLines: (id: string, edits: EditReq[]) => json<unknown>(`/cycles/${id}/forecast/lines`, send('PATCH', { edits })),
    /** Validation findings of the Forecaster working copy. */
    validation: (id: string) => json<ValidationFinding[]>(`/cycles/${id}/forecast/validation`),
    /** Sales analysis of one region. */
    salesAnalysis: (id: string, region: string) => json<SalesRow[]>(`/cycles/${id}/sales-analysis?region=${region}`),
    /** Monthly sales of one product in one region (drill-down chart). */
    salesHistory: (productId: string, region: string) => json<SalesPoint[]>(`/sales-history?productId=${productId}&region=${region}&months=24`),

    // ---- notifications
    /** Newest notifications visible to the caller. */
    notifications: () => json<Notice[]>('/notifications?limit=20'),
    /** Marks one notification as read. */
    markRead: (id: number) => json<void>(`/notifications/${id}/read`, { method: 'POST' }),
  }
}

/** The client type, for component props. */
export type Api = ReturnType<typeof createApi>
