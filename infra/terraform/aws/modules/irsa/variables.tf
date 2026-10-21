# Purpose: inputs of the IRSA role module.

variable "role_name" {
  description = "IAM role name."
  type        = string
}

variable "oidc_provider_arn" {
  description = "ARN of the cluster's IAM OIDC provider."
  type        = string
}

variable "oidc_issuer_url" {
  description = "Cluster OIDC issuer URL (with https://)."
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace of the service account."
  type        = string
}

variable "service_account" {
  description = "Kubernetes service account name allowed to assume the role."
  type        = string
}

variable "policy_json" {
  description = "Inline IAM permission policy (JSON) - the complete, least-privilege set of permissions for this workload."
  type        = string
}
