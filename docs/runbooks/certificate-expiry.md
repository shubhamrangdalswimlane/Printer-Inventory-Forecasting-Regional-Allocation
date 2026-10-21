# Certificate expiry (PifaCertificateExpiringSoon, PifaCertificateExpiryCritical)

## What it means
A TLS certificate (ingress, Keycloak, ClamAV TLS terminator, SFTP/SMTP relay) expires within 14 days.

## Fix
- The ingress certificate is issued by cert-manager: `kubectl -n pifa get certificate`. If renewal is
  failing, check `kubectl -n pifa describe certificaterequest` for ACME or issuer errors.
- The ClamAV TLS certificate is internal: regenerate it and update the `caCert` secret used by the API and worker
  (`PIFA_CLAMAV_CA_CERT`), then restart both.
- External certificates (SSO, SMTP relay): contact their owners.

## Verify
`openssl s_client -connect <host>:443 -servername <host> </dev/null | openssl x509 -noout -enddate`

## Escalate
The platform team.
