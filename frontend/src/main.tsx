import React from 'react'
import ReactDOM from 'react-dom/client'
import { AuthProvider } from 'react-oidc-context'
import { BrowserRouter } from 'react-router-dom'
import { oidcConfig } from './auth'
import App from './App'
import './styles.css'

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
