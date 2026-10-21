/**
 * vite.config.ts - configuration for Vite, the dev server and bundler.
 * `npm run dev` starts the dev server; `npm run build` produces static files in dist/.
 */
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // Enables JSX and fast refresh (live component reload) for React.
  plugins: [react()],
  server: {
    // Must match the redirect URI registered for "pifa-web" in the Keycloak realm file.
    port: 5173,
    // Forward /api/* calls to the Spring Boot API during development. The browser then sees a
    // single origin, so no CORS configuration is needed locally.
    proxy: { '/api': 'http://localhost:8080' },
  },
})
