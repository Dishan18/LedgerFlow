# ---------------------------------------------------------------------------------------------
# PostgreSQL (RDS)
# ---------------------------------------------------------------------------------------------

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "17"
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "ledgerflow"
  username = "ledgerflow"
  # RDS generates the password and keeps it in Secrets Manager (and can rotate it). It never
  # appears in Terraform state or in the task definition; ECS injects it at container start.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 1
  # Portfolio settings so `terraform destroy` really removes everything. In production:
  # deletion_protection = true and a final snapshot.
  deletion_protection = false
  skip_final_snapshot = true
  apply_immediately   = true
}

# ---------------------------------------------------------------------------------------------
# Container registries
# ---------------------------------------------------------------------------------------------

resource "aws_ecr_repository" "service" {
  for_each             = toset(["ledgerflow-api", "mock-bank"])
  name                 = "${local.name}/${each.key}"
  image_tag_mutability = "MUTABLE" # CI moves the "latest" tag; each deploy also pushes an immutable git-sha tag
  force_delete         = true      # let terraform destroy remove repositories that still hold images

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "service" {
  for_each   = aws_ecr_repository.service
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 10 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 10 }
      action       = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------------------------------------
# Application secrets
# ---------------------------------------------------------------------------------------------

resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "random_password" "webhook" {
  length  = 40
  special = false
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${local.name}/jwt-signing-secret"
  description             = "HS256 key that merchant JWTs are signed with"
  recovery_window_in_days = 0 # delete immediately on destroy, so a re-apply can reuse the name
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt.result
}

resource "aws_secretsmanager_secret" "webhook" {
  name                    = "${local.name}/webhook-signing-secret"
  description             = "HMAC key for webhook signatures; merchants get it to verify deliveries"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "webhook" {
  secret_id     = aws_secretsmanager_secret.webhook.id
  secret_string = "whsec_${random_password.webhook.result}"
}
