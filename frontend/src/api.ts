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
  canRunAllocation: boolean
  canEditAllocation: boolean
  canEditReserve: boolean
  canApproveAllocation: boolean
  finalizableRegions: string[]
  unfinalizableRegions: string[]
  canCreateTransfer: boolean
  canDecideAnyTransfer: boolean
  canReopenAllocation: boolean
  canReopenCycle: boolean
}

/** One cell of the forecast grid (mirrors ForecastQueryService.Cell). */
export type Cell = { lineId: string; uploaded: number; published: number; revised: number | null; reason: string | null; rowVersion: number }

/** One product row: a cell per region. */
export type GridRow = { productId: string; product: string; regions: Record<string, Cell> }

/** A page of grid rows. */
export type GridPage = { total: number; rows: GridRow[] }

/** One requested cell edit (mirrors ForecastEditService.EditRequest). */
export type EditReq = { lineId: string; field: 'PUBLISHED' | 'REVISED' | 'APPROVED'; value: number; reason: string | null; rowVersion: number }

/** A validation finding of the working copy. */
export type ValidationFinding = { code: string; severity: 'ERROR' | 'WARNING'; column: string; message: string }

/** A transition request (mirrors CycleWorkflowService.TransitionRequest). */
export type Transition = {
  action: 'PUBLISH' | 'RECALL' | 'SUBMIT_REGION' | 'PROCEED' | 'RETURN_REGIONS' | 'APPROVE_ALLOCATION' | 'REOPEN_ALLOCATION' | 'FINALIZE_REGION' | 'UNFINALIZE_REGION' | 'REOPEN_CYCLE'
  region?: string
  regions?: string[]
  comment?: string
  rowVersion?: number
  reviewDeadline?: string
  finalizeDeadline?: string
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

/** A cell of the allocation grid (mirrors AllocationService.Cell). */
export type AllocCell = { lineId: string; demand: number; proposed: number | null; approved: number | null; finalQty: number | null; rowVersion: number }

/** Stock of a product in the cycle (mirrors AllocationService.Stock). */
export type Stock = { available: number; reserve: number; reserveReason: string | null; allocatable: number; classification: string | null; explanation: string | null; unallocated: number | null; rowVersion: number }

/** One product row of the allocation grid. */
export type AllocRow = { productId: string; product: string; stock: Stock; regions: Record<string, AllocCell> }

/** A page of the allocation grid plus the cycle summary. */
export type AllocPage = {
  total: number; rows: AllocRow[]
  summary: { products: number; constrained: number; allocatable: number; demand: number; approved: number; proposalAt: string | null; rulesetVersion: string | null; parameterVersion: number | null; stale: boolean }
}

/** Result of a proposal run or preview (mirrors AllocationService.ProposalResult). */
export type Proposal = {
  saved: boolean; products: number; constrained: number; sufficient: number; changedCells: number
  changes: { productId: string; product: string; region: string; before: number | null; after: number }[]
  rulesetVersion: string; parameterVersion: number
}

/** An allocation finding (BR-VA-xx). */
export type AllocFinding = { code: string; severity: 'ERROR' | 'WARNING'; productId: string; region: string | null; message: string }

/** Rule parameter values (mirrors RuleParameterService.Values). */
export type ParamValues = {
  regionWeights: Record<string, number>; changeThresholdPct: number; minRegionFloor: number; spikeFactor: number; capAtDemand: boolean; autoActivateForecast: boolean
  /** Phase 7: FIXED = regionWeights for every product; SALES_SHARE = each product's sales share over shareMonths (fixed weights when it has no sales). */
  weightMode: 'FIXED' | 'SALES_SHARE'; shareMonths: number
}

/** One stored rule-parameter version. */
export type ParamVersion = { version: number; values: ParamValues; effectiveFrom: string; changedBy: string; reason: string | null; createdAt: string }

/** A transfer between regions (mirrors TransferService.TransferRow). */
export type TransferRow = {
  id: string; productId: string; product: string; fromRegion: string; toRegion: string; qty: number; kind: 'REQUEST' | 'OFFER' | 'DIRECT'
  status: 'REQUESTED' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED'; reason: string | null; requestedBy: string; requestedAt: string
  decidedBy: string | null; decidedAt: string | null; decisionComment: string | null
}

/** A product in the master data (mirrors MasterDataAdminService.ProductRow). */
export type MasterProduct = { id: string; name: string; code: string | null; active: boolean; source: string; aliases: { id: string; alias: string }[] }

/** An audit entry. */
export type AuditEntry = { id: number; at: string; actor: string; action: string; entityType: string; entityId: string | null; detail: string }

/** Audit search filters. */
export type AuditFilter = { actor?: string; action?: string; entityType?: string; entityId?: string; q?: string }

/** A user from Keycloak (read-only view). */
export type KcUser = { username: string; name: string; email: string; enabled: boolean; roles: string[]; regions: string[]; lastLogin: string | null }

// ---- go-live (Phase 6)

/** One go-live checklist line. item = number in the go-live checklist (0 = technical prerequisite). */
export interface ReadinessCheck { key: string; item: number; title: string; status: 'PASS' | 'WARN' | 'FAIL' | 'MANUAL'; detail: string }
/** The readiness report. */
export interface Readiness { overall: 'PASS' | 'WARN' | 'FAIL'; checks: ReadinessCheck[]; at: string }
/** A suggested master product for an unmatched name. */
export interface NameSuggestion { productId: string; name: string; score: number }
/** Product matching report for one file. */
export interface MatchingReport {
  fileName: string; distinctNames: number; exact: number; alias: number; ok: boolean
  problems: { name: string; match: 'INACTIVE' | 'UNKNOWN'; occurrences: number; rows: number[]; suggestions: NameSuggestion[] }[]
}
/** Sales-history import outcome; problems not empty = nothing imported. */
export interface SalesImport { imported: number; products: number; firstMonth: string | null; lastMonth: string | null; problems: string[] }
/** Parallel-run reconciliation. */
export interface Reconciliation {
  cycleMonth: string; pifaStage: string; cellsCompared: number; matching: number; pifaTotal: number; legacyTotal: number
  differences: { product: string; region: string; pifa: number; legacy: number; diff: number }[]
  differenceCount: number; onlyInFile: string[]; onlyInPifa: string[]; ok: boolean
}

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

  /** @returns a multipart form with the file in field "file" */
  function formWith(file: File) {
    const form = new FormData()
    form.append('file', file)
    return form
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

    // ---- planning and allocation (Phase 3)
    /** One page of the demand-vs-stock / allocation grid. */
    allocation: (id: string, q: string, constrained: boolean, page: number, size = 50) =>
      json<AllocPage>(`/cycles/${id}/allocation?q=${encodeURIComponent(q)}&constrained=${constrained}&page=${page}&size=${size}`),
    /** What a re-run would change, without saving. */
    previewAllocation: (id: string) => json<Proposal>(`/cycles/${id}/allocation/preview`, { method: 'POST' }),
    /** Runs the Drools allocation and stores it (replaces the Planner's edits). */
    runAllocation: (id: string) => json<Proposal>(`/cycles/${id}/allocation/proposal`, { method: 'POST' }),
    /** Allocation findings of a stage. */
    allocationViolations: (id: string, stage: 'APPROVED' | 'FINAL') => json<AllocFinding[]>(`/cycles/${id}/allocation/violations?stage=${stage}`),
    /** Sets a product's reserve. */
    setReserve: (id: string, productId: string, reserveQty: number, reason: string, rowVersion: number) =>
      json<{ rowVersion: number }>(`/cycles/${id}/stock/${productId}`, send('PATCH', { reserveQty, reason, rowVersion })),
    /** Downloads an allocation export (all regions or one region). */
    exportAllocation: (id: string, stage: 'proposed' | 'approved' | 'final', region?: string) =>
      download(`/cycles/${id}/allocation/export?stage=${stage}${region ? `&region=${region}` : ''}`, `allocation_${stage}.xlsx`),
    /** Rule parameters: current, history and ruleset version (Planner, Admin). */
    ruleParameters: () => json<{ current: ParamVersion; history: ParamVersion[]; rulesetVersion: string }>('/rule-parameters'),
    /** Saves a new rule-parameter version (Admin). */
    saveRuleParameters: (values: ParamValues, reason: string, effectiveFrom?: string) =>
      json<ParamVersion>('/rule-parameters', send('POST', { values, reason, effectiveFrom: effectiveFrom || null })),

    // ---- transfers (Phase 4)
    /** Transfers of a cycle, newest first. */
    transfers: (id: string, status?: string) => json<TransferRow[]>(`/cycles/${id}/transfers${status ? `?status=${status}` : ''}`),
    /** Requests, offers or (owning both regions) directly applies a transfer. */
    createTransfer: (id: string, body: { productId: string; fromRegion: string; toRegion: string; qty: number; reason?: string }) =>
      json<{ transfer: TransferRow; warnings: string[] }>(`/cycles/${id}/transfers`, send('POST', body)),
    /** Accepts a pending transfer (the units move). */
    acceptTransfer: (tid: string, comment?: string) => json<{ transfer: TransferRow; warnings: string[] }>(`/transfers/${tid}/accept`, send('POST', { comment: comment ?? null })),
    /** Rejects a pending transfer. */
    rejectTransfer: (tid: string, comment?: string) => json<{ transfer: TransferRow }>(`/transfers/${tid}/reject`, send('POST', { comment: comment ?? null })),
    /** Withdraws one's own pending transfer. */
    cancelTransfer: (tid: string) => json<{ transfer: TransferRow }>(`/transfers/${tid}/cancel`, { method: 'POST' }),

    // ---- admin (Phase 4)
    /** Overview of a cycle: progress, open issues, system health. */
    overview: (cycleId: string) => json<Overview>(`/admin/overview?cycleId=${cycleId}`),
    /** Analytics across cycles. */
    analytics: (from?: string, to?: string) => json<CycleAnalytics[]>(`/admin/analytics?${from ? `from=${from}&` : ''}${to ? `to=${to}` : ''}`),
    /** Products with aliases. */
    products: (q: string, includeInactive: boolean) => json<MasterProduct[]>(`/admin/products?q=${encodeURIComponent(q)}&includeInactive=${includeInactive}`),
    /** Adds a product. */
    addProduct: (name: string) => json<{ id: string }>('/admin/products', send('POST', { name })),
    /** Renames, (de)activates or sets the Product_code of a product (code "" removes it). */
    updateProduct: (pid: string, body: { name?: string; active?: boolean; code?: string }) => json<void>(`/admin/products/${pid}`, send('PATCH', body)),
    /** Adds an alias. */
    addAlias: (pid: string, name: string) => json<{ id: string }>(`/admin/products/${pid}/aliases`, send('POST', { name })),
    /** Removes an alias. */
    removeAlias: (pid: string, aid: string) => json<void>(`/admin/products/${pid}/aliases/${aid}`, { method: 'DELETE' }),
    /** Bulk product import from Excel. */
    importProducts: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      return json<{ created: number; updated: number; aliasesAdded: number; problems: string[] }>('/admin/products/import', { method: 'POST', body: form })
    },
    /** Audit search. */
    audit: (f: AuditFilter, page: number) => json<{ total: number; rows: AuditEntry[] }>(`/admin/audit?${auditQuery(f)}&page=${page}&size=50`),
    /** Audit export. */
    exportAudit: (f: AuditFilter, format: 'csv' | 'xlsx') => download(`/admin/audit/export?${auditQuery(f)}&format=${format}`, `audit.${format}`),
    /** Verifies the audit hash chain now. */
    verifyAudit: () => json<{ ok: boolean; rows: number; recomputed: number; brokenAtId: number | null; message: string }>('/admin/audit/verify', { method: 'POST' }),
    /** Creates a planning cycle. */
    createCycle: (month: string, reviewDeadline?: string, finalizeDeadline?: string) =>
      json<{ id: string }>('/admin/cycles', send('POST', { month, reviewDeadline: reviewDeadline || null, finalizeDeadline: finalizeDeadline || null })),
    /** Sets a cycle's deadlines. */
    setDeadlines: (cycleId: string, reviewDeadline?: string, finalizeDeadline?: string) =>
      json<void>(`/admin/cycles/${cycleId}/deadlines`, send('PATCH', { reviewDeadline: reviewDeadline || null, finalizeDeadline: finalizeDeadline || null })),
    /** Users from Keycloak (read-only). */
    users: () => json<{ users: KcUser[]; consoleUrl: string | null }>('/admin/users'),

    // ---- go-live tools (Phase 6)
    /** Go-live readiness: every checklist item with PASS / WARN / FAIL / MANUAL. */
    readiness: () => json<Readiness>('/admin/go-live/readiness'),
    /** Matching report: which product names in a real file match the master. */
    matchingReport: (file: File) => json<MatchingReport>('/admin/go-live/matching-report', { method: 'POST', body: formWith(file) }),
    /** Imports real sales history; problems come back as a result (422), not an error. */
    importSales: (file: File) => json<SalesImport>('/admin/go-live/sales-history', { method: 'POST', body: formWith(file) }, true),
    /** Compares the old process's final allocation with PIFA's for a cycle. */
    reconcile: (cycleId: string, file: File) => json<Reconciliation>(`/admin/go-live/cycles/${cycleId}/reconciliation`, { method: 'POST', body: formWith(file) }),
    /** Empty sales or allocation template. */
    goLiveTemplate: (kind: 'sales' | 'allocation') => download(`/admin/go-live/templates/${kind}`, `PIFA_${kind}_template.xlsx`),
  }
}

/** Builds the query string of an audit search. */
function auditQuery(f: AuditFilter) {
  const p = new URLSearchParams()
  for (const [k, v] of Object.entries(f)) if (v) p.set(k, v)
  return p.toString()
}

/** Admin overview of one cycle (mirrors AdminInsightService.overview). */
export type Overview = {
  cycle: CycleDetail
  issues: {
    rejectedFilesLast7Days: number; pendingTransfers: number; regionsNotSubmitted: string[]; regionsNotFinalized: string[]
    allocationStale: boolean; inventoryChangedAfterApproval: boolean; reviewOverdue: boolean; finalizeOverdue: boolean
  }
  system: Record<string, { value: string; ok: boolean | 'unknown'; updatedAt: string } | number>
}

/** Analytics of one cycle (mirrors AdminInsightService.analytics). */
export type CycleAnalytics = {
  cycleId: string; month: string; state: string
  regions: Record<string, { published: number; revised: number; approved: number | null; final: number | null; fillRatePct: number | null }>
  constrainedProducts: number; constrainedSharePct: number | null
  transferFlows: { from: string; to: string; units: number; transfers: number }[]
  stageHours: { draft: number | null; regionalReview: number | null; planning: number | null; finalization: number | null; total: number | null }
  accuracy: { available: boolean; actualUnits?: number; wapePublishedPct?: number; wapeRevisedPct?: number; wapeAllocationPct?: number; source?: string }
}

/** The client type, for component props. */
export type Api = ReturnType<typeof createApi>
