# Purpose: all inputs of the PIFA AWS environment. Example values per environment are in envs/*.tfvars.
# None of these variables holds a secret: SSH *public* keys and CIDRs only. Secret values live in Secrets Manager.

# ---------------------------------------------------------------------------------------------------------------
# General
# ---------------------------------------------------------------------------------------------------------------
variable "project" {
  description = "Project short name, used as the prefix of every resource name."
  type        = string
  default     = "pifa"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.project))
    error_message = "project must be 2-16 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "environment" {
  description = "Environment name (architecture.md 13.2). qa/local are not provisioned in AWS by this stack."
  type        = string

  validation {
    condition     = contains(["dev", "uat", "prod"], var.environment)
    error_message = "environment must be one of dev, uat, prod."
  }
}

variable "aws_region" {
  description = "Primary AWS region, e.g. eu-west-1."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.aws_region))
    error_message = "aws_region must look like eu-west-1."
  }
}

variable "dr_region" {
  description = "Disaster-recovery region for S3 replication and RDS backup replication. Must differ from aws_region."
  type        = string

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.dr_region))
    error_message = "dr_region must look like eu-central-1."
  }
}

variable "tags" {
  description = "Extra tags applied to every resource (cost centre, owner, data classification)."
  type        = map(string)
  default     = {}
}

variable "log_retention_days" {
  description = "Retention of every CloudWatch log group created by this stack (VPC flow logs, EKS, RDS, SFTP)."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a value CloudWatch Logs accepts (e.g. 30, 90, 365, 731)."
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Network
# ---------------------------------------------------------------------------------------------------------------
variable "vpc_cidr" {
  description = "VPC CIDR. A /16 leaves room for pod IPs (VPC CNI)."
  type        = string
  default     = "10.40.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) <= 18
    error_message = "vpc_cidr must be a valid IPv4 CIDR of size /18 or larger."
  }
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway across AZs (cost saving for dev only; prod must use one per AZ)."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------------------------------------------
# EKS
# ---------------------------------------------------------------------------------------------------------------
variable "kubernetes_version" {
  description = "EKS Kubernetes version."
  type        = string
  default     = "1.31"

  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.kubernetes_version))
    error_message = "kubernetes_version must look like 1.31."
  }
}

variable "eks_admin_principal_arns" {
  description = "IAM role ARNs granted cluster-admin through EKS access entries (platform SSO role, CI deploy role)."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for a in var.eks_admin_principal_arns : can(regex("^arn:aws[a-z-]*:iam::[0-9]{12}:(role|user)/.+$", a))])
    error_message = "eks_admin_principal_arns must be IAM role or user ARNs."
  }
}

variable "node_instance_types" {
  description = "Instance types for the managed node group."
  type        = list(string)
  default     = ["m6i.large"]
}

variable "node_min_size" {
  description = "Minimum node count. At least 3 in prod so each AZ has a node for the x2 replicas + anti-affinity."
  type        = number
  default     = 3

  validation {
    condition     = var.node_min_size >= 1
    error_message = "node_min_size must be at least 1."
  }
}

variable "node_desired_size" {
  description = "Initial desired node count."
  type        = number
  default     = 3
}

variable "node_max_size" {
  description = "Maximum node count for the autoscaler."
  type        = number
  default     = 6
}

# Kubernetes identities that get IAM roles through IRSA.
variable "app_namespace" {
  description = "Namespace of the PIFA API and worker."
  type        = string
  default     = "pifa"
}

variable "app_service_account" {
  description = "Service account used by pifa-api and pifa-worker pods (both read/write the files bucket)."
  type        = string
  default     = "pifa-app"
}

variable "external_secrets_namespace" {
  description = "Namespace of the External Secrets Operator."
  type        = string
  default     = "external-secrets"
}

variable "external_secrets_service_account" {
  description = "Service account of the External Secrets Operator controller."
  type        = string
  default     = "external-secrets"
}

# ---------------------------------------------------------------------------------------------------------------
# Databases
# ---------------------------------------------------------------------------------------------------------------
variable "db_engine_version" {
  description = "PostgreSQL 16 minor version for both databases."
  type        = string
  default     = "16.4"

  validation {
    condition     = can(regex("^16\\.[0-9]+$", var.db_engine_version))
    error_message = "db_engine_version must be a PostgreSQL 16 version such as 16.4."
  }
}

variable "app_db_instance_class" {
  description = "Instance class of the PIFA application database."
  type        = string
  default     = "db.m6g.large"

  validation {
    condition     = startswith(var.app_db_instance_class, "db.")
    error_message = "app_db_instance_class must be an RDS class such as db.m6g.large."
  }
}

variable "keycloak_db_instance_class" {
  description = "Instance class of the Keycloak database (small: sessions are in Infinispan, the DB holds config/users)."
  type        = string
  default     = "db.t4g.medium"

  validation {
    condition     = startswith(var.keycloak_db_instance_class, "db.")
    error_message = "keycloak_db_instance_class must be an RDS class such as db.t4g.medium."
  }
}

variable "db_allocated_storage_gb" {
  description = "Initial storage of each database in GiB."
  type        = number
  default     = 50

  validation {
    condition     = var.db_allocated_storage_gb >= 20
    error_message = "db_allocated_storage_gb must be at least 20."
  }
}

variable "db_max_allocated_storage_gb" {
  description = "Storage autoscaling ceiling in GiB."
  type        = number
  default     = 500
}

variable "db_multi_az" {
  description = "Run each database Multi-AZ (synchronous standby, automatic failover). Required in uat/prod."
  type        = bool
  default     = true
}

variable "db_deletion_protection" {
  description = "Enable RDS deletion protection. Required in prod."
  type        = bool
  default     = true
}

variable "db_backup_retention_days" {
  description = "Automated backup / PITR retention (architecture.md 14.1: 35 days)."
  type        = number
  default     = 35

  validation {
    condition     = var.db_backup_retention_days >= 7 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days must be between 7 and 35."
  }
}

variable "db_dr_backup_retention_days" {
  description = "Retention of automated backups replicated to the DR region."
  type        = number
  default     = 7

  validation {
    condition     = var.db_dr_backup_retention_days >= 1 && var.db_dr_backup_retention_days <= 35
    error_message = "db_dr_backup_retention_days must be between 1 and 35."
  }
}

variable "db_log_min_duration_statement_ms" {
  description = "PostgreSQL log_min_duration_statement in ms: statements slower than this are logged."
  type        = number
  default     = 500

  validation {
    condition     = var.db_log_min_duration_statement_ms >= -1
    error_message = "db_log_min_duration_statement_ms must be -1 (off) or a positive number of milliseconds."
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Object storage
# ---------------------------------------------------------------------------------------------------------------
variable "files_bucket_name" {
  description = "Name of the files bucket. Null derives <project>-<env>-files-<account id> (globally unique)."
  type        = string
  default     = null

  validation {
    condition     = var.files_bucket_name == null || can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.files_bucket_name))
    error_message = "files_bucket_name must be a valid S3 bucket name."
  }
}

variable "object_lock_enabled" {
  description = "Enable S3 Object Lock (WORM, GOVERNANCE mode) on the files bucket. Only effective at bucket creation."
  type        = bool
  default     = false
}

variable "noncurrent_version_expiration_days" {
  description = "Days before noncurrent object versions are removed."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_version_expiration_days >= 1
    error_message = "noncurrent_version_expiration_days must be at least 1."
  }
}

# ---------------------------------------------------------------------------------------------------------------
# SFTP
# ---------------------------------------------------------------------------------------------------------------
variable "sftp_allowed_cidrs" {
  description = "Upstream system CIDRs allowed to connect to the SFTP endpoint (IP allowlist)."
  type        = list(string)

  validation {
    condition     = length(var.sftp_allowed_cidrs) > 0 && alltrue([for c in var.sftp_allowed_cidrs : can(cidrhost(c, 0)) && c != "0.0.0.0/0"])
    error_message = "sftp_allowed_cidrs must contain at least one valid CIDR and must not be 0.0.0.0/0."
  }
}

variable "sftp_forecast_public_keys" {
  description = "SSH public keys of the upstream forecast system (sftp-forecast account)."
  type        = list(string)

  validation {
    condition     = length(var.sftp_forecast_public_keys) > 0 && alltrue([for k in var.sftp_forecast_public_keys : can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]+", k))])
    error_message = "sftp_forecast_public_keys must contain OpenSSH public keys (ssh-ed25519, ssh-rsa or ecdsa)."
  }
}

variable "sftp_inventory_public_keys" {
  description = "SSH public keys of the upstream inventory system (sftp-inventory account)."
  type        = list(string)

  validation {
    condition     = length(var.sftp_inventory_public_keys) > 0 && alltrue([for k in var.sftp_inventory_public_keys : can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]+", k))])
    error_message = "sftp_inventory_public_keys must contain OpenSSH public keys (ssh-ed25519, ssh-rsa or ecdsa)."
  }
}

variable "sftp_worker_public_keys" {
  description = "SSH public keys of the PIFA worker (pifa-worker account). The private key is in Secrets Manager."
  type        = list(string)

  validation {
    condition     = length(var.sftp_worker_public_keys) > 0 && alltrue([for k in var.sftp_worker_public_keys : can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)) [A-Za-z0-9+/=]+", k))])
    error_message = "sftp_worker_public_keys must contain OpenSSH public keys (ssh-ed25519, ssh-rsa or ecdsa)."
  }
}

# ---------------------------------------------------------------------------------------------------------------
# Mail
# ---------------------------------------------------------------------------------------------------------------
variable "mail_mode" {
  description = "How PIFA sends mail: ses (create an SES domain identity here) or smtp (corporate relay configured out of band)."
  type        = string
  default     = "ses"

  validation {
    condition     = contains(["ses", "smtp"], var.mail_mode)
    error_message = "mail_mode must be ses or smtp."
  }
}

variable "mail_from_domain" {
  description = "Domain PIFA sends mail from (SES identity when mail_mode = ses), e.g. pifa.example.com."
  type        = string
  default     = "pifa.example.com"

  validation {
    condition     = can(regex("^([a-z0-9-]+\\.)+[a-z]{2,}$", var.mail_from_domain))
    error_message = "mail_from_domain must be a DNS domain name."
  }
}
