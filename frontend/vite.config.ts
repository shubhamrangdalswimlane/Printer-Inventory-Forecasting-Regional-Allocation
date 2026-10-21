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
    // Override with VITE_API_TARGET when the API listens elsewhere (e.g. port 8080 is taken).
    proxy: { '/api': process.env.VITE_API_TARGET ?? 'http://localhost:8080' },
  },
  // `vite preview` serves the production build (used by the end-to-end tests and CI) with the same proxy.
  preview: {
    port: 4173,
    // Vite only answers requests addressed to localhost unless told otherwise (DNS-rebinding protection).
    // The E2E containers reach the preview server as http://web:4173, so that name is allowed too.
    allowedHosts: ['web'],
    proxy: { '/api': process.env.VITE_API_TARGET ?? 'http://localhost:8080' },
  },
})
