# Purpose: inputs of the RDS PostgreSQL module.

variable "identifier" {
  description = "RDS instance identifier, e.g. pifa-prod-app or pifa-prod-keycloak."
  type        = string
}

variable "db_name" {
  description = "Initial database name."
  type        = string
}

variable "master_username" {
  description = "Master user name (password is generated and managed by RDS in Secrets Manager)."
  type        = string
  default     = "pifa_admin"
}

variable "engine_version" {
  description = "PostgreSQL engine version (major 16), e.g. 16.4."
  type        = string
}

variable "instance_class" {
  description = "RDS instance class, e.g. db.m6g.large."
  type        = string
}

variable "allocated_storage_gb" {
  description = "Initial storage in GiB."
  type        = number
}

variable "max_allocated_storage_gb" {
  description = "Upper bound for storage autoscaling in GiB."
  type        = number
}

variable "multi_az" {
  description = "Deploy a synchronous standby in a second AZ with automatic failover."
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Block deletion of the instance through the API/Terraform."
  type        = bool
  default     = true
}

variable "backup_retention_days" {
  description = "Automated backup (and PITR) retention in days."
  type        = number
  default     = 35
}

variable "dr_backup_retention_days" {
  description = "Retention in days of the automated backups replicated to the DR region."
  type        = number
  default     = 7
}

variable "monthly_backup_retention_days" {
  description = "Retention in days of the monthly AWS Backup recovery points (and their DR copies)."
  type        = number
  default     = 365
}

variable "performance_insights_retention_days" {
  description = "Performance Insights retention: 7 (free tier) or a multiple of 31 up to 731."
  type        = number
  default     = 7
}

variable "log_min_duration_statement_ms" {
  description = "Log statements slower than this many milliseconds (-1 disables)."
  type        = number
  default     = 500
}

variable "vpc_id" {
  description = "VPC ID."
  type        = string
}

variable "subnet_ids" {
  description = "Isolated subnet IDs for the DB subnet group."
  type        = list(string)
}

variable "allowed_security_group_ids" {
  description = "Security groups allowed to connect on 5432 (the EKS cluster security group)."
  type        = list(string)
}

variable "kms_key_arn" {
  description = "KMS key ARN for storage, Performance Insights and the backup vault."
  type        = string
}

variable "secrets_kms_key_arn" {
  description = "KMS key ARN for the RDS-managed master password secret."
  type        = string
}

variable "dr_kms_key_arn" {
  description = "KMS key ARN in the DR region for replicated backups."
  type        = string
}

variable "logs_kms_key_arn" {
  description = "KMS key ARN for the PostgreSQL CloudWatch log groups."
  type        = string
}

variable "log_retention_days" {
  description = "Retention in days of the PostgreSQL CloudWatch log groups."
  type        = number
}
