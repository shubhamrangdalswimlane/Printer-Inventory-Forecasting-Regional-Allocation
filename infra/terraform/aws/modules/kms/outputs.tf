# Purpose: identifiers of the KMS key for use by other modules and IAM policies.

output "key_arn" {
  description = "KMS key ARN."
  value       = aws_kms_key.this.arn
}

output "key_id" {
  description = "KMS key ID."
  value       = aws_kms_key.this.key_id
}

output "alias_arn" {
  description = "KMS alias ARN."
  value       = aws_kms_alias.this.arn
}
