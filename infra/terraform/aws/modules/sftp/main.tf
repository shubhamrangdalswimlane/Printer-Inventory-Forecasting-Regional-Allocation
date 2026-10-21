# Purpose: AWS Transfer Family SFTP server backed by the PIFA files bucket (architecture.md 5 and 13.3).
#
# How it fits the app:
#   - Upstream systems log in as sftp-forecast / sftp-inventory and drop .xlsx files into /inbound/forecast and
#     /inbound/inventory - the same paths as the local atmoz/sftp container, so nothing changes for the app.
#   - The PIFA worker's SFTP poller (SftpIngestPoller, application.yml pifa.sftp.*) logs in as pifa-worker over
#     plain SFTP, reads those SAME folders, writes rejected-file reports to /outbound/errors and moves processed
#     files to /archive. The app therefore does not need S3 permissions on the sftp/ prefix at all; Transfer Family
#     maps every SFTP path to an S3 key under s3://<bucket>/sftp/... on its behalf.
#   - Every account uses LOGICAL home directories: the user sees only the virtual paths mapped below and cannot
#     navigate ("cd ..") to any other prefix of the bucket. This is the managed-service equivalent of an OpenSSH
#     chroot, and it is enforced again by the per-user IAM role (defence in depth).
#   - Key-only authentication (SERVICE_MANAGED users with SSH public keys, no passwords), no shell (SFTP only),
#     IP allowlist through the endpoint security group, modern-cipher security policy.

data "aws_partition" "current" {}

locals {
  bucket_root = "/${var.bucket_name}/${var.prefix}"

  # Flatten user -> keys into one map so each key is its own resource (rotating one key does not touch others).
  ssh_keys = merge([
    for user, cfg in var.users : {
      for idx, key in cfg.ssh_public_keys : "${user}-${idx}" => { user = user, body = key }
    }
  ]...)
}

# ---------------------------------------------------------------------------------------------------------------
# Network: VPC endpoint with Elastic IPs (internet-facing) and an allowlisting security group
# ---------------------------------------------------------------------------------------------------------------
resource "aws_security_group" "this" {
  name        = "${var.name}-sftp"
  description = "SFTP (22) from allowlisted upstream IPs and from inside the VPC (pifa-worker)"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.name}-sftp" }
}

resource "aws_vpc_security_group_ingress_rule" "allowlist" {
  for_each          = toset(var.allowed_cidrs)
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  description       = "SFTP from allowlisted upstream system"
}

# The worker pods reach the endpoint's private IPs from inside the VPC (no hairpin through the internet).
resource "aws_vpc_security_group_ingress_rule" "vpc" {
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  description       = "SFTP from inside the VPC (pifa-worker)"
}

resource "aws_eip" "this" {
  count  = var.internet_facing ? length(var.subnet_ids) : 0
  domain = "vpc"
  tags   = { Name = "${var.name}-sftp-${count.index}" }
}

# ---------------------------------------------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "this" {
  name              = "/aws/transfer/${var.name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
}

data "aws_iam_policy_document" "transfer_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["transfer.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "logging" {
  name               = "${var.name}-sftp-logging"
  assume_role_policy = data.aws_iam_policy_document.transfer_assume.json
}

resource "aws_iam_role_policy_attachment" "logging" {
  role       = aws_iam_role.logging.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSTransferLoggingAccess"
}

# ---------------------------------------------------------------------------------------------------------------
# Server
# ---------------------------------------------------------------------------------------------------------------
resource "aws_transfer_server" "this" {
  identity_provider_type = "SERVICE_MANAGED" # users and SSH keys are managed below, in Terraform
  domain                 = "S3"
  protocols              = ["SFTP"] # SFTP only: no FTP/FTPS, no shell
  endpoint_type          = "VPC"
  security_policy_name   = var.security_policy_name

  endpoint_details {
    vpc_id                 = var.vpc_id
    subnet_ids             = var.subnet_ids
    security_group_ids     = [aws_security_group.this.id]
    address_allocation_ids = var.internet_facing ? aws_eip.this[*].id : null
  }

  logging_role                = aws_iam_role.logging.arn
  structured_log_destinations = ["${aws_cloudwatch_log_group.this.arn}:*"]

  pre_authentication_login_banner = "Authorized use only. Activity is logged."

  tags = { Name = "${var.name}-sftp" }
}

# ---------------------------------------------------------------------------------------------------------------
# Users: one IAM role per user, scoped to that user's prefixes only
# ---------------------------------------------------------------------------------------------------------------
resource "aws_iam_role" "user" {
  for_each           = var.users
  name               = "${var.name}-sftp-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.transfer_assume.json
}

data "aws_iam_policy_document" "user" {
  for_each = var.users

  statement {
    sid       = "ListOwnPrefixes"
    actions   = ["s3:ListBucket"]
    resources = ["arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}"]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values = flatten([
        for target in values(each.value.directories) : ["${var.prefix}/${target}", "${var.prefix}/${target}/*"]
      ])
    }
  }

  statement {
    sid = "ObjectAccessOwnPrefixes"
    actions = concat(
      ["s3:PutObject"],
      each.value.allow_read ? ["s3:GetObject", "s3:GetObjectVersion"] : [],
      each.value.allow_delete ? ["s3:DeleteObject", "s3:DeleteObjectVersion"] : [],
    )
    resources = [
      for target in values(each.value.directories) :
      "arn:${data.aws_partition.current.partition}:s3:::${var.bucket_name}/${var.prefix}/${target}/*"
    ]
  }

  # Objects are SSE-KMS encrypted: uploading needs GenerateDataKey, reading needs Decrypt.
  statement {
    sid       = "UseBucketKey"
    actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
    resources = [var.kms_key_arn]
  }
}

resource "aws_iam_role_policy" "user" {
  for_each = var.users
  name     = "sftp-scoped-access"
  role     = aws_iam_role.user[each.key].id
  policy   = data.aws_iam_policy_document.user[each.key].json
}

resource "aws_transfer_user" "this" {
  for_each  = var.users
  server_id = aws_transfer_server.this.id
  user_name = each.key
  role      = aws_iam_role.user[each.key].arn

  # LOGICAL = chroot: only the mapped virtual directories exist for this user.
  home_directory_type = "LOGICAL"

  dynamic "home_directory_mappings" {
    for_each = each.value.directories
    content {
      entry  = home_directory_mappings.key                             # path the SFTP client sees
      target = "${local.bucket_root}/${home_directory_mappings.value}" # where it is stored in S3
    }
  }
}

resource "aws_transfer_ssh_key" "this" {
  for_each  = local.ssh_keys
  server_id = aws_transfer_server.this.id
  user_name = aws_transfer_user.this[each.value.user].user_name
  body      = each.value.body
}
