# Purpose: example inputs for the PIFA "uat" environment (eu-west-1 primary, eu-central-1 DR).
# Contains NO secrets: only sizes, CIDRs (RFC 5737 documentation ranges), example.com names and placeholder SSH
# *public* keys. Replace the placeholders before the first apply; secret values go to Secrets Manager, not here.
# Usage: terraform plan -var-file=envs/uat.tfvars

environment = "uat"
aws_region  = "eu-west-1"
dr_region   = "eu-central-1"

tags = {
  CostCentre         = "supply-chain-planning"
  Owner              = "pifa-platform@example.com"
  DataClassification = "internal"
}

log_retention_days = 30

# Network
vpc_cidr           = "10.41.0.0/16"
single_nat_gateway = false # one NAT per AZ: an AZ outage does not cut egress

# EKS
kubernetes_version         = "1.31"
eks_admin_principal_arns = [
  "arn:aws:iam::111111111111:role/example-platform-admin",
  "arn:aws:iam::111111111111:role/example-ci-deploy",
]
node_instance_types = ["m6i.large"]
node_min_size       = 3
node_desired_size   = 3
node_max_size       = 5

# Databases
db_engine_version          = "16.4"
app_db_instance_class      = "db.t4g.medium"
keycloak_db_instance_class = "db.t4g.medium"
db_multi_az                = true
db_deletion_protection     = true
db_backup_retention_days   = 35

# Object storage
object_lock_enabled = false

# SFTP - IP allowlist of the upstream systems and their SSH public keys (placeholders).
sftp_allowed_cidrs         = ["203.0.113.10/32", "203.0.113.20/32"]
sftp_forecast_public_keys  = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPLACEHOLDERforecastKEYreplaceME00000000000 forecast-uat@example.com"]
sftp_inventory_public_keys = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPLACEHOLDERinventoryKEYreplaceME0000000000 inventory-uat@example.com"]
sftp_worker_public_keys    = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPLACEHOLDERworkerKEYreplaceME000000000000 pifa-worker-uat@example.com"]

# Mail
mail_mode        = "ses"
mail_from_domain = "uat.pifa.example.com"
