# Written to the ad-loadtest-ad-outputs Secret by tofu-controller (see ../flux/ad.yaml) and copied to
# the app's namespace, where the chart uses adminPassword and testUserPassword (secrets.existingSecret).

output "adminPassword" {
  description = "Domain Administrator password"
  value       = random_password.admin.result
  sensitive   = true
}

output "testUserPassword" {
  description = "Password of the load-test users and svc-ltapp"
  value       = random_password.test_user.result
  sensitive   = true
}

output "dc1_ip" {
  description = "First domain controller's private IP, the chart's dc.host"
  value       = local.dc_ips[0]
}

output "dc_ips" {
  description = "All domain controllers' private IPs, comma separated"
  value       = join(",", local.dc_ips)
}

output "instance_ids" {
  description = "Domain controller instance IDs, comma separated"
  value       = join(",", aws_instance.dc[*].id)
}
