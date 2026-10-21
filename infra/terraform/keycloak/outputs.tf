# Purpose: values the deploy pipeline needs after applying the realm. Client secrets are marked sensitive; CI copies
# them into Secrets Manager (pifa/<env>/keycloak/*) and never prints them. They are also in Terraform state, which is
# why the state bucket is encrypted and access-restricted (see infra/terraform/aws/backend.tf).

output "realm_id" {
  description = "Realm ID/name."
  value       = keycloak_realm.this.id
}

output "issuer_url" {
  description = "OIDC issuer for the API (spring.security.oauth2.resourceserver.jwt.issuer-uri) and the SPA."
  value       = "${var.frontend_url}/realms/${keycloak_realm.this.realm}"
}

output "client_ids" {
  description = "Client IDs of the PIFA clients."
  value = {
    web          = keycloak_openid_client.web.client_id
    api          = keycloak_openid_client.api.client_id
    automation   = keycloak_openid_client.automation.client_id
    admin_reader = keycloak_openid_client.admin_reader.client_id
  }
}

output "browser_flow_alias" {
  description = "Alias of the bound browser flow with role-conditional OTP."
  value       = keycloak_authentication_flow.browser.alias
}

output "automation_client_secret" {
  description = "Secret of pifa-automation; store in pifa/<env>/keycloak/pifa-automation-secret."
  value       = keycloak_openid_client.automation.client_secret
  sensitive   = true
}

output "admin_reader_client_secret" {
  description = "Secret of pifa-admin-reader; store in pifa/<env>/keycloak/pifa-admin-reader-secret (PIFA_KEYCLOAK_READER_SECRET)."
  value       = keycloak_openid_client.admin_reader.client_secret
  sensitive   = true
}
