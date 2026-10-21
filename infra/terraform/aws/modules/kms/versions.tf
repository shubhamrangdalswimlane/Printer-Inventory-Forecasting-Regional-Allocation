# Purpose: declare the provider this module needs (the version constraint is owned by the root module's versions.tf).

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}
