# Purpose: inputs of the KMS key module.

variable "alias" {
  description = "Alias name without the alias/ prefix, e.g. pifa-prod-data."
  type        = string
}

variable "description" {
  description = "Human-readable description of what the key protects."
  type        = string
}

variable "deletion_window_in_days" {
  description = "Waiting period before a scheduled key deletion takes effect (7-30). 30 gives the most time to cancel a mistake."
  type        = number
  default     = 30
}

variable "rotation_period_in_days" {
  description = "Automatic rotation period in days (90-2560)."
  type        = number
  default     = 365
}

variable "allow_cloudwatch_logs" {
  description = "Add a key-policy statement letting CloudWatch Logs in this region use the key for log groups of this account."
  type        = bool
  default     = false
}

variable "allow_autoscaling_ebs" {
  description = "Add a key-policy statement letting the EC2 Auto Scaling service-linked role use the key (encrypted EKS node volumes)."
  type        = bool
  default     = false
}
