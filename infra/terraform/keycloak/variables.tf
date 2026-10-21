# Purpose: inputs of the PIFA Keycloak realm-as-code. Defaults are the PRODUCTION values from architecture.md 6.1;
# uat uses the same code with realm_name = "pifa-uat" and its own URLs.

# ---------------------------------------------------------------------------------------------------------------
# Provider connection
# ---------------------------------------------------------------------------------------------------------------
variable "keycloak_url" {
  description = "Base URL of the Keycloak server (admin API reachable from CI only), e.g. https://sso.example.com."
  type        = string
  default     = "https://sso.example.com"

  validation {
    condition     = can(regex("^https://", var.keycloak_url))
    error_message = "keycloak_url must use https://."
  }
}

variable "keycloak_admin_realm" {
  description = "Realm in which the Terraform admin client lives."
  type        = string
  default     = "master"
}

variable "keycloak_client_id" {
  description = "Client ID of the confidential admin client Terraform uses (client-credentials grant)."
  type        = string
  default     = "terraform"
}

variable "keycloak_client_secret" {
  description = "Secret of the Terraform admin client. Pass via TF_VAR_keycloak_client_secret in CI; never in a file."
  type        = string
  sensitive   = true
  default     = null
}

# ---------------------------------------------------------------------------------------------------------------
# Realm
# ---------------------------------------------------------------------------------------------------------------
variable "realm_name" {
  description = "Realm name: pifa in prod, pifa-uat / pifa-qa elsewhere (architecture.md 6.1)."
  type        = string
  default     = "pifa"

  validation {
    condition     = can(regex("^pifa(-[a-z]+)?$", var.realm_name))
    error_message = "realm_name must be pifa or pifa-<env>."
  }
}

variable "realm_display_name" {
  description = "Display name on the login page."
  type        = string
  default     = "PIFA - Printer Inventory Forecasting & Allocation"
}

variable "frontend_url" {
  description = "Public base URL of the realm's login pages (the issuer host), e.g. https://sso.example.com."
  type        = string
  default     = "https://sso.example.com"

  validation {
    condition     = can(regex("^https://", var.frontend_url))
    error_message = "frontend_url must use https://."
  }
}

variable "web_redirect_uris" {
  description = "EXACT redirect URIs of the SPA (no wildcards in prod: a wildcard lets an attacker-chosen path receive the code)."
  type        = list(string)
  default     = ["https://pifa.example.com/", "https://pifa.example.com/silent-check-sso.html"]

  validation {
    condition     = length(var.web_redirect_uris) > 0 && alltrue([for u in var.web_redirect_uris : can(regex("^https://[^*]+$", u))])
    error_message = "web_redirect_uris must be https URLs without wildcards."
  }
}

variable "web_post_logout_redirect_uris" {
  description = "Exact post-logout redirect URIs of the SPA."
  type        = list(string)
  default     = ["https://pifa.example.com/"]

  validation {
    condition     = alltrue([for u in var.web_post_logout_redirect_uris : can(regex("^https://[^*]+$", u))])
    error_message = "web_post_logout_redirect_uris must be https URLs without wildcards."
  }
}

variable "web_origins" {
  description = "Allowed CORS origins for pifa-web (the app origin only)."
  type        = list(string)
  default     = ["https://pifa.example.com"]

  validation {
    condition     = alltrue([for o in var.web_origins : can(regex("^https://[^*/]+$", o))])
    error_message = "web_origins must be https origins (scheme + host, no path, no wildcard)."
  }
}

variable "regions" {
  description = "Allowed values of the user-profile attribute regions (validator)."
  type        = list(string)
  default     = ["NORTH", "EAST", "WEST", "SOUTH"]

  validation {
    condition     = length(var.regions) > 0 && alltrue([for r in var.regions : can(regex("^[A-Z]+$", r))])
    error_message = "regions must be upper-case region codes."
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Security settings
# ---------------------------------------------------------------------------------------------------------------
variable "password_policy" {
  description = "Keycloak password policy string."
  type        = string
  default     = "length(12) and passwordHistory(5) and notUsername"

  validation {
    condition     = try(tonumber(regex("length\\(([0-9]+)\\)", var.password_policy)[0]), 0) >= 12 && strcontains(var.password_policy, "passwordHistory") && strcontains(var.password_policy, "notUsername")
    error_message = "password_policy must keep length(12+), passwordHistory and notUsername."
  }
}

variable "otp_required_roles" {
  description = "Realm roles for which OTP is REQUIRED at login (architecture.md 6.1: admin and planner). Others: optional."
  type        = list(string)
  default     = ["admin", "planner"]

  validation {
    condition     = length(var.otp_required_roles) > 0 && alltrue([for r in var.otp_required_roles : contains(["forecaster", "regional_manager", "planner", "admin"], r)])
    error_message = "otp_required_roles must be a non-empty subset of the four PIFA realm roles."
  }
}

variable "events_expiration_days" {
  description = "How long login (user) events are kept in the Keycloak DB. They are also forwarded to the log pipeline."
  type        = number
  default     = 90

  validation {
    condition     = var.events_expiration_days >= 1 && var.events_expiration_days <= 3650
    error_message = "events_expiration_days must be between 1 and 3650."
  }
}

variable "smtp" {
  description = "Optional SMTP settings for e-mail verification and password-reset mails. Null leaves mail unset (must be set before go-live because verify_email is on)."
  type = object({
    host     = string
    port     = number
    from     = string
    username = string
  })
  default = null
}

variable "smtp_password" {
  description = "SMTP password, kept separate from var.smtp so the rest stays non-sensitive. Pass via TF_VAR_smtp_password in CI."
  type        = string
  sensitive   = true
  default     = ""
}

# ---------------------------------------------------------------------------------------------------------------
# Optional corporate IdP brokering (architecture.md 6.1 "Federation", Phase 5 Q9)
# ---------------------------------------------------------------------------------------------------------------
variable "idp_enabled" {
  description = "Create the corporate OIDC identity provider (Entra ID / Okta / Google). Disabled by default."
  type        = bool
  default     = false
}

variable "idp_alias" {
  description = "Alias of the identity provider (appears in the broker redirect URI)."
  type        = string
  default     = "corporate"
}

variable "idp_display_name" {
  description = "Button label on the login page."
  type        = string
  default     = "Company login"
}

variable "idp_issuer" {
  description = "Issuer URL of the corporate IdP."
  type        = string
  default     = "https://login.example.com"
}

variable "idp_authorization_url" {
  description = "OIDC authorization endpoint of the corporate IdP."
  type        = string
  default     = "https://login.example.com/oauth2/v2.0/authorize"
}

variable "idp_token_url" {
  description = "OIDC token endpoint of the corporate IdP."
  type        = string
  default     = "https://login.example.com/oauth2/v2.0/token"
}

variable "idp_jwks_url" {
  description = "JWKS URL of the corporate IdP (signature validation of its ID tokens)."
  type        = string
  default     = "https://login.example.com/discovery/v2.0/keys"
}

variable "idp_client_id" {
  description = "Client ID registered for PIFA at the corporate IdP."
  type        = string
  default     = "pifa-keycloak-broker"
}

variable "idp_client_secret" {
  description = "Client secret registered at the corporate IdP. Pass via TF_VAR_idp_client_secret in CI."
  type        = string
  sensitive   = true
  default     = ""
}

variable "idp_role_claim" {
  description = "Claim of the IdP token that carries group/role names used for role mapping."
  type        = string
  default     = "groups"
}

variable "idp_group_to_role" {
  description = "Map of IdP group value -> PIFA realm role, e.g. { \"pifa-planners\" = \"planner\" }."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for r in values(var.idp_group_to_role) : contains(["forecaster", "regional_manager", "planner", "admin"], r)])
    error_message = "idp_group_to_role values must be PIFA realm roles."
  }
}

variable "idp_regions_claim" {
  description = "Claim of the IdP token imported into the user attribute regions (empty string: do not import)."
  type        = string
  default     = ""
}
