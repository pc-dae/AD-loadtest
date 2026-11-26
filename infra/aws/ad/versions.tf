# The backend is configured by the Terraform object's backendConfig, see ../flux/ad.yaml
terraform {
  required_version = ">= 1.10"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.6"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Application = "ad-loadtest"
      Cluster     = var.eks_cluster_name
      ManagedBy   = "tofu-controller"
      email       = var.owner_email
    }
  }
}
