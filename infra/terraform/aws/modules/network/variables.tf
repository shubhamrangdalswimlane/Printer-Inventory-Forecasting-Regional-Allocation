# Purpose: inputs of the network module (VPC, subnets, NAT, flow logs).

variable "name" {
  description = "Name prefix for all network resources, e.g. pifa-prod."
  type        = string
}

variable "region" {
  description = "AWS region of the VPC; used to build VPC endpoint service names."
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster name; subnets are tagged with it so the AWS Load Balancer Controller can discover them."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR block. A /16 is recommended because the VPC CNI gives every pod a VPC IP."
  type        = string
}

variable "az_count" {
  description = "Number of Availability Zones to span (3 for PIFA: survives one AZ loss with quorum-friendly spread)."
  type        = number
  default     = 3
}

variable "single_nat_gateway" {
  description = "Use one shared NAT gateway instead of one per AZ (cheaper, but an AZ outage cuts egress). Only for dev."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "Retention in days for the VPC flow log group."
  type        = number
}

variable "logs_kms_key_arn" {
  description = "KMS key ARN used to encrypt CloudWatch log groups."
  type        = string
}
