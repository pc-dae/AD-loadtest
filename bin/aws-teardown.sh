#!/usr/bin/env bash
# Destroys the AD load tester's AWS infrastructure created by tofu-controller on the EKS cluster, see infra/aws/flux
# Run it before tearing down the EKS cluster: the domain controllers are in the EKS VPC, which can't be deleted while
# they exist.
set -euo pipefail

function usage() {
    echo "usage: ${0} [--yes] [--debug]" >&2
    echo "This script destroys the AD load tester's domain controllers. It suspends the ad-loadtest-infra Kustomization" >&2
    echo "on the EKS cluster, so Flux doesn't recreate the Terraform object, then deletes it and tofu-controller destroys" >&2
    echo "the AWS resources. Then tear down the cluster from the cluster repository with eks-teardown.sh." >&2
    echo "Use flux resume kustomization ad-loadtest-infra -n flux-system to create the domain controllers again." >&2
    echo "kubectl must use the EKS cluster, e.g. run it in the cluster repository's target-cluster folder" >&2
    echo "  --yes    Do not ask for confirmation." >&2
    echo "  --debug  Enable debug mode (set -x)." >&2
    exit 1
}

YES=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --yes) YES=true; shift ;;
        --debug) set -x; shift ;;
        --help) usage ;;
        *) echo "Unknown argument: $1" >&2; usage ;;
    esac
done

server="$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}' 2>/dev/null || true)"
if [[ "${server}" != *.eks.amazonaws.com ]]; then
    echo "kubectl's current context, ${server:-none}, is not an EKS cluster, set KUBECONFIG, e.g. ~/.kube/eks-ad-loadtest.yaml" >&2
    exit 1
fi

if [[ "${YES}" != "true" ]]; then
    read -rp "Destroy the AD load tester's domain controllers? [y/N] " answer
    if [[ "${answer}" != "y" && "${answer}" != "Y" ]]; then
        exit 1
    fi
fi

flux suspend kustomization ad-loadtest-infra -n flux-system
kubectl delete resourcesets.fluxcd.controlplane.io ad-loadtest-secrets -n flux-system --ignore-not-found
kubectl delete terraforms.infra.contrib.fluxcd.io ad-loadtest-ad -n flux-system --ignore-not-found --wait=false
echo "Waiting for ad-loadtest-ad to be destroyed..."
if ! kubectl wait --for=delete terraforms.infra.contrib.fluxcd.io/ad-loadtest-ad -n flux-system --timeout=30m 2>/dev/null; then
    if kubectl get terraforms.infra.contrib.fluxcd.io ad-loadtest-ad -n flux-system >/dev/null 2>&1; then
        echo "ad-loadtest-ad has not been destroyed, see kubectl describe terraform ad-loadtest-ad -n flux-system" >&2
        exit 1
    fi
fi
echo "The AD load tester's domain controllers have been destroyed"
