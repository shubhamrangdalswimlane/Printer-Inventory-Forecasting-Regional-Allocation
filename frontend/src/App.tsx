/**
 * App.tsx - the application shell for Phase 0.
 *
 * Flow: not signed in -> show "Sign in" button -> Keycloak login -> back here with a token ->
 * call GET /api/v1/me -> redirect from "/" to the user's landing dashboard -> show an empty
 * dashboard for that role. Real dashboard content arrives in later phases.
 */
import { useEffect, useState } from 'react'
import { useAuth } from 'react-oidc-context'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'

/** Shape of the JSON returned by the backend GET /api/v1/me (see MeController.MeResponse). */
type Me = { username: string; roles: string[]; regions: string[]; landing: string }

/**
 * The four dashboards: URL path -> page title and the role required to view it.
 * Role names are the Keycloak realm roles. This only controls what the UI shows;
 * the backend independently enforces access on every API call.
 */
const DASHBOARDS: Record<string, { title: string; role: string }> = {
  '/forecaster': { title: 'Forecaster dashboard', role: 'forecaster' },
  '/regional': { title: 'Regional Manager dashboard', role: 'regional_manager' },
  '/planner': { title: 'Planner dashboard', role: 'planner' },
  '/admin': { title: 'Admin dashboard', role: 'admin' },
}

/**
 * Custom hook: loads the current user's profile from the backend.
 *
 * @param token the OIDC access token, or undefined while the user is not signed in
 * @returns `me` (the profile, null until loaded) and `error` (a message if the call failed, else null).
 *          Re-runs whenever the token changes (for example after silent renewal).
 */
function useMe(token: string | undefined) {
  const [me, setMe] = useState<Me | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!token) return // nothing to do until we are signed in
    // The Vite dev server proxies /api to http://localhost:8080 (see vite.config.ts).
    fetch('/api/v1/me', { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(`GET /me failed: ${r.status}`))))
      .then(setMe)
      .catch((e: Error) => setError(e.message))
  }, [token])
  return { me, error }
}

/**
 * Renders one (currently empty) dashboard page.
 *
 * @param props.path the route path, used to look up the title and required role in DASHBOARDS
 * @param props.me   the signed-in user's profile
 * @returns the dashboard heading and a greeting, or an "Access denied" message when the user
 *          lacks the role. This is a UX guard only, not a security control.
 */
function Dashboard({ path, me }: { path: string; me: Me }) {
  const d = DASHBOARDS[path]
  if (!me.roles.includes(d.role)) return <p>Access denied for your role.</p>
  return (
    <section>
      <h2>{d.title}</h2>
      <p>Empty dashboard (Phase 0). Signed in as <b>{me.username}</b>
        {me.regions.length > 0 && <> — regions: {me.regions.join(', ')}</>}.</p>
    </section>
  )
}

/**
 * Root component. Takes no props.
 *
 * Renders, in order of priority: a loading state, an auth error, the sign-in screen, a profile
 * error, a profile-loading state, and finally the signed-in layout with the four routes.
 * Visiting "/" redirects to the user's landing dashboard; any other unknown path shows "Not found".
 */
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
        {/* Starts the redirect to the Keycloak login page. */}
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
        {/* Ends the Keycloak session and returns to the app. */}
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
