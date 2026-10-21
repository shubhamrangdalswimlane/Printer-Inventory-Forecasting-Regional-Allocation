# Purpose: inputs of the AWS Transfer Family SFTP module.

variable "name" {
  description = "Name prefix, e.g. pifa-prod."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID for the server endpoint."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR allowed to connect (the pifa-worker pods)."
  type        = string
}

variable "subnet_ids" {
  description = "Subnets for the endpoint ENIs (public subnets when internet_facing)."
  type        = list(string)
}

variable "internet_facing" {
  description = "Attach Elastic IPs so upstream systems outside AWS can connect (still limited by allowed_cidrs)."
  type        = bool
  default     = true
}

variable "allowed_cidrs" {
  description = "Upstream source CIDRs allowed to connect on port 22 (IP allowlist)."
  type        = list(string)
}

variable "security_policy_name" {
  description = "Transfer Family security policy (allowed SSH ciphers/KEX/MACs)."
  type        = string
  default     = "TransferSecurityPolicy-2024-01"
}

variable "bucket_name" {
  description = "Name of the S3 bucket backing the SFTP server."
  type        = string
}

variable "prefix" {
  description = "Bucket prefix under which all SFTP paths are stored (no leading/trailing slash)."
  type        = string
  default     = "sftp"
}

variable "kms_key_arn" {
  description = "KMS key ARN of the bucket's default encryption."
  type        = string
}

variable "users" {
  description = <<-EOT
    SFTP accounts. directories maps the path the client sees (logical entry, e.g. /inbound/forecast) to a path
    under <bucket>/<prefix>/ (e.g. inbound/forecast). allow_read/allow_delete widen the default upload-only rights.
  EOT
  type = map(object({
    ssh_public_keys = list(string)
    directories     = map(string)
    allow_read      = bool
    allow_delete    = bool
  }))
}

variable "log_retention_days" {
  description = "Retention in days of the Transfer Family log group."
  type        = number
}

variable "logs_kms_key_arn" {
  description = "KMS key ARN for the log group."
  type        = string
}
