# Purpose: secret names/ARNs for ExternalSecret manifests.

output "secret_arns" {
  description = "Map of relative secret name to secret ARN."
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.arn }
}

output "secret_names" {
  description = "Map of relative secret name to full secret name."
  value       = { for k, s in aws_secretsmanager_secret.this : k => s.name }
}
