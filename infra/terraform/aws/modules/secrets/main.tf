# Purpose: Secrets Manager secret "shells" for PIFA (architecture.md 12 - "nothing in Git").
# Terraform creates only the secret containers (name, KMS key, recovery window, description). Values are written
# out of band (break-glass operator, rotation Lambda, or the Keycloak CI job), so secret values never enter
# Terraform state, plans or CI logs. External Secrets Operator then syncs them into Kubernetes.

resource "aws_secretsmanager_secret" "this" {
  for_each = var.secrets

  name                    = "${var.prefix}/${each.key}"
  description             = each.value
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days # a deleted secret can be restored for this many days
}
