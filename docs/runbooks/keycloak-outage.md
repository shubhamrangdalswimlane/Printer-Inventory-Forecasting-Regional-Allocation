# Keycloak outage

## Impact
Nobody can sign in. Users with a valid access token keep working until it expires (5 minutes by default).
The API validates tokens with cached signing keys, so it does not need Keycloak for each request.
SFTP ingestion and mail continue (they do not use Keycloak).

## Diagnose
1. `curl -fsS https://<sso-host>/realms/<realm>/.well-known/openid-configuration`
2. Keycloak pods and their database: `kubectl -n keycloak get pods`, logs.

## Fix
Restore Keycloak (it is a separate service with its own database and backups). If the signing keys were
rotated during recovery, the API picks up the new JWKS automatically on the next unknown key id.

## Escalate
The identity/platform team.
