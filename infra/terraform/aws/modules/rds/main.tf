# Purpose: one production-grade RDS PostgreSQL 16 instance (used twice: the PIFA DB and the Keycloak DB).
#
# Why these settings (architecture.md 13.3 and 14.1):
#   - Multi-AZ: a synchronous standby in another AZ with automatic failover (typically 1-2 min), so an AZ or
#     instance failure does not lose committed data. This is the "PostgreSQL HA" box in the topology.
#   - KMS encryption + TLS-only (rds.force_ssl): data at rest and in transit are encrypted (security table, ch. 12).
#   - 35-day automated backups: the RDS maximum. Together with continuous WAL archiving they give point-in-time
#     restore (PITR) to any second in that window (RPO in practice ~5 min, inside the 15 min target).
#   - Cross-region automated backup replication: copies snapshots AND transaction logs to the DR region, so the DB
#     can be restored there to a point in time even if the whole primary region is lost (DR, RPO 15 min).
#   - AWS Backup monthly copy kept 1 year: covers "monthly kept 1 year" from 14.1, which RDS alone cannot do.
#   - Deletion protection + final snapshot: a mistaken `terraform destroy` or console click cannot drop the data.
#   - Isolated subnets + security group from EKS only: the DB is unreachable from the internet and other VPC tiers.

terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      configuration_aliases = [aws.dr]
    }
  }
}

data "aws_partition" "current" {}

resource "aws_db_subnet_group" "this" {
  name        = var.identifier
  description = "Isolated subnets for ${var.identifier}"
  subnet_ids  = var.subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.identifier}-db"
  description = "PostgreSQL access for ${var.identifier} from EKS only"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.identifier}-db" }
}

resource "aws_vpc_security_group_ingress_rule" "from_sources" {
  for_each                     = toset(var.allowed_security_group_ids)
  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from EKS pods/nodes"
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.identifier}-pg16"
  family      = "postgres16"
  description = "PIFA PostgreSQL 16 parameters for ${var.identifier}"

  # Log every statement slower than the threshold: feeds slow-query analysis (Phase 5 DB/query tuning).
  parameter {
    name  = "log_min_duration_statement"
    value = tostring(var.log_min_duration_statement_ms)
  }

  # Reject non-TLS connections (security table: TLS 1.2+ to the DB).
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  # Lock waits longer than deadlock_timeout are logged - useful for the concurrent forecast-edit workload.
  parameter {
    name  = "log_lock_waits"
    value = "1"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# RDS creates /aws/rds/instance/<id>/postgresql on first export; creating it first sets retention and KMS.
resource "aws_cloudwatch_log_group" "postgresql" {
  for_each          = toset(["postgresql", "upgrade"])
  name              = "/aws/rds/instance/${var.identifier}/${each.value}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
}

# Enhanced Monitoring (OS-level metrics every N seconds) needs a role RDS can assume.
data "aws_iam_policy_document" "monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  name               = "${var.identifier}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume.json
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "this" {
  identifier     = var.identifier
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class
  db_name        = var.db_name
  username       = var.master_username
  port           = 5432

  # RDS generates the master password and stores/rotates it in Secrets Manager (encrypted with our key), so
  # no password ever appears in Terraform state or variables. Application users are created by migrations
  # and their credentials live in the separate "pifa/<env>/db/*" secret shells (secrets module).
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.secrets_kms_key_arn

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.max_allocated_storage_gb # storage autoscaling ceiling
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  multi_az               = var.multi_az
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.this.name
  ca_cert_identifier     = "rds-ca-rsa2048-g1"

  backup_retention_period  = var.backup_retention_days # enables automated backups + PITR
  backup_window            = "01:00-02:00"
  maintenance_window       = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot    = true
  delete_automated_backups = false # keep automated backups if the instance is ever deleted

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.identifier}-final"

  performance_insights_enabled          = true
  performance_insights_kms_key_id       = var.kms_key_arn
  performance_insights_retention_period = var.performance_insights_retention_days
  monitoring_interval                   = 60
  monitoring_role_arn                   = aws_iam_role.monitoring.arn
  enabled_cloudwatch_logs_exports       = ["postgresql", "upgrade"]

  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false # major upgrades are a planned change, never a side effect of apply
  apply_immediately           = false

  depends_on = [aws_cloudwatch_log_group.postgresql]
}

# ---------------------------------------------------------------------------------------------------------------
# DR: cross-region replication of automated backups (snapshots + WAL) into the DR region.
# Restores in the DR region can then target any point in the replicated retention window.
# ---------------------------------------------------------------------------------------------------------------
resource "aws_db_instance_automated_backups_replication" "dr" {
  provider = aws.dr

  source_db_instance_arn = aws_db_instance.this.arn
  kms_key_id             = var.dr_kms_key_arn # must be a key in the DR region
  retention_period       = var.dr_backup_retention_days
}

# ---------------------------------------------------------------------------------------------------------------
# Long-term monthly backups (kept 1 year) through AWS Backup, copied to a vault in the DR region.
# ---------------------------------------------------------------------------------------------------------------
resource "aws_backup_vault" "this" {
  name        = "${var.identifier}-vault"
  kms_key_arn = var.kms_key_arn
}

resource "aws_backup_vault" "dr" {
  provider    = aws.dr
  name        = "${var.identifier}-dr-vault"
  kms_key_arn = var.dr_kms_key_arn
}

resource "aws_backup_plan" "monthly" {
  name = "${var.identifier}-monthly"

  rule {
    rule_name         = "monthly-1y"
    target_vault_name = aws_backup_vault.this.name
    schedule          = "cron(0 4 1 * ? *)" # 04:00 UTC on the 1st of each month

    lifecycle {
      delete_after = var.monthly_backup_retention_days
    }

    copy_action {
      destination_vault_arn = aws_backup_vault.dr.arn
      lifecycle {
        delete_after = var.monthly_backup_retention_days
      }
    }
  }
}

data "aws_iam_policy_document" "backup_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["backup.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backup" {
  name               = "${var.identifier}-backup"
  assume_role_policy = data.aws_iam_policy_document.backup_assume.json
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

resource "aws_backup_selection" "this" {
  name         = "${var.identifier}-db"
  plan_id      = aws_backup_plan.monthly.id
  iam_role_arn = aws_iam_role.backup.arn
  resources    = [aws_db_instance.this.arn]
}
