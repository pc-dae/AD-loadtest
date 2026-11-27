variable "region" {
  description = "AWS region, from the tf-config ConfigMap"
  type        = string
}

variable "owner_email" {
  description = "Owner tag, from the tf-config ConfigMap"
  type        = string
}

variable "eks_cluster_name" {
  description = "EKS cluster whose VPC hosts the domain controllers, from the tf-config ConfigMap"
  type        = string
}

variable "domain_name" {
  description = "AD DNS domain name; must match the chart's domain values"
  type        = string
  default     = "lab.local"
}

variable "netbios_name" {
  description = "AD NetBIOS domain name"
  type        = string
  default     = "LAB"
}

variable "dc_count" {
  description = "Domain controllers, 1 or 2. Each is in its own availability zone"
  type        = number
  default     = 2
  validation {
    condition     = var.dc_count >= 1 && var.dc_count <= 2
    error_message = "dc_count must be 1 or 2."
  }
}

variable "instance_type" {
  description = "Domain controller instance type"
  type        = string
  default     = "t3.medium"
}

variable "user_count" {
  description = "Load-test users to seed, ltuser00001..N; must match the chart's users.count"
  type        = number
  default     = 500
}

variable "role_groups" {
  description = "Extra LT-Role-nnn groups each \"heavy\" user (every 10th) is in; must match the chart's users.roleGroups"
  type        = number
  default     = 200
}

variable "nest_depth" {
  description = "Depth of the LT-Nest-nn chain the \"deep\" users authorise through; must match the chart's users.nestDepth"
  type        = number
  default     = 8
  validation {
    condition     = var.nest_depth >= 1
    error_message = "nest_depth must be at least 1."
  }
}

variable "computer_count" {
  description = "Computer accounts LTPC00001$... to create for machine-logon tests"
  type        = number
  default     = 100
}

variable "telegraf_version" {
  description = "Telegraf release installed on each DC to serve its performance counters"
  type        = string
  default     = "1.40.3"
}

variable "telegraf_sha256" {
  description = "SHA-256 of telegraf-<version>_windows_amd64.zip"
  type        = string
  default     = "273867D9BB070625B8FC8627F3189899B1221C2C6540DD98F6E526108A89C9C3"
}

variable "metrics_port" {
  description = "Port Telegraf serves DC metrics on, in Prometheus format"
  type        = number
  default     = 9273
}

variable "dc_ip_offset" {
  description = "Host number of each DC's fixed private IP in its subnet, e.g. 10 gives 10.0.0.10 in the default EKS VPC"
  type        = number
  default     = 10
}

variable "windows_ami_name" {
  description = "Name filter for the Amazon Windows Server AMI"
  type        = string
  default     = "Windows_Server-2022-English-Full-Base-*"
}
