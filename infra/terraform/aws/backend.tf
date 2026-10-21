# Purpose: remote state backend EXAMPLE (S3 state bucket + DynamoDB lock table).
#
# It is commented out so `terraform init -backend=false && terraform validate` works in CI without AWS access.
# To use it, uncomment and initialise one state per environment, for example:
#
#   terraform init -backend-config="key=pifa/prod/aws.tfstate"
#   terraform plan -var-file=envs/prod.tfvars
#
# Why S3 + DynamoDB: state is encrypted at rest (KMS), versioned (a corrupted state can be rolled back), and the
# lock table stops two pipelines from applying to the same environment at once. The state bucket and lock table
# are created once per account by a small bootstrap stack (not in this repo), never by the stack they protect.
# Use a separate AWS account (or at least a separate state key and IAM role) per environment so a dev pipeline can
# never touch prod state.
#
# terraform {
#   backend "s3" {
#     bucket         = "example-org-terraform-state"   # bootstrap bucket (versioned, SSE-KMS, TLS-only)
#     key            = "pifa/prod/aws.tfstate"         # override per environment with -backend-config
#     region         = "eu-west-1"
#     encrypt        = true
#     kms_key_id     = "alias/terraform-state"
#     dynamodb_table = "terraform-state-locks"
#   }
# }
