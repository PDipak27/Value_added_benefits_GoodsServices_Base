#!/usr/bin/env bash
# Tear the FULL-edition cluster down (the $ discipline). Order matters:
#   1. delete the app namespace FIRST, while the EBS CSI driver still runs, so the PVCs' gp3
#      volumes really get deleted (reclaimPolicy Delete). `eksctl delete cluster` on its own
#      leaves them orphaned — and billed.
#   2. that also removes the three LoadBalancer Services → their NLBs.
#   3. eksctl deletes the node-group + cluster CloudFormation stacks.
# ECR repos are kept (lifecycle-policied, pennies); delete them by hand if wanted.
set -euo pipefail
AWS_REGION="${AWS_REGION:-ap-south-1}"
CLUSTER_NAME="${CLUSTER_NAME:-vabags-full}"

if aws eks describe-cluster --name "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1; then
  aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION"
  echo "==> deleting namespace vabags (PVCs → EBS volumes, LB Services → NLBs)"
  kubectl delete namespace vabags --ignore-not-found --wait=true --timeout=10m || true
  echo "==> waiting for the namespace's persistent volumes to be released"
  for _ in $(seq 1 60); do
    left="$(kubectl get pv --no-headers 2>/dev/null | grep -c ' vabags/' || true)"
    [ "$left" = "0" ] && break
    sleep 5
  done
  echo "==> eksctl delete cluster"
  eksctl delete cluster --name "$CLUSTER_NAME" --region "$AWS_REGION" --wait
else
  echo "cluster $CLUSTER_NAME not found in $AWS_REGION — nothing to delete"
fi

echo "Orphaned-volume check (should print nothing):"
aws ec2 describe-volumes --region "$AWS_REGION" \
  --filters "Name=tag:kubernetes.io/cluster/${CLUSTER_NAME},Values=owned" "Name=status,Values=available" \
  --query 'Volumes[].VolumeId' --output text || true
