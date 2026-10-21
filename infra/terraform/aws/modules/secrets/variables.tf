# Purpose: inputs of the secrets module.

variable "prefix" {
  description = "Name prefix of every secret, e.g. pifa/prod. External Secrets is allowed to read only this prefix."
  type        = string
}

variable "secrets" {
  description = "Map of secret name (relative to prefix) to a description of its expected content."
  type        = map(string)
}

variable "kms_key_arn" {
  description = "KMS key ARN encrypting the secrets."
  type        = string
}

variable "recovery_window_in_days" {
  description = "Days a deleted secret stays recoverable (7-30)."
  type        = number
  default     = 30
}
