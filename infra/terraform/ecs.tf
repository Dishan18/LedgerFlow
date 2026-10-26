# ---------------------------------------------------------------------------------------------
# Cluster, logs, service discovery
# ---------------------------------------------------------------------------------------------

resource "aws_ecs_cluster" "main" {
  name = local.name
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["ledgerflow-api", "mock-bank"])
  name              = "/ecs/${local.name}/${each.key}"
  retention_in_days = 14
}

# ledgerflow-api finds mock-bank at mock-bank.ledgerflow.local, kept up to date by ECS.
resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${local.name}.local"
  vpc  = aws_vpc.main.id
}

resource "aws_service_discovery_service" "mock_bank" {
  name = "mock-bank"

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.main.id
    routing_policy = "MULTIVALUE"
    dns_records {
      type = "A"
      ttl  = 10
    }
  }
}

# ---------------------------------------------------------------------------------------------
# Load balancer (ledgerflow-api only; mock-bank is internal)
# ---------------------------------------------------------------------------------------------

resource "aws_lb" "api" {
  name               = "${local.name}-api"
  load_balancer_type = "application"
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]
}

resource "aws_lb_target_group" "api" {
  name                 = "${local.name}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 15

  health_check {
    path                = "/actuator/health"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
  }
}

# HTTP only: HTTPS needs a domain for the ACM certificate. With one, add a 443 listener and
# redirect 80 -> 443.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# ---------------------------------------------------------------------------------------------
# IAM: one execution role (pull image, write logs, read startup secrets) and one task role per
# service with only what that service's code calls.
# ---------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [aws_secretsmanager_secret.jwt.arn, aws_db_instance.main.master_user_secret[0].secret_arn]
    }]
  })
}

resource "aws_iam_role" "api_task" {
  name               = "${local.name}-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "api_task" {
  role = aws_iam_role.api_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "PublishEvents", Effect = "Allow", Action = "sns:Publish", Resource = aws_sns_topic.payment_events.arn },
      {
        Sid    = "ConsumeQueues"
        Effect = "Allow"
        Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl"]
        Resource = [aws_sqs_queue.webhook.arn, aws_sqs_queue.settlement_files.arn]
      },
      { Sid = "ReadSettlements", Effect = "Allow", Action = "s3:GetObject", Resource = "${aws_s3_bucket.settlements.arn}/settlements/*" },
      { Sid = "WriteReports", Effect = "Allow", Action = "s3:PutObject", Resource = "${aws_s3_bucket.settlements.arn}/reports/*" },
      { Sid = "WebhookSecret", Effect = "Allow", Action = "secretsmanager:GetSecretValue", Resource = aws_secretsmanager_secret.webhook.arn },
      {
        Sid       = "Metrics"
        Effect    = "Allow"
        Action    = "cloudwatch:PutMetricData"
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = "LedgerFlow" } }
      }
    ]
  })
}

resource "aws_iam_role" "mock_bank_task" {
  name               = "${local.name}-mock-bank-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "mock_bank_task" {
  role = aws_iam_role.mock_bank_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Sid = "WriteSettlements", Effect = "Allow", Action = "s3:PutObject", Resource = "${aws_s3_bucket.settlements.arn}/settlements/*" },
      {
        Sid      = "ConsumeTrigger"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
        Resource = aws_sqs_queue.settlement_trigger.arn
      }
    ]
  })
}

# ---------------------------------------------------------------------------------------------
# Task definitions and services
# ---------------------------------------------------------------------------------------------

locals {
  api_environment = {
    SPRING_PROFILES_ACTIVE    = "aws"
    AWS_REGION                = var.region
    DB_URL                    = "jdbc:postgresql://${aws_db_instance.main.address}:5432/ledgerflow"
    DB_USERNAME               = aws_db_instance.main.username
    BANK_URL                  = "http://mock-bank.${aws_service_discovery_private_dns_namespace.main.name}:8081"
    PAYMENT_EVENTS_TOPIC_ARN  = aws_sns_topic.payment_events.arn
    WEBHOOK_QUEUE             = aws_sqs_queue.webhook.name
    WEBHOOK_SIGNING_SECRET_ID = aws_secretsmanager_secret.webhook.name
    SETTLEMENT_FILES_QUEUE    = aws_sqs_queue.settlement_files.name
    SETTLEMENT_BUCKET         = aws_s3_bucket.settlements.bucket
  }
  mock_bank_environment = {
    SPRING_PROFILES_ACTIVE   = "aws"
    AWS_REGION               = var.region
    SETTLEMENT_BUCKET        = aws_s3_bucket.settlements.bucket
    SETTLEMENT_TRIGGER_QUEUE = aws_sqs_queue.settlement_trigger.name
  }
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "ledgerflow-api"
    image        = "${aws_ecr_repository.service["ledgerflow-api"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    environment  = [for k, v in local.api_environment : { name = k, value = v }]
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" },
      { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt.arn }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["ledgerflow-api"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "mock_bank" {
  family                   = "${local.name}-mock-bank"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.mock_bank_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([{
    name         = "mock-bank"
    image        = "${aws_ecr_repository.service["mock-bank"].repository_url}:${var.image_tag}"
    essential    = true
    portMappings = [{ containerPort = 8081, protocol = "tcp" }]
    environment  = [for k, v in local.mock_bank_environment : { name = k, value = v }]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service["mock-bank"].name
        awslogs-region        = var.region
        awslogs-stream-prefix = "app"
      }
    }
  }])
}

resource "aws_ecs_service" "api" {
  name                              = "ledgerflow-api"
  cluster                           = aws_ecs_cluster.main.id
  task_definition                   = aws_ecs_task_definition.api.arn
  desired_count                     = var.api_desired_count
  launch_type                       = "FARGATE"
  health_check_grace_period_seconds = 180 # JVM start + Flyway migrations

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.api.id]
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "ledgerflow-api"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # CI registers new task definition revisions on every deploy; don't fight it.
  lifecycle {
    ignore_changes = [task_definition]
  }

  depends_on = [aws_lb_listener.http]
}

resource "aws_ecs_service" "mock_bank" {
  name            = "mock-bank"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.mock_bank.arn
  desired_count   = var.mock_bank_desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.mock_bank.id]
    assign_public_ip = true
  }

  service_registries {
    registry_arn = aws_service_discovery_service.mock_bank.arn
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}
