# Purpose: values the deploy pipeline and operators need after apply (Helm values, ExternalSecret manifests,
# upstream SFTP onboarding, DNS). No secret values are output.

output "vpc_id" {
  description = "VPC ID."
  value       = module.network.vpc_id
}

output "nat_public_ips" {
  description = "Egress IPs of the cluster (for partner allowlists, e.g. SMTP relay or corporate IdP)."
  value       = module.network.nat_public_ips
}

output "eks_cluster_name" {
  description = "EKS cluster name (aws eks update-kubeconfig --name ...)."
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "Kubernetes API endpoint."
  value       = module.eks.cluster_endpoint
}

output "app_irsa_role_arn" {
  description = "Annotate the pifa-app service account with this role (eks.amazonaws.com/role-arn)."
  value       = module.irsa_app.role_arn
}

output "external_secrets_irsa_role_arn" {
  description = "Annotate the External Secrets Operator service account with this role."
  value       = module.irsa_external_secrets.role_arn
}

output "app_db_endpoint" {
  description = "PIFA DB writer endpoint (host:port) for SPRING_DATASOURCE_URL."
  value       = module.db_app.endpoint
}

output "keycloak_db_endpoint" {
  description = "Keycloak DB writer endpoint (host:port) for KC_DB_URL."
  value       = module.db_keycloak.endpoint
}

output "app_db_master_secret_arn" {
  description = "RDS-managed master credential secret of the PIFA DB (break-glass / migrations bootstrap only)."
  value       = module.db_app.master_user_secret_arn
}

output "keycloak_db_master_secret_arn" {
  description = "RDS-managed master credential secret of the Keycloak DB."
  value       = module.db_keycloak.master_user_secret_arn
}

output "files_bucket_name" {
  description = "Files bucket name for PIFA_S3_BUCKET."
  value       = module.s3.bucket_name
}

output "files_dr_bucket_name" {
  description = "DR replica bucket name."
  value       = module.s3.dr_bucket_name
}

output "data_kms_key_arn" {
  description = "KMS key of the files bucket."
  value       = module.kms_data.key_arn
}

output "sftp_endpoint" {
  description = "SFTP server host name (PIFA_SFTP_HOST inside the VPC resolves to the private endpoint IPs)."
  value       = module.sftp.endpoint
}

output "sftp_public_ips" {
  description = "Static public IPs of the SFTP endpoint for upstream systems."
  value       = module.sftp.public_ips
}

output "sftp_host_key_fingerprint" {
  description = "Host key fingerprint to verify in known_hosts (strict host-key checking by the worker and upstreams)."
  value       = module.sftp.host_key_fingerprint
}

output "secret_names" {
  description = "Secrets Manager names to reference from ExternalSecret manifests."
  value       = module.secrets.secret_names
}

output "ses_dkim_tokens" {
  description = "SES DKIM tokens; publish <token>._domainkey.<domain> CNAME <token>.dkim.amazonses.com in DNS."
  value       = var.mail_mode == "ses" ? aws_sesv2_email_identity.mail[0].dkim_signing_attributes[0].tokens : []
}
