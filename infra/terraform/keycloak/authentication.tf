# Purpose: browser authentication flow with role-conditional OTP (architecture.md 6.1 "MFA", Phase 5):
#   - users holding a role in var.otp_required_roles (admin, planner) MUST pass OTP; if they have not enrolled yet,
#     Keycloak forces OTP enrolment at their next login (the OTP form is REQUIRED for them);
#   - everyone else gets OTP only if they have chosen to configure it (optional MFA).
#
# Flow layout ("pifa-browser"):
#   Cookie                                   ALTERNATIVE
#   Identity Provider Redirector             ALTERNATIVE
#   pifa-browser-forms                       ALTERNATIVE
#     Username Password Form                 REQUIRED
#     pifa-otp-required-<role>  (per role)   CONDITIONAL
#       Condition - user role = <role>       REQUIRED
#       Condition - user role != <earlier>   REQUIRED   (so a user with two such roles is asked only once)
#       OTP Form                             REQUIRED
#     pifa-otp-optional                      CONDITIONAL
#       Condition - user role != each role   REQUIRED
#       Condition - user configured          REQUIRED
#       OTP Form                             REQUIRED
#
# Why one subflow per role: "Condition - user role" accepts a single role and Keycloak ANDs the conditions of a
# subflow, so "admin OR planner" is expressed as mutually exclusive subflows. Keycloak evaluates all conditions of
# a conditional subflow before its other executions, so their position inside the subflow does not matter; the
# order is set explicitly with "priority" (Keycloak >= 25 sorts by it) and additionally with depends_on (older
# Keycloak versions append executions in creation order).
#
# Brokered (corporate IdP) logins skip this flow's password/OTP steps; MFA for them must be enforced at the IdP
# (e.g. Entra ID Conditional Access) - see identity_provider.tf.

locals {
  otp_roles = { for idx, role in var.otp_required_roles : role => idx }

  # For required-role subflow N: negate every role that has its own earlier subflow (avoids double prompts).
  otp_role_exclusions = merge([
    for role, idx in local.otp_roles : {
      for earlier, eidx in local.otp_roles : "${role}-not-${earlier}" => { role = role, excluded = earlier } if eidx < idx
    }
  ]...)
}

resource "keycloak_authentication_flow" "browser" {
  realm_id    = keycloak_realm.this.id
  alias       = "pifa-browser"
  description = "Browser login with OTP required for privileged roles and optional for others"
  provider_id = "basic-flow"
}

resource "keycloak_authentication_execution" "cookie" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_flow.browser.alias
  authenticator     = "auth-cookie"
  requirement       = "ALTERNATIVE"
  priority          = 10
}

resource "keycloak_authentication_execution" "idp_redirector" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_flow.browser.alias
  authenticator     = "identity-provider-redirector"
  requirement       = "ALTERNATIVE"
  priority          = 20

  depends_on = [keycloak_authentication_execution.cookie]
}

resource "keycloak_authentication_subflow" "forms" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_flow.browser.alias
  alias             = "pifa-browser-forms"
  provider_id       = "basic-flow"
  requirement       = "ALTERNATIVE"
  priority          = 30

  depends_on = [keycloak_authentication_execution.idp_redirector]
}

resource "keycloak_authentication_execution" "username_password" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.forms.alias
  authenticator     = "auth-username-password-form"
  requirement       = "REQUIRED"
  priority          = 10
}

# ---------------------------------------------------------------------------------------------------------------
# OTP REQUIRED for privileged roles
# ---------------------------------------------------------------------------------------------------------------
resource "keycloak_authentication_subflow" "otp_required" {
  for_each          = local.otp_roles
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.forms.alias
  alias             = "pifa-otp-required-${each.key}"
  description       = "OTP is mandatory for users with realm role ${each.key}"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 20 + each.value

  depends_on = [keycloak_authentication_execution.username_password]
}

resource "keycloak_authentication_execution" "otp_required_role" {
  for_each          = local.otp_roles
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.otp_required[each.key].alias
  authenticator     = "conditional-user-role"
  requirement       = "REQUIRED"
  priority          = 10
}

resource "keycloak_authentication_execution_config" "otp_required_role" {
  for_each     = local.otp_roles
  realm_id     = keycloak_realm.this.id
  execution_id = keycloak_authentication_execution.otp_required_role[each.key].id
  alias        = "pifa-has-role-${each.key}"
  config = {
    condUserRole = each.key
    negate       = "false"
  }
}

resource "keycloak_authentication_execution" "otp_required_exclusion" {
  for_each          = local.otp_role_exclusions
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.otp_required[each.value.role].alias
  authenticator     = "conditional-user-role"
  requirement       = "REQUIRED"
  priority          = 20

  depends_on = [keycloak_authentication_execution.otp_required_role]
}

resource "keycloak_authentication_execution_config" "otp_required_exclusion" {
  for_each     = local.otp_role_exclusions
  realm_id     = keycloak_realm.this.id
  execution_id = keycloak_authentication_execution.otp_required_exclusion[each.key].id
  alias        = "pifa-${each.key}"
  config = {
    condUserRole = each.value.excluded
    negate       = "true"
  }
}

resource "keycloak_authentication_execution" "otp_required_form" {
  for_each          = local.otp_roles
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.otp_required[each.key].alias
  authenticator     = "auth-otp-form"
  requirement       = "REQUIRED"
  priority          = 90

  depends_on = [keycloak_authentication_execution.otp_required_exclusion]
}

# ---------------------------------------------------------------------------------------------------------------
# OTP OPTIONAL for everyone else (only if the user has configured it)
# ---------------------------------------------------------------------------------------------------------------
resource "keycloak_authentication_subflow" "otp_optional" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.forms.alias
  alias             = "pifa-otp-optional"
  description       = "OTP for non-privileged users who have enrolled an authenticator"
  provider_id       = "basic-flow"
  requirement       = "CONDITIONAL"
  priority          = 80

  depends_on = [keycloak_authentication_execution.otp_required_form]
}

resource "keycloak_authentication_execution" "otp_optional_not_role" {
  for_each          = local.otp_roles
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.otp_optional.alias
  authenticator     = "conditional-user-role"
  requirement       = "REQUIRED"
  priority          = 10 + each.value
}

resource "keycloak_authentication_execution_config" "otp_optional_not_role" {
  for_each     = local.otp_roles
  realm_id     = keycloak_realm.this.id
  execution_id = keycloak_authentication_execution.otp_optional_not_role[each.key].id
  alias        = "pifa-optional-not-${each.key}"
  config = {
    condUserRole = each.key
    negate       = "true"
  }
}

resource "keycloak_authentication_execution" "otp_optional_configured" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.otp_optional.alias
  authenticator     = "conditional-user-configured"
  requirement       = "REQUIRED"
  priority          = 50

  depends_on = [keycloak_authentication_execution.otp_optional_not_role]
}

resource "keycloak_authentication_execution" "otp_optional_form" {
  realm_id          = keycloak_realm.this.id
  parent_flow_alias = keycloak_authentication_subflow.otp_optional.alias
  authenticator     = "auth-otp-form"
  requirement       = "REQUIRED"
  priority          = 90

  depends_on = [keycloak_authentication_execution.otp_optional_configured]
}

# Make pifa-browser the realm's browser flow once every step exists (binding a half-built flow could lock users out).
resource "keycloak_authentication_bindings" "this" {
  realm_id     = keycloak_realm.this.id
  browser_flow = keycloak_authentication_flow.browser.alias

  depends_on = [
    keycloak_authentication_execution.cookie,
    keycloak_authentication_execution.idp_redirector,
    keycloak_authentication_execution.username_password,
    keycloak_authentication_execution_config.otp_required_role,
    keycloak_authentication_execution_config.otp_required_exclusion,
    keycloak_authentication_execution.otp_required_form,
    keycloak_authentication_execution_config.otp_optional_not_role,
    keycloak_authentication_execution.otp_optional_form,
  ]
}
