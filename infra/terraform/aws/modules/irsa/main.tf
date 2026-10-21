# Purpose: one IAM role for a single Kubernetes service account (IRSA).
# The trust policy pins BOTH the namespace and the service-account name (StringEquals on "sub") and the audience,
# so no other pod in the cluster - even in the same namespace - can assume the role.
# The permission policy is passed in as JSON so each caller states exactly the least-privilege actions it needs.

locals {
  issuer_host = replace(var.oidc_issuer_url, "https://", "")
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.issuer_host}:sub"
      values   = ["system:serviceaccount:${var.namespace}:${var.service_account}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${local.issuer_host}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name                 = var.role_name
  description          = "IRSA role for ${var.namespace}/${var.service_account}"
  assume_role_policy   = data.aws_iam_policy_document.assume.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy" "this" {
  name   = "least-privilege"
  role   = aws_iam_role.this.id
  policy = var.policy_json
}
