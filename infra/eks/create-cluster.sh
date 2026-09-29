#!/usr/bin/env bash
# Create the FULL-edition EKS cluster + ECR repos, then the cluster-level platform objects.
#
#   AWS_REGION=ap-south-1 CLUSTER_NAME=vabags-full \
#   JENKINS_ROLE_ARN=arn:aws:iam::<acct>:role/<jenkins-ec2-role> infra/eks/create-cluster.sh
#
# Run it as whoever should own the cluster: your admin laptop, or the Jenkins role via
# jenkins/Jenkinsfile.infra (eksctl then needs broad IAM/EC2/CloudFormation/EKS rights on it).
# If JENKINS_ROLE_ARN is empty, or IS the caller, no extra access entry is added — the creator
# already gets cluster-admin, and a duplicate entry would make eksctl fail.
# ~15–20 min. Cost while it exists: control plane $0.10/h + nodes + NLBs + EBS → delete-cluster.sh!
set -euo pipefail
AWS_REGION="${AWS_REGION:-ap-south-1}"
CLUSTER_NAME="${CLUSTER_NAME:-vabags-full}"
JENKINS_ROLE_ARN="${JENKINS_ROLE_ARN:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SERVICES="api-gateway order-service inventory-service billing-service notification-service catalog-service fulfilment-service ott-service"

# assumed-role ARN (arn:aws:sts::A:assumed-role/NAME/SESSION) → role ARN (arn:aws:iam::A:role/NAME)
CALLER="$(aws sts get-caller-identity --query Arn --output text)"
CALLER_ROLE="$(echo "$CALLER" | sed -E 's#^arn:aws:sts::([0-9]+):assumed-role/([^/]+)/.*#arn:aws:iam::\1:role/\2#')"

RENDERED="$(mktemp)"
trap 'rm -f "$RENDERED"' EXIT
sed -e "s#__CLUSTER_NAME__#${CLUSTER_NAME}#" -e "s#__AWS_REGION__#${AWS_REGION}#" \
    -e "s#__JENKINS_ROLE_ARN__#${JENKINS_ROLE_ARN}#" "$HERE/cluster.template.yaml" > "$RENDERED"
if [ -z "$JENKINS_ROLE_ARN" ] || [ "$JENKINS_ROLE_ARN" = "$CALLER_ROLE" ]; then
  echo "No extra access entry (JENKINS_ROLE_ARN empty or equal to the caller $CALLER_ROLE)"
  sed -i '/__ACCESS_ENTRIES_BEGIN__/,/__ACCESS_ENTRIES_END__/d' "$RENDERED"
fi

echo "==> eksctl create cluster ($CLUSTER_NAME, $AWS_REGION)"
eksctl create cluster -f "$RENDERED"

echo "==> platform objects (namespace + gp3 StorageClass)"
aws eks update-kubeconfig --name "$CLUSTER_NAME" --region "$AWS_REGION"
kubectl apply -f "$ROOT/k8s-full/platform/platform.yaml"

echo "==> ECR repositories (scan on push + keep-last-10 lifecycle policy)"
for s in $SERVICES; do
  aws ecr describe-repositories --repository-names "vabags/$s" --region "$AWS_REGION" >/dev/null 2>&1 \
    || aws ecr create-repository --repository-name "vabags/$s" --region "$AWS_REGION" \
         --image-scanning-configuration scanOnPush=true >/dev/null
  aws ecr put-lifecycle-policy --repository-name "vabags/$s" --region "$AWS_REGION" \
    --lifecycle-policy-text "file://$HERE/ecr-lifecycle-policy.json" >/dev/null
done
echo "Cluster ready. Deploy via jenkins/Jenkinsfile.full, or: REGISTRY=... TAG=... k8s-full/scripts/deploy.sh"
