# Purpose: one customer-managed KMS key with an alias and automatic yearly rotation.
# Customer-managed (not AWS-managed) keys are used so that (a) key access is controlled by our key policy and IAM,
# (b) every use is visible in CloudTrail, and (c) the key can be disabled to crypto-shred an environment.
# The module is instantiated several times (data, rds, secrets, eks, logs, DR) so a compromise of one IAM grant
# never unlocks every kind of data; the key policy below delegates to IAM and optionally lets AWS services in.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

data "aws_iam_policy_document" "this" {
  # Standard "enable IAM policies" statement: the account root can administer the key, and IAM policies
  # (like the IRSA roles in the root module) decide which principals can use it.
  statement {
    sid       = "EnableIamPolicies"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  # CloudWatch Logs cannot use IAM to reach a key; it must be allowed in the key policy, scoped to this account's log groups.
  dynamic "statement" {
    for_each = var.allow_cloudwatch_logs ? [1] : []
    content {
      sid       = "AllowCloudWatchLogs"
      actions   = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
      resources = ["*"]
      principals {
        type        = "Service"
        identifiers = ["logs.${data.aws_region.current.name}.amazonaws.com"]
      }
      condition {
        test     = "ArnLike"
        variable = "kms:EncryptionContext:aws:logs:arn"
        values   = ["arn:${data.aws_partition.current.partition}:logs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:log-group:*"]
      }
    }
  }

  # EKS managed node groups launch instances through EC2 Auto Scaling. Its service-linked role cannot get IAM
  # policies attached, so encrypted root volumes only work if the key policy itself lets that role use the key.
  dynamic "statement" {
    for_each = var.allow_autoscaling_ebs ? [1] : []
    content {
      sid       = "AllowAutoScalingEbs"
      actions   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey", "kms:CreateGrant"]
      resources = ["*"]
      principals {
        type        = "AWS"
        identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"]
      }
    }
  }
}

resource "aws_kms_key" "this" {
  description             = var.description
  enable_key_rotation     = true # yearly automatic rotation of the backing key material; ARN/alias stay the same
  rotation_period_in_days = var.rotation_period_in_days
  deletion_window_in_days = var.deletion_window_in_days
  policy                  = data.aws_iam_policy_document.this.json
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.alias}"
  target_key_id = aws_kms_key.this.key_id
}
