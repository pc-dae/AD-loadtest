#!/usr/bin/env bash
# Shows what the AD load tester is running in AWS and roughly what it costs per hour, to keep a time and
# budget-limited sandbox account in check. Uses the AWS CLI's current credentials, e.g. AWS_PROFILE.
set -euo pipefail

region="${AWS_REGION:-$(aws configure get region)}"
echo "Account $(aws sts get-caller-identity --query Account --output text), region ${region}"

echo
echo "EC2 instances (running or pending):"
aws ec2 describe-instances --region "${region}" \
    --filters Name=instance-state-name,Values=running,pending \
    --query 'Reservations[].Instances[].[Tags[?Key==`Name`]|[0].Value, InstanceType, Platform || `linux`, PrivateIpAddress, LaunchTime]' \
    --output table

echo
echo "EKS clusters: $(aws eks list-clusters --region "${region}" --query 'clusters' --output text)"
echo "NAT gateways: $(aws ec2 describe-nat-gateways --region "${region}" --filter Name=state,Values=available \
    --query 'length(NatGateways)' --output text)"
echo "Load balancers: $(aws elbv2 describe-load-balancers --region "${region}" --query 'length(LoadBalancers)' --output text)"

cat <<'NOTE'

Approximate eu-west-1 on-demand prices: EKS control plane $0.10/h, t3.large node $0.09/h,
t3.medium Windows DC $0.06/h, NAT gateway $0.05/h plus data, NLB $0.03/h.
Tear down when idle: bin/aws-teardown.sh (domain controllers) then eks-teardown.sh (cluster).
NOTE
