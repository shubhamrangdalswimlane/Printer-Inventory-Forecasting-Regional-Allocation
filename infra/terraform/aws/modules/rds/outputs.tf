# Purpose: connection details for the application configuration (no secrets: the password is in Secrets Manager).

output "endpoint" {
  description = "Writer endpoint host:port (follows the primary on Multi-AZ failover)."
  value       = aws_db_instance.this.endpoint
}

output "address" {
  description = "Writer endpoint host name."
  value       = aws_db_instance.this.address
}

output "arn" {
  description = "DB instance ARN."
  value       = aws_db_instance.this.arn
}

output "master_user_secret_arn" {
  description = "ARN of the RDS-managed Secrets Manager secret holding the master credentials."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "security_group_id" {
  description = "Security group of the DB."
  value       = aws_security_group.this.id
}
