# Windows Server domain controllers for a new AD forest, in the EKS cluster's private subnets so the
# load generators reach them directly and nothing is exposed to the internet. DC1 creates the forest,
# installs an enterprise CA (so the DCs get LDAPS certificates) and seeds the load-test users; DC2, if
# any, joins as a replica. See the PowerShell templates for the steps, they run on every boot and
# carry on after each reboot.
#
# The tf-runner role only has EC2 permissions, so the DCs have no instance profile and the
# passwords are passed in the user data: fine for a disposable lab, not for anything real.

data "aws_availability_zones" "available" {
  state = "available"
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

data "aws_vpc" "eks" {
  filter {
    name   = "tag:Name"
    values = [var.eks_cluster_name]
  }
}

# The EKS module's private subnets, one per availability zone, in the same zone order as the cluster's.
data "aws_subnet" "dc" {
  count             = var.dc_count
  vpc_id            = data.aws_vpc.eks.id
  availability_zone = data.aws_availability_zones.available.names[count.index]
  filter {
    name   = "tag:kubernetes.io/role/internal-elb"
    values = ["1"]
  }
}

data "aws_ami" "windows" {
  most_recent = true
  owners      = ["amazon"]
  filter {
    name   = "name"
    values = [var.windows_ami_name]
  }
}

locals {
  dc_ips = [for s in data.aws_subnet.dc : cidrhost(s.cidr_block, var.dc_ip_offset)]
}

# Restricted to characters that are safe in PowerShell single-quoted strings, CSV and properties files.
resource "random_password" "admin" {
  length           = 20
  override_special = "!#-_=+"
  min_upper        = 2
  min_lower        = 2
  min_numeric      = 2
  min_special      = 2
}

resource "random_password" "test_user" {
  length           = 16
  override_special = "!#-_=+"
  min_upper        = 1
  min_lower        = 1
  min_numeric      = 1
  min_special      = 1
}

resource "aws_security_group" "dc" {
  name        = "${var.eks_cluster_name}-ad-dc"
  description = "AD domain controllers: everything from inside the VPC only"
  vpc_id      = data.aws_vpc.eks.id

  ingress {
    description = "AD, DNS, Kerberos, LDAP(S), SMB and replication from the VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [data.aws_vpc.eks.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.eks_cluster_name}-ad-dc"
  }
}

resource "aws_instance" "dc" {
  count                  = var.dc_count
  ami                    = data.aws_ami.windows.id
  instance_type          = var.instance_type
  subnet_id              = data.aws_subnet.dc[count.index].id
  private_ip             = local.dc_ips[count.index]
  vpc_security_group_ids = [aws_security_group.dc.id]

  user_data = templatefile(count.index == 0 ? "${path.module}/dc1.ps1.tftpl" : "${path.module}/dc-replica.ps1.tftpl", {
    computer_name      = "DC${count.index + 1}"
    domain_name        = var.domain_name
    netbios_name       = var.netbios_name
    admin_password     = random_password.admin.result
    test_user_password = random_password.test_user.result
    user_count         = var.user_count
    role_groups        = var.role_groups
    nest_depth         = var.nest_depth
    dc1_ip             = local.dc_ips[0]
  })

  root_block_device {
    volume_type = "gp3"
    volume_size = 50
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name = "${var.eks_cluster_name}-ad-dc${count.index + 1}"
  }

  lifecycle {
    # A new AMI release or script change must never replace a domain controller, that would
    # destroy the domain. Recreate the DCs deliberately instead, e.g. with -replace.
    ignore_changes = [ami, user_data]
  }
}
