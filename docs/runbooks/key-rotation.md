# Secret and key rotation

| Secret | Where | How |
|---|---|---|
| DB password | Secrets Manager `pifa/db` -> K8s secret | Rotate in Secrets Manager; restart API and worker (`rollout restart`). |
| S3 access | IRSA role (no static keys) | Nothing to rotate. |
| SFTP password/key | `pifa/sftp` | Add the new key on the server first, update the secret, restart the worker, then remove the old key. |
| SMTP credentials | `pifa/smtp` | Update, restart the worker. |
| `pifa-admin-reader` client secret | Keycloak, then `pifa/keycloak` | Regenerate in Keycloak, update the secret, restart the API. |
| Keycloak realm signing keys | Keycloak | Add a new active key; keep the old one passive until all tokens expired (at least 1 hour); then remove it. |
| ClamAV TLS CA | `pifa/clamav-ca` | See [certificate-expiry.md](certificate-expiry.md). |

After any rotation, check **Admin, Overview, System status** and the alert dashboard for 15 minutes.
