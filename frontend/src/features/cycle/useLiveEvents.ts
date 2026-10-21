/**
 * useLiveEvents.ts - live refresh (Phase 4): listens to the backend's Server-Sent Events stream and calls
 * `onChange` whenever something changes in the watched cycle, so panels reload without a manual refresh
 * (for example a Regional Manager sees a transfer request arrive).
 *
 * The browser's EventSource cannot send an Authorization header, so the stream is read with fetch and
 * parsed here. On any disconnect it reconnects after a short, growing delay.
 */
import { useEffect, useRef } from 'react'

/**
 * @param token    the access token (the stream requires it like every other endpoint)
 * @param cycleId  only events of this cycle trigger `onChange` (null: any cycle)
 * @param onChange called (at most once per second) when a relevant event arrives
 */
export function useLiveEvents(token: string, cycleId: string | null, onChange: () => void) {
  // Keep the latest callback/cycle without restarting the stream on every render.
  const handler = useRef(onChange)
  const watched = useRef(cycleId)
  handler.current = onChange
  watched.current = cycleId

  useEffect(() => {
    let stopped = false
    let delay = 1000
    let last = 0
    const controller = new AbortController()

    /** Calls the handler, at most once per second (several events often arrive together). */
    function fire() {
      const now = Date.now()
      if (now - last < 1000) return
      last = now
      handler.current()
    }

    /** Opens the stream and reads it until it ends, then schedules a reconnect. */
    async function connect() {
      try {
        const r = await fetch('/api/v1/events', { headers: { Authorization: `Bearer ${token}` }, signal: controller.signal })
        if (!r.ok || !r.body) throw new Error(`stream ${r.status}`)
        delay = 1000
        const reader = r.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        for (;;) {
          const { value, done } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          // SSE messages are separated by a blank line; each has "event:" and "data:" lines.
          let idx
          while ((idx = buffer.indexOf('\n\n')) >= 0) {
            const message = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            const event = /^event:(.*)$/m.exec(message)?.[1]?.trim()
            const data = /^data:(.*)$/m.exec(message)?.[1]?.trim()
            if (event !== 'change' || !data) continue
            try {
              const payload = JSON.parse(data) as { cycleId: string | null }
              if (!watched.current || !payload.cycleId || payload.cycleId === watched.current) fire()
            } catch { /* ignore malformed events */ }
          }
        }
      } catch {
        /* network error or abort: fall through to reconnect */
      }
      if (!stopped) {
        setTimeout(connect, delay)
        delay = Math.min(delay * 2, 30_000)
      }
    }

    connect()
    return () => { stopped = true; controller.abort() }
  }, [token])
}
