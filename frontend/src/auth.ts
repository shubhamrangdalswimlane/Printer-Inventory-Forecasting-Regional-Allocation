/**
 * auth.ts - OIDC (OpenID Connect) login settings for the browser app.
 *
 * The app signs users in through Keycloak using the "authorization code + PKCE" flow:
 * the browser is redirected to Keycloak, the user logs in there (the app never sees the
 * password), and Keycloak redirects back with a code that the oidc-client-ts library exchanges
 * for tokens. These settings are passed to <AuthProvider> in main.tsx.
 */
import { WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'

/**
 * A minimal in-memory implementation of the browser Storage interface.
 *
 * Why: by default oidc-client-ts would store tokens in sessionStorage/localStorage, where any
 * injected script could read them. Keeping them in a plain Map means tokens live only in this
 * page's memory. Trade-off: a full page reload starts a fresh login (the Keycloak SSO session
 * makes that near-instant).
 *
 * Every method mirrors the standard Storage method of the same name.
 */
class InMemoryStore implements Storage {
  /** The actual key/value data. */
  private data = new Map<string, string>()

  /** @returns number of stored items */
  get length() { return this.data.size }

  /** Removes every item. */
  clear() { this.data.clear() }

  /**
   * @param key item name
   * @returns the stored string, or null if the key does not exist
   */
  getItem(key: string) { return this.data.get(key) ?? null }

  /**
   * @param i zero-based position
   * @returns the key at that position, or null if out of range
   */
  key(i: number) { return [...this.data.keys()][i] ?? null }

  /** @param key item name to delete */
  removeItem(key: string) { this.data.delete(key) }

  /**
   * @param key item name
   * @param value string to store under that name
   */
  setItem(key: string, value: string) { this.data.set(key, value) }
}

/**
 * Settings object consumed by <AuthProvider>.
 *  - authority    : the Keycloak realm URL (override with VITE_OIDC_AUTHORITY in .env files).
 *  - client_id    : must match the public client "pifa-web" in infra/keycloak/realm-pifa-dev.json.
 *  - redirect_uri : where Keycloak sends the user back after login (the app's own origin).
 *  - scope        : "openid" is required for OIDC; "profile" adds username/name claims.
 *  - userStore    : where tokens are kept (memory only, see InMemoryStore above).
 * PKCE (S256) is on by default in oidc-client-ts and is enforced by the Keycloak client config.
 */
export const oidcConfig: UserManagerSettings = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8081/realms/pifa-dev',
  client_id: 'pifa-web',
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  scope: 'openid profile',
  userStore: new WebStorageStateStore({ store: new InMemoryStore() }),
}
