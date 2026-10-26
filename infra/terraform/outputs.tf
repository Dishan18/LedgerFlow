output "api_url" {
  description = "Public URL of ledgerflow-api."
  value       = "http://${aws_lb.api.dns_name}"
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN repository variable in GitHub."
  value       = aws_iam_role.github_deploy.arn
}

output "ecr_repositories" {
  value = { for k, r in aws_ecr_repository.service : k => r.repository_url }
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "settlement_bucket" {
  value = aws_s3_bucket.settlements.bucket
}

output "jwt_secret_name" {
  description = "Read it to mint tokens: tools/mint_token.py."
  value       = aws_secretsmanager_secret.jwt.name
}

output "webhook_secret_name" {
  value = aws_secretsmanager_secret.webhook.name
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}
