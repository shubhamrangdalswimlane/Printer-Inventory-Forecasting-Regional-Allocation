/**
 * NotificationBell.tsx - in-app notifications (PRD FR-COM-06): a bell with the unread count; clicking
 * opens a list, and clicking an entry marks it as read. Refreshes every 30 seconds.
 */
import { useCallback, useEffect, useState } from 'react'
import type { Api, Notice } from '../../api'

/** @param props.api API client */
export default function NotificationBell({ api }: { api: Api }) {
  const [items, setItems] = useState<Notice[]>([])
  const [open, setOpen] = useState(false)

  const load = useCallback(() => { api.notifications().then(setItems).catch(() => { /* a failed refresh just keeps the old list */ }) }, [api])
  useEffect(() => {
    load()
    const t = setInterval(load, 30_000)
    return () => clearInterval(t)
  }, [load])

  const unread = items.filter((i) => i.unread).length
  return (
    <span className="bell">
      <button onClick={() => setOpen(!open)} aria-label="Notifications">🔔{unread > 0 && <span className="badge">{unread}</span>}</button>
      {open && (
        <div className="bell-panel">
          {items.length === 0 && <p className="muted">No notifications.</p>}
          {items.map((n) => (
            <div key={n.id} className={`notice ${n.unread ? 'unread' : ''}`} onClick={() => api.markRead(n.id).then(load)}>
              <b>{n.title}</b>
              {n.body && <div className="muted">{n.body}</div>}
              <small className="muted">{new Date(n.createdAt).toLocaleString()}</small>
            </div>
          ))}
        </div>
      )}
    </span>
  )
}
