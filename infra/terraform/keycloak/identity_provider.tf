# Purpose: OPTIONAL brokering to the corporate OIDC IdP (Entra ID / Okta / Google), architecture.md 6.1
# "Federation" and Phase 5 open question Q9. Disabled by default (idp_enabled = false); nothing is created then.
#
# Notes:
#   - MFA for brokered users is the IdP's job (e.g. Conditional Access requiring MFA for the PIFA app); Keycloak's
#     pifa-browser OTP steps do not run for them. Agree this with the IdP owners before enabling in prod.
#   - Roles and regions can be derived from IdP groups/claims with the mappers below (sync mode FORCE: the IdP is
#     the source of truth and changes apply at the next login). Without mappings, an admin assigns them in Keycloak.

resource "keycloak_oidc_identity_provider" "corporate" {
  count = var.idp_enabled ? 1 : 0

  realm        = keycloak_realm.this.id
  alias        = var.idp_alias
  display_name = var.idp_display_name
  enabled      = true

  issuer             = var.idp_issuer
  authorization_url  = var.idp_authorization_url
  token_url          = var.idp_token_url
  jwks_url           = var.idp_jwks_url
  validate_signature = true

  client_id      = var.idp_client_id
  client_secret  = var.idp_client_secret
  default_scopes = "openid profile email"

  trust_email        = true  # corporate IdP addresses are verified there
  store_token        = false # PIFA never calls the IdP on behalf of the user
  sync_mode          = "FORCE"
  hide_on_login_page = false

  extra_config = {
    clientAuthMethod = "client_secret_post"
  }
}

resource "keycloak_custom_identity_provider_mapper" "group_to_role" {
  for_each = var.idp_enabled ? var.idp_group_to_role : {}

  realm                    = keycloak_realm.this.id
  name                     = "group-${each.key}-to-${each.value}"
  identity_provider_alias  = keycloak_oidc_identity_provider.corporate[0].alias
  identity_provider_mapper = "oidc-role-idp-mapper"

  extra_config = {
    syncMode      = "FORCE"
    claim         = var.idp_role_claim
    "claim.value" = each.key
    role          = keycloak_role.realm[each.value].name
  }
}

resource "keycloak_attribute_importer_identity_provider_mapper" "regions" {
  count = var.idp_enabled && var.idp_regions_claim != "" ? 1 : 0

  realm                   = keycloak_realm.this.id
  name                    = "regions"
  identity_provider_alias = keycloak_oidc_identity_provider.corporate[0].alias
  claim_name              = var.idp_regions_claim
  user_attribute          = "regions"

  extra_config = {
    syncMode = "FORCE"
  }
}
