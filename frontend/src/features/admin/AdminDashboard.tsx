/**
 * AdminDashboard.tsx - the Admin's single place to analyse and operate PIFA (PRD FR-AD-01..10), as tabs:
 * Overview, Analytics, Data (versions, ingestion, synthetic data), Master data, Rule parameters, Audit,
 * Cycles and Users. Admin is read-only on business quantities by design; the actions here are
 * configuration, data management and oversight.
 */
import { useState } from 'react'
import type { Api } from '../../api'
import type { CycleState } from '../cycle/useCycle'
import IngestionPanel from '../datasets/IngestionPanel'
import SyntheticPanel from '../datasets/SyntheticPanel'
import VersionHistoryPanel from '../datasets/VersionHistoryPanel'
import AllocationGrid from '../planning/AllocationGrid'
import { AnalyticsTab, AuditTab, CyclesTab, MasterDataTab, OverviewTab, RuleParametersTab, UsersTab } from './AdminTabs'
import GoLiveTab from './GoLiveTab'

const TABS = ['Overview', 'Analytics', 'Data', 'Master data', 'Rule parameters', 'Audit', 'Cycles', 'Users', 'Go-live'] as const

/**
 * @param props.api   API client
 * @param props.token access token (for the synthetic-data panel)
 * @param props.cycle the shared cycle state
 */
export default function AdminDashboard({ api, token, cycle }: { api: Api; token: string; cycle: CycleState }) {
  const [tab, setTab] = useState<(typeof TABS)[number]>('Overview')
  const key = cycle.version
  return (
    <>
      <nav className="tabs">{TABS.map((t) => <button key={t} className={t === tab ? 'active' : ''} onClick={() => setTab(t)}>{t}</button>)}</nav>
      {tab === 'Overview' && cycle.selectedId && <OverviewTab api={api} cycleId={cycle.selectedId} refreshKey={key} />}
      {tab === 'Analytics' && <AnalyticsTab api={api} />}
      {tab === 'Data' && (
        <>
          <SyntheticPanel token={token} onChanged={cycle.reload} />
          {cycle.selectedId && <AllocationGrid api={api} cycleId={cycle.selectedId} mode="readonly" perms={cycle.perms} refreshKey={key} onSaved={cycle.reload} />}
          <IngestionPanel api={api} refreshKey={key} />
          <VersionHistoryPanel api={api} type="FORECAST_UPLOAD" canActivate canDelete isAdmin refreshKey={key} />
          <VersionHistoryPanel api={api} type="INVENTORY_UPLOAD" canActivate canDelete isAdmin refreshKey={key} />
        </>
      )}
      {tab === 'Master data' && <MasterDataTab api={api} />}
      {tab === 'Rule parameters' && <RuleParametersTab api={api} editable />}
      {tab === 'Audit' && <AuditTab api={api} />}
      {tab === 'Cycles' && <CyclesTab api={api} cycle={cycle} />}
      {tab === 'Users' && <UsersTab api={api} />}
      {tab === 'Go-live' && <GoLiveTab api={api} cycle={cycle} />}
    </>
  )
}
