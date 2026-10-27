# One CloudWatch dashboard for the whole system: traffic and latency at the load balancer,
# compute, database, queues, and LedgerFlow's own business metrics (Micrometer, namespace LedgerFlow).

locals {
  alb_dims = ["LoadBalancer", aws_lb.api.arn_suffix]
  # App metrics are found with SEARCH so the dashboard doesn't depend on Micrometer's exact naming.
  # Match on the namespace, not the {LedgerFlow} schema: a schema only matches metrics without
  # dimensions, and tagged counters (bank calls by operation/outcome) would be left out.
  app_search = "SEARCH('Namespace=\"LedgerFlow\" %s', 'Sum', 60)"
}

resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = local.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 8, height = 6
        properties = {
          title  = "API requests / min and 5xx"
          region = var.region
          stat   = "Sum"
          period = 60
          metrics = [
            concat(["AWS/ApplicationELB", "RequestCount"], local.alb_dims),
            concat(["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count"], local.alb_dims),
          ]
        }
      },
      {
        type = "metric", x = 8, y = 0, width = 8, height = 6
        properties = {
          title  = "API latency (s): p50 / p95 / p99"
          region = var.region
          period = 60
          metrics = [
            concat(["AWS/ApplicationELB", "TargetResponseTime"], local.alb_dims, [{ stat = "p50" }]),
            concat(["AWS/ApplicationELB", "TargetResponseTime"], local.alb_dims, [{ stat = "p95" }]),
            concat(["AWS/ApplicationELB", "TargetResponseTime"], local.alb_dims, [{ stat = "p99" }]),
          ]
        }
      },
      {
        type = "metric", x = 16, y = 0, width = 8, height = 6
        properties = {
          title  = "ECS CPU / memory (%)"
          region = var.region
          stat   = "Average"
          period = 60
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.api.name],
            ["AWS/ECS", "MemoryUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.api.name],
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.mock_bank.name],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 8, height = 6
        properties = {
          title  = "PostgreSQL"
          region = var.region
          stat   = "Average"
          period = 60
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", aws_db_instance.main.identifier],
            ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", aws_db_instance.main.identifier, { yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 8, y = 6, width = 8, height = 6
        properties = {
          title  = "Queues: backlog and dead letters"
          region = var.region
          stat   = "Maximum"
          period = 60
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.webhook.name],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.webhook_dlq.name],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.settlement_files_dlq.name],
            ["AWS/SQS", "ApproximateAgeOfOldestMessage", "QueueName", aws_sqs_queue.webhook.name, { yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 16, y = 6, width = 8, height = 6
        properties = {
          title   = "Bank calls by outcome"
          region  = var.region
          period  = 60
          metrics = [[{ expression = format(local.app_search, "ledgerflow.bank.calls"), id = "bank" }]]
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title  = "Webhooks: delivered, failed attempts, dead-lettered, duplicates"
          region = var.region
          period = 60
          metrics = [
            [{ expression = format(local.app_search, "ledgerflow.webhooks"), id = "webhooks" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6
        properties = {
          title  = "Outbox published and reconciliation discrepancies"
          region = var.region
          period = 60
          metrics = [
            [{ expression = format(local.app_search, "ledgerflow.outbox"), id = "outbox" }],
            [{ expression = format(local.app_search, "ledgerflow.reconciliation"), id = "recon" }],
          ]
        }
      },
    ]
  })
}

output "dashboard_url" {
  value = "https://${var.region}.console.aws.amazon.com/cloudwatch/home?region=${var.region}#dashboards:name=${aws_cloudwatch_dashboard.main.dashboard_name}"
}
