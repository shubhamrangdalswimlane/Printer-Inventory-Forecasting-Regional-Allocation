# Keycloak realm as code — `realm-pifa-dev.json`

JSON cannot hold comments, so this file explains `realm-pifa-dev.json` section by section.
The realm is imported automatically when the Keycloak container first starts (see
`infra/docker-compose.yml`). Change the JSON in Git — never click-ops in shared environments.

| Section | What it does |
|---|---|
| `realm`, `enabled` | Realm name `pifa-dev` (one realm per environment). The API `issuer-uri` is `http://localhost:8081/realms/pifa-dev`. |
| `sslRequired: none` | Allows plain HTTP for local development only. Production must use TLS. |
| `accessTokenLifespan: 300` | Access tokens live 5 minutes. A leaked token is useful only briefly. |
| `ssoSessionIdleTimeout` / `ssoSessionMaxLifespan` | Login session ends after 30 min idle or 10 h total. |
| `revokeRefreshToken`, `refreshTokenMaxReuse: 0` | Refresh-token rotation: each refresh token works once. |
| `bruteForceProtected` | Temporarily locks accounts after repeated wrong passwords. |
| `attributes.unmanagedAttributePolicy: ENABLED` | Lets users carry the custom `regions` attribute without declaring it in the Keycloak user-profile schema. |
| `roles.realm` | The four business roles: `forecaster`, `regional_manager`, `planner`, `admin`. No composite roles, so separation of duties stays explicit. |

## Clients (applications allowed to use this realm)
- **`pifa-web`** — the React app. *Public* client (a browser cannot keep a secret), standard flow with **PKCE S256** enforced, direct password grant disabled, redirect/web origin limited to `http://localhost:5173`. It carries two protocol mappers:
  - `regions`: copies the user attribute `regions` into the token claim `regions` (multi-valued). This is the region scope the API reads.
  - `pifa-api-audience`: adds `pifa-api` to the token `aud` claim so the API can confirm the token is meant for it.
- **`pifa-api`** — the Spring Boot API. *Bearer-only*: it never logs users in; it only validates tokens.
- **`pifa-automation`** — confidential client with a service account (client-credentials flow) for CI smoke tests and future system-to-system calls. Its secret `dev-automation-secret` is dev-only.

## Test users (all use password `dev-password`, dev only)
| User | Role | Regions |
|---|---|---|
| `fiona.forecaster` | forecaster | — |
| `pat.planner` | planner | — |
| `alex.admin` | admin | — |
| `anita.north` | regional_manager | NORTH |
| `evan.east` | regional_manager | EAST |
| `wendy.west` | regional_manager | WEST |
| `sam.south` | regional_manager | SOUTH |

## Not yet configured (Phase 5)
MFA for admin/planner, password policy, corporate IdP federation, event forwarding.
