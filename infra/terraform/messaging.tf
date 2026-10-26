# Same topology as infra/localstack/init-aws.sh, plus the IAM policies LocalStack doesn't enforce.

# ---------------------------------------------------------------------------------------------
# Payment events: SNS payment-events -> SQS webhook-delivery (DLQ after 5 receives)
# ---------------------------------------------------------------------------------------------

resource "aws_sns_topic" "payment_events" {
  name = "payment-events"
}

resource "aws_sqs_queue" "webhook_dlq" {
  name                      = "webhook-delivery-dlq"
  message_retention_seconds = 1209600 # 14 days to investigate
}

resource "aws_sqs_queue" "webhook" {
  name                       = "webhook-delivery"
  visibility_timeout_seconds = 30
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.webhook_dlq.arn
    maxReceiveCount     = 5 # must equal ledgerflow.webhooks.max-attempts
  })
}

resource "aws_sqs_queue_policy" "webhook" {
  queue_url = aws_sqs_queue.webhook.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowPaymentEventsTopic"
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.webhook.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.payment_events.arn } }
    }]
  })
}

resource "aws_sns_topic_subscription" "webhook" {
  topic_arn            = aws_sns_topic.payment_events.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.webhook.arn
  raw_message_delivery = true
}

# ---------------------------------------------------------------------------------------------
# Settlement: EventBridge Scheduler -> settlement-trigger -> mock-bank -> S3 -> settlement-files
# ---------------------------------------------------------------------------------------------

resource "random_id" "bucket" {
  byte_length = 4
}

resource "aws_s3_bucket" "settlements" {
  bucket        = "${local.name}-settlements-${random_id.bucket.hex}" # bucket names are global
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "settlements" {
  bucket                  = aws_s3_bucket.settlements.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "settlements" {
  bucket = aws_s3_bucket.settlements.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "settlements" {
  bucket = aws_s3_bucket.settlements.id
  rule {
    id     = "expire-after-90-days"
    status = "Enabled"
    filter {}
    expiration {
      days = 90
    }
  }
}

resource "aws_sqs_queue" "settlement_trigger" {
  name = "settlement-trigger"
}

resource "aws_sqs_queue" "settlement_files_dlq" {
  name                      = "settlement-files-dlq"
  message_retention_seconds = 1209600
}

resource "aws_sqs_queue" "settlement_files" {
  name                       = "settlement-files"
  visibility_timeout_seconds = 60
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.settlement_files_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_sqs_queue_policy" "settlement_files" {
  queue_url = aws_sqs_queue.settlement_files.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowSettlementBucket"
      Effect    = "Allow"
      Principal = { Service = "s3.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.settlement_files.arn
      Condition = {
        ArnEquals    = { "aws:SourceArn" = aws_s3_bucket.settlements.arn }
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  })
}

resource "aws_s3_bucket_notification" "settlements" {
  bucket = aws_s3_bucket.settlements.id

  queue {
    queue_arn     = aws_sqs_queue.settlement_files.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "settlements/"
    filter_suffix = ".csv"
  }

  depends_on = [aws_sqs_queue_policy.settlement_files]
}

# Every night at 00:30 IST, ask mock-bank to settle the previous day.
resource "aws_iam_role" "scheduler" {
  name = "${local.name}-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = local.account_id } }
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sqs:SendMessage", Resource = aws_sqs_queue.settlement_trigger.arn }]
  })
}

resource "aws_scheduler_schedule" "daily_settlement" {
  name                         = "${local.name}-daily-settlement"
  schedule_expression          = "cron(30 0 * * ? *)"
  schedule_expression_timezone = "Asia/Kolkata"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_sqs_queue.settlement_trigger.arn
    role_arn = aws_iam_role.scheduler.arn
    input    = jsonencode({}) # no date: mock-bank settles "yesterday" in IST
  }
}
