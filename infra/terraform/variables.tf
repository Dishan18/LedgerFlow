variable "project" {
  description = "Name prefix for every resource."
  type        = string
  default     = "ledgerflow"
}

variable "region" {
  description = "AWS region. Mumbai by default, close to Indian users."
  type        = string
  default     = "ap-south-1"
}

variable "github_repository" {
  description = "owner/name of the GitHub repo allowed to deploy through OIDC."
  type        = string
  default     = "Dishan18/LedgerFlow"
}

variable "alert_email" {
  description = "Receives CloudWatch alarm and budget emails. AWS sends a confirmation link first."
  type        = string
}

variable "monthly_budget_usd" {
  description = "AWS Budgets alert threshold for the whole account, in USD."
  type        = number
  default     = 50
}

variable "image_tag" {
  description = "Image tag used when Terraform creates the task definitions. CI deploys newer tags itself."
  type        = string
  default     = "latest"
}

variable "api_desired_count" {
  description = "Running ledgerflow-api tasks. Set to 0 to pause compute without destroying anything."
  type        = number
  default     = 1
}

variable "mock_bank_desired_count" {
  type    = number
  default = 1
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}
