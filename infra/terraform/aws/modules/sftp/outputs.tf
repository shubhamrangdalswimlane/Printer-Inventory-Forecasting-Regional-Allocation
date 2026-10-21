# Purpose: SFTP endpoint details for upstream onboarding and for the worker configuration
# (PIFA_SFTP_HOST, PIFA_SFTP_KNOWN_HOSTS for strict host-key verification).

output "server_id" {
  description = "Transfer Family server ID."
  value       = aws_transfer_server.this.id
}

output "endpoint" {
  description = "Server endpoint host name."
  value       = aws_transfer_server.this.endpoint
}

output "public_ips" {
  description = "Elastic IPs of the endpoint (give to upstream systems; they never change)."
  value       = aws_eip.this[*].public_ip
}

output "host_key_fingerprint" {
  description = "SHA256 fingerprint of the server host key, for the worker's known_hosts / strict host-key check."
  value       = aws_transfer_server.this.host_key_fingerprint
}

output "user_names" {
  description = "Configured SFTP user names."
  value       = keys(aws_transfer_user.this)
}
