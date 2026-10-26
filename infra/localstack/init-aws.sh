#!/bin/bash
# Creates LedgerFlow's AWS resources inside LocalStack. Runs automatically when LocalStack is
# ready (mounted into /etc/localstack/init/ready.d). Terraform creates the same things in AWS.
set -euo pipefail

REGION=ap-south-1
ACCOUNT=000000000000

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

echo "LedgerFlow LocalStack resources ready"
