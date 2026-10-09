#!/usr/bin/env bash
# Deploy VA-BAGS to the current kube-context (EKS). Idempotent: safe to re-run.
#
#   REGISTRY=<acct>.dkr.ecr.ap-south-1.amazonaws.com TAG=<git-sha> k8s-eks/scripts/deploy.sh
#
# Why a script and not just `kubectl apply -k`: with AWS-provided hostnames the public URLs only
# exist AFTER the load balancers are created, yet Keycloak (KC_HOSTNAME → token `iss`), every
# resource server (issuer check) and the OTT login redirect need them BEFORE they boot. So:
#   1. platform (namespace + gp3 StorageClass)
#   2. the three public NLB Services → wait for their hostnames
#   3. vabags-public ConfigMap (public URLs)                    — refreshed every run
#   4. vabags-secrets / gateway-tls / keycloak-tls              — created ONCE, then reused
#      (Postgres only reads POSTGRES_PASSWORD on first init, so rotating means a fresh PVC)
#   5. keycloak-realm ConfigMap rendered from deploy/keycloak/vab-realm.json
#   6. render the overlay, pin images to $REGISTRY/vabags/<svc>:$TAG, stamp the public-config
#      hash into pod annotations (→ restart only when URLs change), apply
#   7. wait: infra first, then Keycloak, then the services
#
# Needs: kubectl (with built-in kustomize), openssl, sha256sum, base64, sed.
set -euo pipefail

: "${REGISTRY:?set REGISTRY, e.g. 123456789012.dkr.ecr.ap-south-1.amazonaws.com}"
: "${TAG:?set TAG, e.g. the short git sha}"
NS="${NS:-vabags}"
AWS_REGION="${AWS_REGION:-ap-south-1}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K8S="$ROOT/k8s-eks"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

SERVICES="api-gateway order-service inventory-service billing-service notification-service catalog-service fulfilment-service ott-service"

log() { printf '\n==> %s\n' "$*"; }

# ── 1. platform ─────────────────────────────────────────────────────────────
log "platform: namespace + gp3 StorageClass"
kubectl apply -f "$K8S/platform/platform.yaml"

# ── 2. public NLBs ──────────────────────────────────────────────────────────
log "public load balancers"
kubectl apply -f "$K8S/overlays/eks/lb-services.yaml"

lb_host() {   # wait up to ~6 min for an NLB hostname
  local svc="$1" h=""
  for _ in $(seq 1 72); do
    h="$(kubectl -n "$NS" get svc "$svc" -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
    [ -n "$h" ] && { echo "$h"; return 0; }
    sleep 5
  done
  echo "timed out waiting for a hostname on svc/$svc" >&2; return 1
}
GW_HOST="$(lb_host api-gateway-public)"
KC_HOST="$(lb_host keycloak-public)"
OTT_HOST="$(lb_host ott-public)"

GATEWAY_PUBLIC_URL="https://${GW_HOST}"
KEYCLOAK_PUBLIC_URL="https://${KC_HOST}"
OTT_PUBLIC_URL="http://${OTT_HOST}"

# ── 3. public-URL config ────────────────────────────────────────────────────
log "vabags-public ConfigMap"
kubectl -n "$NS" create configmap vabags-public \
  --from-literal=GATEWAY_PUBLIC_URL="$GATEWAY_PUBLIC_URL" \
  --from-literal=KEYCLOAK_PUBLIC_URL="$KEYCLOAK_PUBLIC_URL" \
  --from-literal=KEYCLOAK_ISSUER="${KEYCLOAK_PUBLIC_URL}/realms/vab" \
  --from-literal=KC_HOSTNAME="$KEYCLOAK_PUBLIC_URL" \
  --from-literal=OTT_PUBLIC_URL="$OTT_PUBLIC_URL" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── 4. secrets (create once) ────────────────────────────────────────────────
rand() { openssl rand -hex 16; }
if ! kubectl -n "$NS" get secret vabags-secrets >/dev/null 2>&1; then
  log "vabags-secrets (generating)"
  kubectl -n "$NS" create secret generic vabags-secrets \
    --from-literal=SPRING_DATASOURCE_USERNAME=eventuate \
    --from-literal=SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD:-$(rand)}" \
    --from-literal=KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-$(rand)}" \
    --from-literal=KEYCLOAK_CLIENT_SECRET="${VAB_PROVISIONING_SECRET:-$(rand)}"
else
  log "vabags-secrets exists — reusing"
fi

if ! kubectl -n "$NS" get secret gateway-tls >/dev/null 2>&1 || ! kubectl -n "$NS" get secret keycloak-tls >/dev/null 2>&1; then
  log "TLS secrets (self-signed for *.elb.${AWS_REGION}.amazonaws.com)"
  OUT_DIR="$WORK/tls" AWS_REGION="$AWS_REGION" bash "$K8S/scripts/gen-selfsigned-tls.sh" "$GW_HOST" "$KC_HOST"
  kubectl -n "$NS" create secret tls keycloak-tls --cert="$WORK/tls/tls.crt" --key="$WORK/tls/tls.key" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$NS" create secret generic gateway-tls \
    --from-file=gateway-keystore.p12="$WORK/tls/gateway-keystore.p12" \
    --from-file=password="$WORK/tls/keystore-password" \
    --dry-run=client -o yaml | kubectl apply -f -
else
  log "TLS secrets exist — reusing"
fi

secret_val() { kubectl -n "$NS" get secret vabags-secrets -o jsonpath="{.data.$1}" | base64 -d; }

# ── 5. realm import ─────────────────────────────────────────────────────────
log "keycloak-realm ConfigMap (OTT redirect → $OTT_PUBLIC_URL)"
PROV_SECRET="$(secret_val KEYCLOAK_CLIENT_SECRET)"
sed -e "s#http://localhost:8087#${OTT_PUBLIC_URL}#g" \
    -e "s#\"vab-provisioning-secret\"#\"${PROV_SECRET}\"#g" \
    "$ROOT/deploy/keycloak/vab-realm.json" > "$WORK/vab-realm.json"
kubectl -n "$NS" create configmap keycloak-realm --from-file=vab-realm.json="$WORK/vab-realm.json" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── 6. render + apply ───────────────────────────────────────────────────────
log "render overlay → images ${REGISTRY}/vabags/<svc>:${TAG}"
PUBLIC_HASH="$(printf '%s|%s|%s' "$GATEWAY_PUBLIC_URL" "$KEYCLOAK_PUBLIC_URL" "$OTT_PUBLIC_URL" | sha256sum | cut -c1-16)"
kubectl kustomize --load-restrictor=LoadRestrictionsNone "$K8S/overlays/eks" > "$WORK/rendered.yaml"
for s in $SERVICES; do
  sed -i -E "s#image: vabags/${s}\$#image: ${REGISTRY}/vabags/${s}:${TAG}#" "$WORK/rendered.yaml"
done
sed -i "s#PUBLIC_CONFIG_HASH#\"${PUBLIC_HASH}\"#g" "$WORK/rendered.yaml"
if grep -nE 'image: vabags/' "$WORK/rendered.yaml"; then
  echo "unpinned image left in rendered manifests (see above)" >&2; exit 1
fi
kubectl apply -f "$WORK/rendered.yaml"

# ── 7. wait ─────────────────────────────────────────────────────────────────
log "waiting for infra"
kubectl -n "$NS" rollout status statefulset/postgres statefulset/kafka statefulset/mongo --timeout=600s
kubectl -n "$NS" rollout status deploy/redis deploy/zookeeper deploy/eventuate-cdc --timeout=600s
log "waiting for keycloak (first boot builds + imports the realm: several minutes)"
kubectl -n "$NS" rollout status deploy/keycloak --timeout=900s
log "waiting for services"
for s in $SERVICES; do kubectl -n "$NS" rollout status "deploy/$s" --timeout=600s; done

cat <<EOF

VA-BAGS is up (self-signed TLS — accept the browser warning once per host):
  Gateway   ${GATEWAY_PUBLIC_URL}/v1/offers
  Keycloak  ${KEYCLOAK_PUBLIC_URL}/realms/vab/.well-known/openid-configuration
  OTT       ${OTT_PUBLIC_URL}/v1/videos     (open Keycloak once first so the browser trusts it)
EOF
