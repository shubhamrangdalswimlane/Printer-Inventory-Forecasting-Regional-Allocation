# Purpose: inputs of the EKS module.

variable "cluster_name" {
  description = "EKS cluster name, e.g. pifa-prod."
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes minor version of the control plane, e.g. 1.31."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID the cluster runs in."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs (one per AZ) for the control-plane ENIs and the node group."
  type        = list(string)
}

variable "admin_principal_arns" {
  description = "IAM role/user ARNs granted cluster-admin through EKS access entries (platform SSO role, CI deploy role)."
  type        = list(string)
  default     = []
}

variable "secrets_kms_key_arn" {
  description = "KMS key ARN for envelope encryption of Kubernetes Secrets."
  type        = string
}

variable "ebs_kms_key_arn" {
  description = "KMS key ARN for node root volumes."
  type        = string
}

variable "logs_kms_key_arn" {
  description = "KMS key ARN for the control-plane log group."
  type        = string
}

variable "log_retention_days" {
  description = "Retention in days of the control-plane log group."
  type        = number
}

variable "node_instance_types" {
  description = "Instance types of the managed node group."
  type        = list(string)
}

variable "node_min_size" {
  description = "Minimum number of nodes."
  type        = number
}

variable "node_desired_size" {
  description = "Initial desired number of nodes (afterwards owned by the autoscaler)."
  type        = number
}

variable "node_max_size" {
  description = "Maximum number of nodes."
  type        = number
}

variable "node_disk_size_gb" {
  description = "Root volume size of each node in GiB."
  type        = number
  default     = 50
}
