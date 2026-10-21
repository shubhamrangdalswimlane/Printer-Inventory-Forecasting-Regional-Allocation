/**
 * main.tsx - the browser entry point (loaded by index.html).
 *
 * Mounts the React app into <div id="root"> and wraps it in two providers:
 *   - AuthProvider  : handles OIDC login state; gives components the useAuth() hook.
 *   - BrowserRouter : enables URL-based routing (/forecaster, /planner, ...).
 * StrictMode only adds extra development-time checks; it has no effect in production builds.
 */
import React from 'react'
import ReactDOM from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import { BrowserRouter } from 'react-router-dom'
import { oidcConfig } from './auth'
import App from './App'
import './styles.css'

/**
 * Runs after Keycloak redirects back with ?code=...&state=... in the URL and the code has been
 * exchanged for tokens. It rewrites the address bar to the plain path so the one-time code is not
 * left in the URL, browser history, or a bookmark. Takes no input and returns nothing.
 */
const onSigninCallback = () => window.history.replaceState({}, document.title, window.location.pathname)

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthProvider {...oidcConfig} onSigninCallback={onSigninCallback}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>,
)
