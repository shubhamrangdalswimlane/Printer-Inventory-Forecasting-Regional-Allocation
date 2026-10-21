# Purpose: AWS provider configurations - the primary region and an aliased provider for the DR region
# (cross-region S3 replication target, RDS backup replication, DR KMS key and backup vault).
# Credentials are NOT configured here: CI assumes a deploy role through OIDC (GitHub Actions -> AWS) and exposes
# it as the standard AWS_* environment variables, so no access keys are ever stored.

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = local.tags
  }
}

provider "aws" {
  alias  = "dr"
  region = var.dr_region

  default_tags {
    tags = local.tags
  }
}
