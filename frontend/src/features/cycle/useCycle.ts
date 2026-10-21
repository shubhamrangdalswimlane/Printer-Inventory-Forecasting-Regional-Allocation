/**
 * useCycle.ts - shared state of the "current planning cycle" for every dashboard (PRD FR-COM-02).
 *
 * Loads the list of cycles, remembers which one is selected (default: the newest), and loads that
 * cycle's detail and the caller's permissions. Call `reload()` after any action that changes the cycle.
 */
import { useCallback, useEffect, useState } from 'react'
import type { Api, CycleDetail, CycleSummary, Perms } from '../../api'

/** The values the hook returns. */
export type CycleState = {
  cycles: CycleSummary[]
  selectedId: string | null
  select: (id: string) => void
  detail: CycleDetail | null
  perms: Perms | null
  /** Incremented on every reload so child panels know to refetch. */
  version: number
  reload: () => void
  error: string | null
}

/**
 * @param api the API client
 * @returns the cycle list, the selected cycle with its detail and permissions, and a reload function
 */
export function useCycle(api: Api): CycleState {
  const [cycles, setCycles] = useState<CycleSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<CycleDetail | null>(null)
  const [perms, setPerms] = useState<Perms | null>(null)
  const [version, setVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)

  /** Reloads the cycle list, then the selected cycle and its permissions. */
  const reload = useCallback(() => {
    api.cycles().then((list) => {
      setCycles(list)
      setSelectedId((cur) => cur ?? list[0]?.id ?? null)
    }).catch((e: Error) => setError(e.message))
    setVersion((v) => v + 1)
  }, [api])

  useEffect(reload, [reload])

  useEffect(() => {
    if (!selectedId) { setDetail(null); setPerms(null); return }
    Promise.all([api.cycle(selectedId), api.permissions(selectedId)])
      .then(([d, p]) => { setDetail(d); setPerms(p); setError(null) })
      .catch((e: Error) => setError(e.message))
  }, [api, selectedId, version])

  return { cycles, selectedId, select: setSelectedId, detail, perms, version, reload, error }
}
