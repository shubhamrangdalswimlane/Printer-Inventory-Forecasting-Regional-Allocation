# PIFA Helm chart

This chart deploys PIFA to Kubernetes (`architecture.md` §13). One release installs the whole application namespace:

| Component | Objects | Purpose |
|---|---|---|
| **api** | Deployment (2), HPA (2–6 at 70% CPU), PDB (minAvailable 1), Service `http` 8080 + `management` 9091 | REST API for the browser |
| **worker** | Deployment (2), PDB, Service `management` 9091 (metrics only) | SFTP poller and scheduled jobs |
| **web** | Deployment (2), PDB, Service 8080, ConfigMap (`nginx.conf`) | Unprivileged nginx serving the React SPA, with strict security headers |
| **clamav** | Deployment (2), PDB, Service 3311 (TLS only), optional cert-manager Certificate | Virus scanning of every file |
| Ingress | 2 Ingress objects on one host: `/api` → api, `/` → web | TLS, rate limiting, 11 MB upload limit |
| Secrets | ExternalSecrets (optional) | DB login, Keycloak reader secret, SFTP key + known_hosts, ClamAV CA and server certificate |
| Network | NetworkPolicies | Default-deny ingress, then only the required flows |
| Monitoring | ServiceMonitor, PrometheusRule (optional) | Metrics scrape and the alerts from `architecture.md` §14 |

PostgreSQL, S3, Keycloak and the SFTP server are managed services outside this chart.

## The api / worker split

There is one backend image (`backend/Dockerfile`), and it runs as two Deployments that differ only in environment:

| | api | worker |
|---|---|---|
| `PIFA_JOBS_ENABLED` | `false` | `true` |
| `PIFA_SFTP_ENABLED` | `false` | `true` |
| Receives user traffic | yes (Ingress `/api`) | no |
| Scaling | HPA on CPU | fixed 2 replicas |
| SFTP key mounted | no | yes |

Why split them? Month-end batch work (parsing 50k-row workbooks, retention, audit verification) then never
competes with interactive requests for CPU or DB connections, and each side can be sized and restarted on its own.

Why are 2 workers safe? Every background job first takes a PostgreSQL advisory lock (ShedLock), so exactly one
replica runs each job. The other replica is a hot standby that takes the lock if the first pod dies. Adding more
workers would not add throughput, so the worker has no HPA.

Storage must be `s3`. Api and worker are separate pods, so a pod-local file store would leave each with
different files. The templates refuse to render with `config.storage.type=local`.

## Install

Prerequisites: an ingress-nginx controller, and optionally External Secrets Operator, cert-manager and
kube-prometheus-stack (the matching features are switched on by values).

```bash
# 1. Secrets. Either let External Secrets create them (externalSecrets.enabled=true and entries in the secret
#    manager), or create them yourself with the names/keys from values.yaml `secrets`, for example:
kubectl -n pifa create secret generic pifa-db --from-literal=username=pifa --from-literal=password='...'
kubectl -n pifa create secret generic pifa-sftp --from-file=id_ed25519=./pifa-worker.key --from-file=known_hosts=./known_hosts
kubectl -n pifa create secret tls pifa-clamav-tls --cert=clamav.crt --key=clamav.key
kubectl -n pifa create secret generic pifa-clamav-ca --from-file=ca.crt=./internal-ca.pem

# 2. Install / upgrade (the image tag is the commit SHA that CI pushed)
helm upgrade --install pifa infra/helm/pifa -n pifa --create-namespace \
  -f infra/helm/pifa/values-prod.yaml --set image.tag=<git-sha>

# 3. Check
kubectl -n pifa get pods
helm -n pifa status pifa      # prints NOTES.txt again
```

`values-dev.yaml` and `values-prod.yaml` are worked examples. Copy one per environment and change the hosts, bucket,
role ARN and secret-manager paths. `example.com` and `OWNER` are placeholders throughout.

Rollback: `helm -n pifa rollback pifa <revision>`. Flyway migrations are forward-only, so roll back only to a
release with a compatible schema.

## Web image

The SPA is static, and `VITE_OIDC_AUTHORITY` is baked in when `npm run build` runs. A real deployment therefore
uses an image that contains `frontend/dist` for that environment's Keycloak realm. Build it with the repository's
[`frontend/Dockerfile`](../../../frontend/Dockerfile) (unprivileged nginx serving the production build):

```bash
docker build --build-arg VITE_OIDC_AUTHORITY=https://sso.example.com/realms/pifa -t ghcr.io/OWNER/pifa-web:TAG frontend
```

Then set `web.image.repository=ghcr.io/OWNER/pifa-web` and `web.image.tag`. The nginx configuration always comes
from the chart's ConfigMap, not from the image, so the security headers are the same everywhere. The default
`nginxinc/nginx-unprivileged` image only serves nginx's welcome page. It is the default so that the chart renders
and runs anywhere; NOTES.txt warns when it is still in use.

### Security headers (web)

Every response from nginx carries:

- `Content-Security-Policy: default-src 'self'; connect-src 'self' <web.keycloakOrigin>; frame-ancestors 'none'; object-src 'none'; base-uri 'self'`
  (plus `web.cspExtra` if set)
- `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`,
  `Cross-Origin-Opener-Policy: same-origin`, `Permissions-Policy` (no device APIs)

Unknown paths fall back to `index.html` (client-side routes). `index.html` is `no-cache`, and hashed `/assets/*`
are cached for a year.

## Key values

Every key is documented in `values.yaml`. The most important ones:

| Key | Default | Meaning |
|---|---|---|
| `image.repository` / `image.tag` / `image.digest` | `ghcr.io/OWNER/pifa-api` / appVersion / – | Backend image for api and worker; the digest wins over the tag |
| `config.springProfilesActive` | `""` | `prod` in production |
| `config.oidcIssuer` | `https://sso.example.com/realms/pifa` | Public realm URL; must equal the token `iss` |
| `config.jwkSetUri` | `""` | In-cluster JWKS URL (optional) |
| `config.db.url` | `jdbc:postgresql://…?sslmode=verify-full` | `PIFA_DB_URL` |
| `config.storage.s3.*` | bucket / region / endpoint / kms | S3 settings; `endpoint` empty for AWS |
| `config.clamav.enabled` | `true` | `PIFA_CLAMAV_ENABLED` |
| `config.sftp.*` | host / port / user / strictHostKey=true | Worker SFTP settings |
| `config.syntheticEnabled` | `false` | Must stay false in production |
| `config.retentionDropPartitions` | `false` | Allow dropping 7-year-old audit partitions |
| `config.javaToolOptions` | `-XX:MaxRAMPercentage=75` | Heap sized from the memory limit |
| `otel.enabled` / `otel.endpoint` | `false` / collector URL | Attach the OpenTelemetry Java agent (init container copies it in) |
| `serviceAccount.roleArn` | `""` | IRSA role (`eks.amazonaws.com/role-arn`). AWS credentials never go in env vars |
| `api.autoscaling.*` | 2–6, 70% CPU | HPA |
| `worker.replicas` | 2 | Active + standby |
| `web.keycloakOrigin` | `https://sso.example.com` | Allowed in the CSP `connect-src` |
| `clamav.tls.certManager.enabled` | `false` | Issue the ClamAV server certificate with cert-manager |
| `clamav.databaseMirror` | `database.clamav.net` | Signature mirror (use a private one without internet egress) |
| `ingress.host` / `ingress.tlsSecretName` | `pifa.example.com` / `pifa-tls` | Public host |
| `ingress.apiAnnotations` | limit-rps 20, body 11m, … | ingress-nginx annotations for `/api` |
| `externalSecrets.enabled` | `false` | Create ExternalSecrets; otherwise the Secrets must already exist |
| `secrets.*.name` | `pifa-db`, `pifa-sftp`, … | Names of the Secrets the pods read |
| `networkPolicy.*` | enabled | Namespaces and labels of the ingress controller and Prometheus |
| `monitoring.enabled` | `false` | ServiceMonitor + PrometheusRule |
| `monitoring.rules.*` | see values | Alert thresholds and the blackbox probe selector |

## Security design notes

- **Pods.** All pods run as non-root (uid 1000; ClamAV uses the image's `clamav` uid 100), with a read-only root
  filesystem and an `emptyDir` at `/tmp`. All capabilities are dropped, `allowPrivilegeEscalation` is false, the
  seccomp profile is `RuntimeDefault`, and service-account tokens are not mounted (none of the pods calls the
  Kubernetes API; IRSA injects its own token).
- **Management port 9091** (health and Prometheus) is never referenced by the Ingress. The NetworkPolicy admits
  only Prometheus to it, and kubelet probes reach it on the pod network.
- **ClamAV.** clamd binds to `127.0.0.1:3310` inside the pod. The socat sidecar terminates TLS (≥ 1.2) on 3311,
  and that is the only port the Service exposes. The api and worker verify the certificate against the CA in
  `secrets.clamavCa` and check the host name (`<fullname>-clamav.<namespace>.svc` by default, which is one of
  the certificate's SANs when cert-manager issues it). The image's `/init` script needs root, so the chart starts
  `clamd` and `freshclam` directly.
- **X-Request-Id.** ingress-nginx sends `X-Request-ID: $req_id` to the backends by default (controller option
  `generate-request-id`). It keeps an incoming id or generates one. The API logs it and returns it in the response
  and in `problem+json` errors. `ingress.requestIdSnippet` can also return it on static responses, but only if the
  controller allows snippet annotations. Most do not, for security reasons.
- **Egress** is not restricted by the chart, because DB, S3, SFTP, SMTP and Keycloak endpoints differ per
  environment. Restrict it with cloud security groups or extra NetworkPolicies if your platform requires that.

## Alerts

`templates/prometheusrule.yaml` contains these alerts. Each one has `severity`, `summary`, `description` and a
`runbook_url` pointing to `docs/runbooks/<file>.md`:

| Alert | Severity | Runbook |
|---|---|---|
| PifaIngestionRejectedSpike | warning | rejected-file.md |
| PifaSftpNoSuccessfulPoll, PifaWorkerDown | critical | sftp-outage.md |
| PifaApiHighErrorRate (5xx > 1%), PifaApiDown | critical | api-errors.md |
| PifaApiPodsUnavailable | warning | api-errors.md |
| PifaApiHighLatency (p95 > 2 s) | warning | latency.md |
| PifaMailOutboxGrowing | warning | mail-outbox.md |
| PifaCycleStageOverdue | warning | stage-overdue.md |
| PifaAuditChainBroken | critical | audit-chain-broken.md |
| PifaAuditChainUnverified | info | audit-chain-broken.md |
| PifaPodRestarting | warning | pod-restarts.md |
| PifaDbPoolExhausted | warning | db-pool.md |
| PifaCertificateExpiringSoon (< 21 d), PifaCertificateExpiryCritical (< 7 d) | warning / critical | certificate-expiry.md |

Dependencies:

- The pod and deployment alerts need **kube-state-metrics**.
- The certificate alerts need the **blackbox exporter** probing the public endpoints, because
  `probe_ssl_earliest_cert_expiry` only exists if it does. Set `monitoring.rules.certProbeSelector` to match your
  probe job.
- The runbook files themselves are a Phase 5 deliverable under `docs/runbooks/`.

The SFTP alert is a heuristic, because the application counts only failed polls. See the comment in the template.

Grafana dashboards are in `infra/monitoring/`.

## Validating changes locally

```bash
docker run --rm -v "$PWD/infra/helm:/charts" -w /charts alpine/helm:3.16.3 lint pifa -f pifa/values-prod.yaml
docker run --rm -v "$PWD/infra/helm:/charts" -w /charts alpine/helm:3.16.3 template pifa pifa -f pifa/values-prod.yaml > /tmp/pifa.yaml
docker run --rm -v /tmp:/work ghcr.io/yannh/kubeconform:v0.6.7 -strict -summary \
  -schema-location default \
  -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
  /work/pifa.yaml
```
