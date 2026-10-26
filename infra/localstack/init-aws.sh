#!/bin/bash
# Creates LedgerFlow's AWS resources inside LocalStack. Runs automatically when LocalStack is
# ready (mounted into /etc/localstack/init/ready.d). Terraform creates the same things in AWS.
set -euo pipefail

REGION=ap-south-1
ACCOUNT=000000000000

# --- Payment events and webhooks (Phase 4) ---
awslocal --region "$REGION" sns create-topic --name payment-events

awslocal --region "$REGION" sqs create-queue --queue-name webhook-delivery-dlq \
  --attributes MessageRetentionPeriod=1209600

# After 5 failed receives SQS moves the message to the DLQ (matches ledgerflow.webhooks.max-attempts).
awslocal --region "$REGION" sqs create-queue --queue-name webhook-delivery \
  --attributes "{\"VisibilityTimeout\":\"30\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"arn:aws:sqs:${REGION}:${ACCOUNT}:webhook-delivery-dlq\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}"

# Raw delivery: the queue receives the event JSON itself, not SNS's envelope around it.
awslocal --region "$REGION" sns subscribe \
  --topic-arn "arn:aws:sns:${REGION}:${ACCOUNT}:payment-events" \
  --protocol sqs \
  --notification-endpoint "arn:aws:sqs:${REGION}:${ACCOUNT}:webhook-delivery" \
  --attributes RawMessageDelivery=true

awslocal --region "$REGION" secretsmanager create-secret \
  --name ledgerflow/webhook-signing-secret \
  --secret-string whsec_local_dev_secret_change_me

# --- Settlement and reconciliation (Phase 5) ---
# EventBridge Scheduler (AWS only) -> settlement-trigger -> mock-bank writes settlements/<date>/settlement.csv
# -> S3 event -> settlement-files -> LedgerFlow reconciles and writes reports/<date>.json.
awslocal --region "$REGION" sqs create-queue --queue-name settlement-trigger
awslocal --region "$REGION" sqs create-queue --queue-name settlement-files-dlq

awslocal --region "$REGION" sqs create-queue --queue-name settlement-files \
  --attributes "{\"VisibilityTimeout\":\"60\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"arn:aws:sqs:${REGION}:${ACCOUNT}:settlement-files-dlq\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"}"

awslocal --region "$REGION" s3api create-bucket --bucket ledgerflow-settlements \
  --create-bucket-configuration LocationConstraint="$REGION"

awslocal --region "$REGION" s3api put-bucket-notification-configuration --bucket ledgerflow-settlements \
  --notification-configuration "{\"QueueConfigurations\":[{\"QueueArn\":\"arn:aws:sqs:${REGION}:${ACCOUNT}:settlement-files\",\"Events\":[\"s3:ObjectCreated:*\"],\"Filter\":{\"Key\":{\"FilterRules\":[{\"Name\":\"prefix\",\"Value\":\"settlements/\"},{\"Name\":\"suffix\",\"Value\":\".csv\"}]}}}]}"

# Marker for the docker-compose healthcheck: only reached if every command above succeeded.
touch /tmp/ledgerflow-init.done
echo "LedgerFlow LocalStack resources ready"
