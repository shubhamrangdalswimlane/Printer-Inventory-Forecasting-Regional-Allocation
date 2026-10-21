# Purpose: network IDs consumed by the EKS, RDS and SFTP modules.

output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "VPC CIDR block."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnet IDs (load balancer, NAT, SFTP endpoint)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnet IDs (EKS nodes)."
  value       = aws_subnet.private[*].id
}

output "isolated_subnet_ids" {
  description = "Isolated subnet IDs (RDS)."
  value       = aws_subnet.isolated[*].id
}

output "nat_public_ips" {
  description = "NAT gateway public IPs; give these to upstream partners if they allowlist PIFA's egress (e.g. SMTP relay, IdP)."
  value       = aws_eip.nat[*].public_ip
}
