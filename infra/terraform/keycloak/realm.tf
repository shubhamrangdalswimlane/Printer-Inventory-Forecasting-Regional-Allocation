# Purpose: the PIFA production realm itself - token lifetimes, brute-force protection, password policy, OTP policy,
# security headers, events, roles and the user profile (architecture.md 6.1 and 12, Phase.md Phase 5).
# Test users are deliberately NOT defined anywhere in this module: they exist only in the dev JSON realm
# (infra/keycloak/realm-pifa-dev.json). Production users come from the admin or from the corporate IdP.

resource "keycloak_realm" "this" {
  realm        = var.realm_name
  enabled      = true
  display_name = var.realm_display_name

  # "external": HTTPS is required for every request from a public IP. TLS is terminated at the ingress, and
  # in-cluster calls (health, JWKS from the API pods) come from private IPs.
  ssl_required = "external"

  # Accounts are created by admins or by IdP brokering; no self-registration for a business-internal tool.
  registration_allowed     = false
  login_with_email_allowed = true
  duplicate_emails_allowed = false
  edit_username_allowed    = false
  reset_password_allowed   = true
  remember_me              = false # shared planning PCs: never keep a session past browser close
  verify_email             = true

  # Token settings (architecture.md 6.1). Short access tokens limit the value of a leaked token; refresh-token
  # rotation (revoke + max reuse 0) means a stolen refresh token is detected the moment it is used twice.
  access_token_lifespan    = "5m"
  access_code_lifespan     = "1m"
  sso_session_idle_timeout = "30m"
  sso_session_max_lifespan = "10h"
  revoke_refresh_token     = true
  refresh_token_max_reuse  = 0

  default_signature_algorithm = "RS256"

  # NIST 800-63B style: length matters most; history stops cycling back; never the username.
  password_policy = var.password_policy

  # TOTP compatible with common authenticator apps (Microsoft/Google Authenticator, FreeOTP).
  otp_policy {
    type              = "totp"
    algorithm         = "HmacSHA1"
    digits            = 6
    period            = 30
    look_ahead_window = 1
  }

  security_defenses {
    headers {
      x_frame_options                     = "SAMEORIGIN"
      content_security_policy             = "frame-src 'self'; frame-ancestors 'self'; object-src 'none';"
      content_security_policy_report_only = ""
      x_content_type_options              = "nosniff"
      x_robots_tag                        = "none"
      x_xss_protection                    = "1; mode=block"
      strict_transport_security           = "max-age=31536000; includeSubDomains"
      referrer_policy                     = "no-referrer"
    }

    # Temporary lockout with growing wait times: stops password guessing without letting an attacker
    # permanently lock out the Planner or Admin just by typing wrong passwords (permanent_lockout = false).
    brute_force_detection {
      permanent_lockout                = false
      max_login_failures               = 5
      wait_increment_seconds           = 60
      quick_login_check_milli_seconds  = 1000
      minimum_quick_login_wait_seconds = 60
      max_failure_wait_seconds         = 900
      failure_reset_time_seconds       = 43200
    }
  }

  dynamic "smtp_server" {
    for_each = var.smtp == null ? [] : [var.smtp]
    content {
      host     = smtp_server.value.host
      port     = tostring(smtp_server.value.port)
      from     = smtp_server.value.from
      starttls = true # mail in transit is encrypted (security table: TLS for SMTP)
      ssl      = false

      auth {
        username = smtp_server.value.username
        password = var.smtp_password
      }
    }
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Events: login events kept 90 days in the DB and admin events with details, both also written to the server log
# (jboss-logging listener) from where the log pipeline forwards them to the SIEM (architecture.md 6.1, 14).
# ---------------------------------------------------------------------------------------------------------------
resource "keycloak_realm_events" "this" {
  realm_id = keycloak_realm.this.id

  events_enabled    = true
  events_expiration = var.events_expiration_days * 86400 # seconds

  admin_events_enabled         = true
  admin_events_details_enabled = true

  # Empty list = all event types are stored.
  enabled_event_types = []
  events_listeners    = ["jboss-logging"]
}

# ---------------------------------------------------------------------------------------------------------------
# Realm roles. No composites: each role is granted explicitly, which keeps separation of duties visible
# (e.g. a Planner is not implicitly a Forecaster).
# ---------------------------------------------------------------------------------------------------------------
locals {
  realm_roles = {
    forecaster       = "Forecaster: uploads and publishes the monthly forecast"
    regional_manager = "Regional Manager: reviews and revises the forecast for the regions in the regions attribute"
    planner          = "Planner: runs allocation and approves the plan (MFA required)"
    admin            = "Admin: user/region administration, purge and reopen actions (MFA required)"
  }
}

resource "keycloak_role" "realm" {
  for_each    = local.realm_roles
  realm_id    = keycloak_realm.this.id
  name        = each.key
  description = each.value
}

# Default roles for every new user: account-console access only (to enrol OTP and change the password).
# offline_access and uma_authorization are removed: PIFA never needs offline tokens (architecture.md 6.1).
resource "keycloak_default_roles" "this" {
  realm_id      = keycloak_realm.this.id
  default_roles = ["account/view-profile", "account/manage-account"]
}

# ---------------------------------------------------------------------------------------------------------------
# User profile. Declaring the schema (instead of the dev realm's unmanaged attributes) means Keycloak validates
# every value and users cannot set arbitrary attributes. regions is admin-editable only: if users could edit it,
# a Regional Manager could grant themselves another region.
# ---------------------------------------------------------------------------------------------------------------
resource "keycloak_realm_user_profile" "this" {
  realm_id                   = keycloak_realm.this.id
  unmanaged_attribute_policy = "ADMIN_VIEW"

  attribute {
    name         = "username"
    display_name = "$${username}"
    validator {
      name   = "length"
      config = { min = "3", max = "255" }
    }
    validator {
      name = "username-prohibited-characters"
    }
    validator {
      name = "up-username-not-idn-homograph"
    }
    permissions {
      view = ["admin", "user"]
      edit = ["admin"]
    }
  }

  attribute {
    name               = "email"
    display_name       = "$${email}"
    required_for_roles = ["user"]
    validator {
      name = "email"
    }
    validator {
      name   = "length"
      config = { max = "255" }
    }
    permissions {
      view = ["admin", "user"]
      edit = ["admin"]
    }
  }

  attribute {
    name               = "firstName"
    display_name       = "$${firstName}"
    required_for_roles = ["user"]
    validator {
      name   = "length"
      config = { max = "255" }
    }
    validator {
      name = "person-name-prohibited-characters"
    }
    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name               = "lastName"
    display_name       = "$${lastName}"
    required_for_roles = ["user"]
    validator {
      name   = "length"
      config = { max = "255" }
    }
    validator {
      name = "person-name-prohibited-characters"
    }
    permissions {
      view = ["admin", "user"]
      edit = ["admin", "user"]
    }
  }

  attribute {
    name         = "regions"
    display_name = "Regions"
    multi_valued = true

    # Only the four business regions are accepted; anything else is rejected at save time.
    validator {
      name   = "options"
      config = { options = jsonencode(var.regions) }
    }

    annotations = {
      inputType                  = "multiselect"
      inputOptionsFromValidation = "options"
    }

    permissions {
      view = ["admin", "user"]
      edit = ["admin"]
    }
  }
}
