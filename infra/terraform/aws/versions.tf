# Purpose: pin the Terraform CLI and provider versions for the PIFA AWS reference environment.
# ">= 1.6": the code uses terraform_data and lifecycle preconditions (available since 1.4/1.2) but deliberately
# avoids newer features such as cross-variable validation (1.9), so any CLI from 1.6 on works.
# "~> 5.80" allows 5.x releases from 5.80 on but never the breaking 6.0; .terraform.lock.hcl then pins the exact
# provider build used by CI.

terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}
