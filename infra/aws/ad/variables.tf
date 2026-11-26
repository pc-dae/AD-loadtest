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
