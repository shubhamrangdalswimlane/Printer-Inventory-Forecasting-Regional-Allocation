# Purpose: root module of the PIFA AWS reference environment (architecture.md 13.1 / 13.3).
# It wires the reusable modules together: network -> KMS -> EKS (+ IRSA) -> RDS x2 -> S3 (+DR) -> SFTP -> secrets
# -> mail. Kubernetes workloads themselves (Helm charts, ingress, External Secrets, observability) are deployed by
# the application pipeline on top of this stack, not by Terraform.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  name         = "${var.project}-${var.environment}"
  account_id   = data.aws_caller_identity.current.account_id
  partition    = data.aws_partition.current.partition
  bucket_name  = coalesce(var.files_bucket_name, "${local.name}-files-${local.account_id}")
  secrets_root = "${var.project}/${var.environment}"

  tags = merge(
    {
      Project     = var.project
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = "printer_inventory_forecasting_planner_dashbaord/infra/terraform/aws"
    },
    var.tags,
  )
}

# Guardrails that need more than one variable (cross-variable validation needs Terraform 1.9; this works on 1.6).
resource "terraform_data" "guardrails" {
  lifecycle {
    precondition {
      condition     = var.aws_region != var.dr_region
      error_message = "dr_region must be different from aws_region, otherwise DR copies share the primary's failure domain."
    }
    precondition {
      condition     = var.environment != "prod" || (var.db_multi_az && var.db_deletion_protection && !var.single_nat_gateway)
      error_message = "prod requires db_multi_az = true, db_deletion_protection = true and single_nat_gateway = false."
    }
  }
}

# ---------------------------------------------------------------------------------------------------------------
# KMS keys - one per data class so access to one never implies access to another.
# ---------------------------------------------------------------------------------------------------------------
module "kms_data" {
  source      = "./modules/kms"
  alias       = "${local.name}-data"
  description = "PIFA ${var.environment}: files bucket (originals, exports, SFTP landing)"
}

module "kms_rds" {
  source      = "./modules/kms"
  alias       = "${local.name}-rds"
  description = "PIFA ${var.environment}: RDS storage, Performance Insights, backup vault"
}

module "kms_secrets" {
  source      = "./modules/kms"
  alias       = "${local.name}-secrets"
  description = "PIFA ${var.environment}: Secrets Manager secrets"
}

module "kms_eks" {
  source                = "./modules/kms"
  alias                 = "${local.name}-eks"
  description           = "PIFA ${var.environment}: Kubernetes Secrets envelope encryption and node volumes"
  allow_autoscaling_ebs = true
}

module "kms_logs" {
  source                = "./modules/kms"
  alias                 = "${local.name}-logs"
  description           = "PIFA ${var.environment}: CloudWatch log groups"
  allow_cloudwatch_logs = true
}

# The DR region needs its own key: KMS keys are regional, and replicas must be encrypted in the DR region.
module "kms_dr" {
  source      = "./modules/kms"
  providers   = { aws = aws.dr }
  alias       = "${local.name}-dr"
  description = "PIFA ${var.environment}: DR replicas (S3 replica bucket, RDS replicated backups, DR backup vault)"
}

# ---------------------------------------------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------------------------------------------
module "network" {
  source             = "./modules/network"
  name               = local.name
  region             = var.aws_region
  cluster_name       = local.name
  vpc_cidr           = var.vpc_cidr
  az_count           = 3
  single_nat_gateway = var.single_nat_gateway
  log_retention_days = var.log_retention_days
  logs_kms_key_arn   = module.kms_logs.key_arn
}

# ---------------------------------------------------------------------------------------------------------------
# EKS
# ---------------------------------------------------------------------------------------------------------------
module "eks" {
  source                       = "./modules/eks"
  cluster_name                 = local.name
  kubernetes_version           = var.kubernetes_version
  vpc_id                       = module.network.vpc_id
  private_subnet_ids           = module.network.private_subnet_ids
  admin_principal_arns         = var.eks_admin_principal_arns
  secrets_kms_key_arn          = module.kms_eks.key_arn
  ebs_kms_key_arn              = module.kms_eks.key_arn
  logs_kms_key_arn             = module.kms_logs.key_arn
  log_retention_days           = var.log_retention_days
  node_instance_types          = var.node_instance_types
  node_min_size                = var.node_min_size
  node_desired_size            = var.node_desired_size
  node_max_size                = var.node_max_size
}

# --- IRSA: PIFA app (pifa-api + pifa-worker) -------------------------------------------------------------------
# Least privilege: only object read/write/delete in the files bucket and only the data KMS key.
# No s3:DeleteBucket, no policy/ACL changes, no other bucket, no other key. The sftp/ prefix is explicitly
# denied because the worker reads upstream files over SFTP (pifa-worker account), never directly from S3; this
# keeps the SFTP chroot meaningful and makes the landing zone write-only for the app.
data "aws_iam_policy_document" "app" {
  statement {
    sid       = "ListFilesBucket"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [module.s3.bucket_arn]
  }

  statement {
    sid = "ReadWriteFileObjects"
    actions = [
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = ["${module.s3.bucket_arn}/*"]
  }

  statement {
    sid       = "DenySftpLandingZone"
    effect    = "Deny"
    actions   = ["s3:GetObject*", "s3:PutObject*", "s3:DeleteObject*"]
    resources = ["${module.s3.bucket_arn}/sftp/*"]
  }

  statement {
    sid       = "UseDataKey"
    actions   = ["kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:DescribeKey"]
    resources = [module.kms_data.key_arn]
  }
}

module "irsa_app" {
  source            = "./modules/irsa"
  role_name         = "${local.name}-app"
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_issuer_url   = module.eks.oidc_issuer_url
  namespace         = var.app_namespace
  service_account   = var.app_service_account
  policy_json       = data.aws_iam_policy_document.app.json
}

# --- IRSA: External Secrets Operator ---------------------------------------------------------------------------
# Read-only access to secrets under pifa/<env>/* only (a subset of pifa/*: scoping by environment means a uat
# controller can never read prod secrets even if both ever share an account), plus Decrypt on the secrets key
# only when called through Secrets Manager.
data "aws_iam_policy_document" "external_secrets" {
  statement {
    sid       = "ReadPifaSecrets"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret", "secretsmanager:ListSecretVersionIds"]
    resources = ["arn:${local.partition}:secretsmanager:${var.aws_region}:${local.account_id}:secret:${local.secrets_root}/*"]
  }

  statement {
    sid       = "DecryptViaSecretsManager"
    actions   = ["kms:Decrypt"]
    resources = [module.kms_secrets.key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.aws_region}.amazonaws.com"]
    }
  }
}

module "irsa_external_secrets" {
  source            = "./modules/irsa"
  role_name         = "${local.name}-external-secrets"
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_issuer_url   = module.eks.oidc_issuer_url
  namespace         = var.external_secrets_namespace
  service_account   = var.external_secrets_service_account
  policy_json       = data.aws_iam_policy_document.external_secrets.json
}

# ---------------------------------------------------------------------------------------------------------------
# Databases: separate instances for PIFA and Keycloak, so a Keycloak upgrade/restore never touches business data
# and each can be sized, restored and failed over independently. Both accept connections only from EKS.
# (Tighten further with Security Groups for Pods if other workloads ever share the cluster.)
# ---------------------------------------------------------------------------------------------------------------
module "db_app" {
  source    = "./modules/rds"
  providers = { aws = aws, aws.dr = aws.dr }

  identifier                    = "${local.name}-app"
  db_name                       = "pifa"
  engine_version                = var.db_engine_version
  instance_class                = var.app_db_instance_class
  allocated_storage_gb          = var.db_allocated_storage_gb
  max_allocated_storage_gb      = var.db_max_allocated_storage_gb
  multi_az                      = var.db_multi_az
  deletion_protection           = var.db_deletion_protection
  backup_retention_days         = var.db_backup_retention_days
  dr_backup_retention_days      = var.db_dr_backup_retention_days
  log_min_duration_statement_ms = var.db_log_min_duration_statement_ms
  vpc_id                        = module.network.vpc_id
  subnet_ids                    = module.network.isolated_subnet_ids
  allowed_security_group_ids    = [module.eks.cluster_security_group_id]
  kms_key_arn                   = module.kms_rds.key_arn
  secrets_kms_key_arn           = module.kms_secrets.key_arn
  dr_kms_key_arn                = module.kms_dr.key_arn
  logs_kms_key_arn              = module.kms_logs.key_arn
  log_retention_days            = var.log_retention_days
}

module "db_keycloak" {
  source    = "./modules/rds"
  providers = { aws = aws, aws.dr = aws.dr }

  identifier                    = "${local.name}-keycloak"
  db_name                       = "keycloak"
  engine_version                = var.db_engine_version
  instance_class                = var.keycloak_db_instance_class
  allocated_storage_gb          = 20
  max_allocated_storage_gb      = 100
  multi_az                      = var.db_multi_az
  deletion_protection           = var.db_deletion_protection
  backup_retention_days         = var.db_backup_retention_days
  dr_backup_retention_days      = var.db_dr_backup_retention_days
  log_min_duration_statement_ms = var.db_log_min_duration_statement_ms
  vpc_id                        = module.network.vpc_id
  subnet_ids                    = module.network.isolated_subnet_ids
  allowed_security_group_ids    = [module.eks.cluster_security_group_id]
  kms_key_arn                   = module.kms_rds.key_arn
  secrets_kms_key_arn           = module.kms_secrets.key_arn
  dr_kms_key_arn                = module.kms_dr.key_arn
  logs_kms_key_arn              = module.kms_logs.key_arn
  log_retention_days            = var.log_retention_days
}

# ---------------------------------------------------------------------------------------------------------------
# Object storage (files bucket + DR replica). The app reads its name from PIFA_S3_BUCKET.
# ---------------------------------------------------------------------------------------------------------------
module "s3" {
  source    = "./modules/s3"
  providers = { aws = aws, aws.dr = aws.dr }

  bucket_name                        = local.bucket_name
  dr_bucket_name                     = "${local.bucket_name}-dr"
  kms_key_arn                        = module.kms_data.key_arn
  dr_kms_key_arn                     = module.kms_dr.key_arn
  object_lock_enabled                = var.object_lock_enabled
  noncurrent_version_expiration_days = var.noncurrent_version_expiration_days
}

# ---------------------------------------------------------------------------------------------------------------
# SFTP (AWS Transfer Family) on the files bucket, prefix sftp/.
# Layout in S3 (mirrors application.yml pifa.sftp.*):
#   sftp/inbound/forecast   <- sftp-forecast uploads; pifa-worker polls
#   sftp/inbound/inventory  <- sftp-inventory uploads; pifa-worker polls
#   sftp/outbound/errors    <- pifa-worker writes rejection reports
#   sftp/archive            <- pifa-worker moves processed files here
# ---------------------------------------------------------------------------------------------------------------
module "sftp" {
  source             = "./modules/sftp"
  name               = local.name
  vpc_id             = module.network.vpc_id
  vpc_cidr           = module.network.vpc_cidr
  subnet_ids         = module.network.public_subnet_ids
  internet_facing    = true
  allowed_cidrs      = var.sftp_allowed_cidrs
  bucket_name        = module.s3.bucket_name
  prefix             = "sftp"
  kms_key_arn        = module.kms_data.key_arn
  log_retention_days = var.log_retention_days
  logs_kms_key_arn   = module.kms_logs.key_arn

  users = {
    # Upstream forecast system: upload-only into its own folder; it sees nothing else (logical chroot).
    "sftp-forecast" = {
      ssh_public_keys = var.sftp_forecast_public_keys
      directories     = { "/inbound/forecast" = "inbound/forecast" }
      allow_read      = true # lets SFTP clients stat/verify their own upload
      allow_delete    = false
    }
    # Upstream inventory system: same, for the inventory folder.
    "sftp-inventory" = {
      ssh_public_keys = var.sftp_inventory_public_keys
      directories     = { "/inbound/inventory" = "inbound/inventory" }
      allow_read      = true
      allow_delete    = false
    }
    # PIFA worker (SftpIngestPoller): reads both inbound folders, writes error reports, archives processed files.
    # Same logical paths as the local atmoz/sftp container, so application.yml needs no change per environment.
    "pifa-worker" = {
      ssh_public_keys = var.sftp_worker_public_keys
      directories = {
        "/inbound"  = "inbound"
        "/outbound" = "outbound"
        "/archive"  = "archive"
      }
      allow_read   = true
      allow_delete = true # needed to move (copy + delete) a processed file from /inbound to /archive
    }
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Secrets Manager shells (values set out of band; synced to Kubernetes by External Secrets).
# ---------------------------------------------------------------------------------------------------------------
module "secrets" {
  source      = "./modules/secrets"
  prefix      = local.secrets_root
  kms_key_arn = module.kms_secrets.key_arn

  secrets = {
    "db/app"                            = "PIFA DB application user (JSON: username, password). Created by the DB migration job."
    "db/keycloak"                       = "Keycloak DB user (JSON: username, password)."
    "keycloak/pifa-automation-secret"   = "Client secret of the pifa-automation service account (CI smoke tests)."
    "keycloak/pifa-admin-reader-secret" = "Client secret of pifa-admin-reader (PIFA_KEYCLOAK_READER_SECRET)."
    "keycloak/terraform-admin-secret"   = "Client secret Terraform/CI uses to apply the Keycloak realm (infra/terraform/keycloak)."
    "keycloak/admin-bootstrap"          = "Keycloak bootstrap admin credentials (break-glass only)."
    "sftp/pifa-worker-private-key"      = "OpenSSH private key of the pifa-worker SFTP account (PIFA_SFTP_PRIVATE_KEY)."
    "sftp/known-hosts"                  = "known_hosts line for the Transfer Family host key (PIFA_SFTP_KNOWN_HOSTS)."
    "mail/smtp"                         = "SMTP credentials (SES SMTP user or corporate relay): host, port, username, password."
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Mail. With mail_mode = "ses" an SES domain identity (DKIM) is created; publish the DKIM CNAMEs from the output in
# DNS and request SES production access once per account. The app keeps using plain SMTP (Spring Mail) against the
# SES SMTP endpoint, with credentials from the mail/smtp secret. With mail_mode = "smtp" nothing is created here
# and the corporate relay is configured through that same secret.
# ---------------------------------------------------------------------------------------------------------------
resource "aws_sesv2_email_identity" "mail" {
  count          = var.mail_mode == "ses" ? 1 : 0
  email_identity = var.mail_from_domain
}
