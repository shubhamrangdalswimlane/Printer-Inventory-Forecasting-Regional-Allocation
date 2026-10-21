import { useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'

type Me = { username: string; roles: string[]; regions: string[]; landing: string }

const DASHBOARDS: Record<string, { title: string; role: string }> = {
  '/forecaster': { title: 'Forecaster dashboard', role: 'forecaster' },
  '/regional': { title: 'Regional Manager dashboard', role: 'regional_manager' },
  '/planner': { title: 'Planner dashboard', role: 'planner' },
  '/admin': { title: 'Admin dashboard', role: 'admin' },
}

function useMe(token: string | undefined) {
  const [me, setMe] = useState<Me | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!token) return
    fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`GET /me failed: ${r.status}`))))
      .then(setMe)
      .catch((e: Error) => setError(e.message))
  }, [token])
  return { me, error }
}

function Dashboard({ path, me }: { path: string; me: Me }) {
  const d = DASHBOARDS[path]
  // UI guard only; the API remains the authority.
  if (!me.roles.includes(d.role)) return <p>Access denied for your role.</p>
  return (
    <section>
      <h2>{d.title}</h2>
      <p>Empty dashboard (Phase 0). Signed in as <b>{me.username}</b>
        {me.regions.length > 0 && <> — regions: {me.regions.join(', ')}</>}.</p>
    </section>
  )
}

export default function App() {
  const auth = useAuth()
  const location = useLocation()
  const { me, error } = useMe(auth.user?.access_token)

  if (auth.isLoading) return <p>Loading…</p>
  if (auth.error) return <p>Auth error: {auth.error.message}</p>
  if (!auth.isAuthenticated) {
    return (
      <main>
        <h1>PIFA</h1>
        <button onClick={() => auth.signinRedirect()}>Sign in</button>
      </main>
    )
  }
  if (error) return <p>{error}</p>
  if (!me) return <p>Loading profile…</p>

  return (
    <main>
      <header>
        <h1>PIFA</h1>
        <span>{me.username} ({me.roles.join(', ')})</span>
        <button onClick={() => auth.signoutRedirect()}>Sign out</button>
      </header>
      <Routes>
        {Object.keys(DASHBOARDS).map((p) => (
          <Route key={p} path={p} element={<Dashboard path={p} me={me} />} />
        ))}
        <Route path="*" element={location.pathname === '/' ? <Navigate to={me.landing} replace /> : <p>Not found</p>} />
      </Routes>
    </main>
  )
}
