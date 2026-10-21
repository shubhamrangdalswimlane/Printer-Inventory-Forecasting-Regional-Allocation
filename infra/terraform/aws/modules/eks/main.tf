# Purpose: the EKS cluster that runs PIFA (namespaces pifa, identity, observability - architecture.md 13.1),
# with one managed node group spread over the private subnets of all AZs, KMS envelope encryption of
# Kubernetes Secrets, control-plane logging, and an OIDC provider for IRSA (IAM roles for service accounts).
#
# Not managed here on purpose (they are cluster add-ons installed by Helm/GitOps): AWS Load Balancer Controller,
# External Secrets Operator, cluster autoscaler/Karpenter, ingress WAF association, observability stack.

data "aws_partition" "current" {}

# ---------------------------------------------------------------------------------------------------------------
# Control plane
# ---------------------------------------------------------------------------------------------------------------
data "aws_iam_policy_document" "cluster_assume" {
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${var.cluster_name}-cluster"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume.json
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

# Created before the cluster so EKS writes into a group with our retention and KMS key
# (otherwise EKS creates it with "never expire").
resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/${var.cluster_name}/cluster"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.logs_kms_key_arn
}

resource "aws_security_group" "cluster" {
  name        = "${var.cluster_name}-cluster"
  description = "EKS control plane extra security group"
  vpc_id      = var.vpc_id
  tags        = { Name = "${var.cluster_name}-cluster" }
}

resource "aws_eks_cluster" "this" {
  name     = var.cluster_name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    security_group_ids      = [aws_security_group.cluster.id]
    # The Kubernetes API is reachable only from inside the VPC, in every environment. Operators and CI reach it
    # through the VPN / SSM bastion or a self-hosted runner in the VPC; a public endpoint is never created.
    endpoint_private_access = true
    endpoint_public_access  = false
  }

  # Envelope-encrypt Kubernetes Secrets (e.g. those synced by External Secrets) with our KMS key.
  encryption_config {
    resources = ["secrets"]
    provider {
      key_arn = var.secrets_kms_key_arn
    }
  }

  # Access entries (EKS API) instead of the legacy aws-auth ConfigMap: cluster access is managed in Terraform.
  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = false
  }

  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_cloudwatch_log_group.cluster,
  ]
}

# Cluster administrators (e.g. a platform SSO role and the CI deploy role).
resource "aws_eks_access_entry" "admins" {
  for_each      = toset(var.admin_principal_arns)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
}

resource "aws_eks_access_policy_association" "admins" {
  for_each      = toset(var.admin_principal_arns)
  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  policy_arn    = "arn:${data.aws_partition.current.partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.admins]
}

# ---------------------------------------------------------------------------------------------------------------
# IRSA: the OIDC provider lets pods assume IAM roles bound to a specific namespace/service account.
# ---------------------------------------------------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "this" {
  url            = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint_list omitted: since 2023 IAM validates EKS OIDC issuers against its own trusted CA library.
}

# ---------------------------------------------------------------------------------------------------------------
# Managed node group
# ---------------------------------------------------------------------------------------------------------------
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.cluster_name}-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json
}

# Nodes get only the baseline policies. Application permissions (S3, KMS, Secrets Manager) are NOT on the node
# role - they live on IRSA roles so that only the intended pod, not every pod on the node, can use them.
resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    "AmazonEC2ContainerRegistryReadOnly",
    "AmazonSSMManagedInstanceCore", # shell access through SSM Session Manager instead of SSH keys
  ])
  role       = aws_iam_role.node.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/${each.value}"
}

resource "aws_launch_template" "node" {
  name_prefix = "${var.cluster_name}-node-"

  # IMDSv2 only, hop limit 1: pods cannot reach the node's instance credentials, which forces them onto IRSA.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = var.node_disk_size_gb
      volume_type           = "gp3"
      encrypted             = true
      kms_key_id            = var.ebs_kms_key_arn
      delete_on_termination = true
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${var.cluster_name}-node" }
  }
}

resource "aws_eks_node_group" "default" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "default"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.private_subnet_ids # one subnet per AZ, so pods (api x2+, worker x2, keycloak x2) spread over AZs
  instance_types  = var.node_instance_types
  capacity_type   = "ON_DEMAND"
  ami_type        = "AL2023_x86_64_STANDARD"

  scaling_config {
    min_size     = var.node_min_size
    desired_size = var.node_desired_size
    max_size     = var.node_max_size
  }

  update_config {
    max_unavailable = 1 # rolling node upgrades never remove more than one node at a time
  }

  launch_template {
    id      = aws_launch_template.node.id
    version = aws_launch_template.node.latest_version
  }

  lifecycle {
    # The cluster autoscaler changes desired_size at runtime; do not fight it on every apply.
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# Core add-ons managed by EKS so they are upgraded together with the control plane.
resource "aws_eks_addon" "this" {
  for_each                    = toset(["vpc-cni", "kube-proxy", "coredns", "eks-pod-identity-agent"])
  cluster_name                = aws_eks_cluster.this.name
  addon_name                  = each.value
  resolve_conflicts_on_update = "OVERWRITE"

  depends_on = [aws_eks_node_group.default]
}
