# Purpose: the role ARN to put in the service account annotation eks.amazonaws.com/role-arn.

output "role_arn" {
  description = "IAM role ARN for the service-account annotation."
  value       = aws_iam_role.this.arn
}

output "role_name" {
  description = "IAM role name."
  value       = aws_iam_role.this.name
}
