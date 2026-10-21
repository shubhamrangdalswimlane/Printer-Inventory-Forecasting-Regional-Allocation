# Purpose: the PIFA files bucket (original uploaded/ingested Excel files, exports, and the SFTP landing area under
# the sftp/ prefix used by AWS Transfer Family), plus its cross-region DR replica.
#
# Why each control exists:
#   - Versioning: an overwrite or delete never destroys the previous object; it becomes a noncurrent version that
#     can be restored (architecture.md 14.1). Also a hard requirement for cross-region replication.
#   - SSE-KMS default encryption with a bucket key: every object is encrypted with our customer key; the bucket key
#     cuts KMS request costs by ~99 % for the many small reads the worker does.
#   - Block Public Access (all four flags) + BucketOwnerEnforced: no ACL or policy can ever make an object public.
#   - TLS-only bucket policy: S3 still accepts plain HTTP by default; denying aws:SecureTransport=false guarantees
#     that files with commercial forecast data are never sent unencrypted over any network (security table, ch. 12).
#   - Lifecycle: noncurrent versions expire after 90 days (restore window, bounded cost); deleted/ and staging/
#     prefixes are cleaned automatically as a safety net behind the application's own purge job.
#   - Cross-region replication with S3 Replication Time Control: 99.99 % of objects replicated within 15 min,
#     which matches the RPO 15 min target for files.
#   - Object Lock (optional): WORM retention if compliance demands it. It can only be enabled at bucket creation.

terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      configuration_aliases = [aws.dr]
    }
  }
}

data "aws_partition" "current" {}

# ---------------------------------------------------------------------------------------------------------------
# Primary bucket
# ---------------------------------------------------------------------------------------------------------------
resource "aws_s3_bucket" "this" {
  bucket              = var.bucket_name
  object_lock_enabled = var.object_lock_enabled
  force_destroy       = false # never delete a bucket that still holds files
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    object_ownership = "BucketOwnerEnforced" # disables ACLs entirely
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "this" {
  count  = var.object_lock_enabled ? 1 : 0
  bucket = aws_s3_bucket.this.id

  rule {
    default_retention {
      # GOVERNANCE (not COMPLIANCE): privileged admins with s3:BypassGovernanceRetention can still remove an
      # object, e.g. for a legal data-deletion request. Switch to COMPLIANCE only if legal requires it.
      mode = "GOVERNANCE"
      days = var.object_lock_retention_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # Soft-deleted files: the app moves them to deleted/ and purges after 90 days; this rule guarantees the purge.
  rule {
    id     = "clean-deleted-prefix"
    status = "Enabled"
    filter {
      prefix = "deleted/"
    }
    expiration {
      days = var.deleted_prefix_expiration_days
    }
  }

  # staging/ holds in-flight uploads awaiting virus scan/validation; anything left there is an abandoned upload.
  rule {
    id     = "clean-staging-prefix"
    status = "Enabled"
    filter {
      prefix = "staging/"
    }
    expiration {
      days = var.staging_prefix_expiration_days
    }
  }

  # Remove delete markers that no longer hide any version (keeps listings fast).
  rule {
    id     = "remove-expired-delete-markers"
    status = "Enabled"
    filter {}
    expiration {
      expired_object_delete_marker = true
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

data "aws_iam_policy_document" "tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.this.arn,
      "${aws_s3_bucket.this.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  # Also refuse old TLS versions (security table: TLS 1.2+).
  statement {
    sid     = "DenyOldTls"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.this.arn,
      "${aws_s3_bucket.this.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "NumericLessThan"
      variable = "s3:TlsVersion"
      values   = ["1.2"]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.tls_only.json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

# ---------------------------------------------------------------------------------------------------------------
# DR bucket (in the DR region) - same protections, encrypted with the DR region key.
# ---------------------------------------------------------------------------------------------------------------
resource "aws_s3_bucket" "dr" {
  provider      = aws.dr
  bucket        = var.dr_bucket_name
  force_destroy = false
}

resource "aws_s3_bucket_ownership_controls" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "dr" {
  provider                = aws.dr
  bucket                  = aws_s3_bucket.dr.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.dr_kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.dr]
}

data "aws_iam_policy_document" "dr_tls_only" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.dr.arn,
      "${aws_s3_bucket.dr.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "dr" {
  provider = aws.dr
  bucket   = aws_s3_bucket.dr.id
  policy   = data.aws_iam_policy_document.dr_tls_only.json

  depends_on = [aws_s3_bucket_public_access_block.dr]
}

# ---------------------------------------------------------------------------------------------------------------
# Replication role and rule
# ---------------------------------------------------------------------------------------------------------------
data "aws_iam_policy_document" "replication_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "replication" {
  name               = "${var.bucket_name}-replication"
  assume_role_policy = data.aws_iam_policy_document.replication_assume.json
}

data "aws_iam_policy_document" "replication" {
  statement {
    actions   = ["s3:GetReplicationConfiguration", "s3:ListBucket"]
    resources = [aws_s3_bucket.this.arn]
  }
  statement {
    actions   = ["s3:GetObjectVersionForReplication", "s3:GetObjectVersionAcl", "s3:GetObjectVersionTagging"]
    resources = ["${aws_s3_bucket.this.arn}/*"]
  }
  statement {
    actions   = ["s3:ReplicateObject", "s3:ReplicateDelete", "s3:ReplicateTags"]
    resources = ["${aws_s3_bucket.dr.arn}/*"]
  }
  statement {
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]
  }
  statement {
    actions   = ["kms:Encrypt", "kms:GenerateDataKey"]
    resources = [var.dr_kms_key_arn]
  }
}

resource "aws_iam_role_policy" "replication" {
  name   = "replicate-to-dr"
  role   = aws_iam_role.replication.id
  policy = data.aws_iam_policy_document.replication.json
}

resource "aws_s3_bucket_replication_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  role   = aws_iam_role.replication.arn

  rule {
    id       = "replicate-all-to-dr"
    status   = "Enabled"
    priority = 1
    filter {}

    # Replicate deletes markers too, so the DR copy mirrors soft deletes; versions remain recoverable there.
    delete_marker_replication {
      status = "Enabled"
    }

    source_selection_criteria {
      sse_kms_encrypted_objects {
        status = "Enabled"
      }
    }

    destination {
      bucket        = aws_s3_bucket.dr.arn
      storage_class = "STANDARD_IA" # DR copy is rarely read; cheaper storage class
      encryption_configuration {
        replica_kms_key_id = var.dr_kms_key_arn
      }
      # Replication Time Control: SLA of 15 minutes, with metrics/alarms for late replication.
      replication_time {
        status = "Enabled"
        time {
          minutes = 15
        }
      }
      metrics {
        status = "Enabled"
        event_threshold {
          minutes = 15
        }
      }
    }
  }

  depends_on = [aws_s3_bucket_versioning.this, aws_s3_bucket_versioning.dr]
}
