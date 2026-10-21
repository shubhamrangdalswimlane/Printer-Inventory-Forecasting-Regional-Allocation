# Purpose: inputs of the S3 files bucket module.

variable "bucket_name" {
  description = "Globally unique name of the primary files bucket."
  type        = string
}

variable "dr_bucket_name" {
  description = "Globally unique name of the DR replica bucket (created in the DR region)."
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key ARN (primary region) for default bucket encryption."
  type        = string
}

variable "dr_kms_key_arn" {
  description = "KMS key ARN in the DR region for the replica bucket."
  type        = string
}

variable "object_lock_enabled" {
  description = "Enable S3 Object Lock (WORM). Can only be set when the bucket is created."
  type        = bool
  default     = false
}

variable "object_lock_retention_days" {
  description = "Default Object Lock GOVERNANCE retention in days (only used when object_lock_enabled)."
  type        = number
  default     = 1095
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which noncurrent object versions are permanently removed."
  type        = number
  default     = 90
}

variable "deleted_prefix_expiration_days" {
  description = "Days after which objects under deleted/ expire."
  type        = number
  default     = 90
}

variable "staging_prefix_expiration_days" {
  description = "Days after which abandoned objects under staging/ expire."
  type        = number
  default     = 7
}
