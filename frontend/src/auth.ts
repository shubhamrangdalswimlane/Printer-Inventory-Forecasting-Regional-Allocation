import { WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'

class InMemoryStore implements Storage {
  private data = new Map<string, string>()
  get length() { return this.data.size }
  clear() { this.data.clear() }
  getItem(key: string) { return this.data.get(key) ?? null }
  key(i: number) { return [...this.data.keys()][i] ?? null }
  removeItem(key: string) { this.data.delete(key) }
  setItem(key: string, value: string) { this.data.set(key, value) }
}

// Access token is kept in memory only (never localStorage).
export const oidcConfig: UserManagerSettings = {
  authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8081/realms/pifa-dev',
  client_id: 'pifa-web',
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  scope: 'openid profile',
  userStore: new WebStorageStateStore({ store: new InMemoryStore() }),
}
