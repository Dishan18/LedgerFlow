# Deploying LedgerFlow to AWS

Everything in AWS is created by Terraform in [`terraform/`](terraform/). GitHub Actions then
builds, tests, pushes images to ECR and deploys to ECS on every push to `main`, authenticating
with GitHub OIDC (no AWS keys stored in GitHub).

## What gets created (region `ap-south-1`)

| Area | Resources |
|---|---|
| Network | VPC, 2 public subnets (ALB + Fargate tasks), 2 private subnets (RDS), **no NAT gateway** |
| Compute | ECS cluster, Fargate services `ledgerflow-api` (0.5 vCPU / 1 GB) and `mock-bank` (0.25 vCPU / 0.5 GB), ALB, Cloud Map `mock-bank.ledgerflow.local` |
| Data | RDS PostgreSQL 17 `db.t4g.micro` (password managed by RDS in Secrets Manager), ECR repos |
| Messaging | SNS `payment-events` → SQS `webhook-delivery` (+ DLQ), SQS `settlement-trigger`, `settlement-files` (+ DLQ), S3 settlement bucket with event notification |
| Scheduling | EventBridge Scheduler: 00:30 IST daily → `settlement-trigger` |
| Secrets | JWT signing key, webhook signing key (Secrets Manager) |
| Observability | CloudWatch log groups, alarms (DLQs not empty, 5xx, no healthy targets, RDS CPU/storage) → email, Micrometer metrics in namespace `LedgerFlow` |
| Cost guard | AWS Budgets alert (50% / 80% actual, 100% forecast) |
| CI/CD | GitHub OIDC provider + deploy role limited to `main` of this repo |

**Rough cost while running:** about $70–80/month (ALB ~$18, Fargate ~$25, RDS ~$15, public IPv4
addresses ~$14, the rest small). Scale to zero with `api_desired_count = 0` and
`mock_bank_desired_count = 0`, or destroy everything when you're done (below).

## One-time setup

You need: an AWS account (credits are fine), the AWS CLI logged in as an admin user or SSO role,
and Terraform ≥ 1.10.

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars   # set alert_email
terraform init
terraform apply
```

Then:

1. **Confirm the email** AWS sends to `alert_email` (alarms and budget alerts).
2. In GitHub → Settings → Secrets and variables → Actions → **Variables**, add
   `AWS_DEPLOY_ROLE_ARN` = the `github_deploy_role_arn` output (and optionally `AWS_REGION`).
3. Re-run the latest CI/CD workflow on `main` (or push a commit). The deploy job builds both
   images, pushes them to ECR and rolls out ECS. Until the first deploy, ECS can't pull an image yet;
   that is expected.

## Try it

```bash
API=$(terraform output -raw api_url)
curl $API/actuator/health
SECRET=$(aws secretsmanager get-secret-value --secret-id ledgerflow/jwt-signing-secret --query SecretString --output text)
TOKEN=$(python ../../tools/mint_token.py --secret "$SECRET" --merchant 11111111-1111-1111-1111-111111111111)
curl -X POST $API/v1/payments -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: demo-1" \
  -H "Content-Type: application/json" -d '{"customerId":"cust_alice","amount":25000}'
```

Swagger UI: `$API/swagger-ui.html`.

## Teardown

```bash
cd infra/terraform
terraform destroy
```

This deletes everything above, including the database (no final snapshot), the S3 bucket and ECR
images (`force_destroy`), and the secrets (no recovery window). Check the AWS Billing console the
next day to confirm nothing is still being charged.
