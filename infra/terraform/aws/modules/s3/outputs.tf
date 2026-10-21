# Purpose: bucket identifiers for IAM policies, the SFTP server and the app configuration (PIFA_S3_BUCKET).

output "bucket_name" {
  description = "Primary bucket name."
  value       = aws_s3_bucket.this.id
}

output "bucket_arn" {
  description = "Primary bucket ARN."
  value       = aws_s3_bucket.this.arn
}

output "dr_bucket_name" {
  description = "DR replica bucket name."
  value       = aws_s3_bucket.dr.id
}

output "dr_bucket_arn" {
  description = "DR replica bucket ARN."
  value       = aws_s3_bucket.dr.arn
}
