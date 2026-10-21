# Purpose: pin Terraform and the official Keycloak provider (keycloak/keycloak, the successor of mrparkers/keycloak)
# for the PIFA production realm. "~> 5.0" accepts 5.x releases but never a breaking 6.0.

terraform {
  required_version = ">= 1.6"

  required_providers {
    keycloak = {
      source  = "keycloak/keycloak"
      version = "~> 5.0"
    }
  }

  # Remote state example (same S3 + DynamoDB backend as infra/terraform/aws, different key). Commented so that
  # `terraform init -backend=false && terraform validate` works without cloud credentials.
  #
  # backend "s3" {
  #   bucket         = "example-org-terraform-state"
  #   key            = "pifa/prod/keycloak.tfstate"
  #   region         = "eu-west-1"
  #   encrypt        = true
  #   dynamodb_table = "terraform-state-locks"
  # }
}

# Terraform authenticates to Keycloak with a dedicated confidential client in the master realm
# (client-credentials grant, realm-admin role), never with a human admin password.
# The secret comes from TF_VAR_keycloak_client_secret in CI (read from Secrets Manager:
# pifa/<env>/keycloak/terraform-admin-secret).
provider "keycloak" {
  url           = var.keycloak_url
  realm         = var.keycloak_admin_realm
  client_id     = var.keycloak_client_id
  client_secret = var.keycloak_client_secret
}
