#!/usr/bin/env bash
# Self-signed TLS material for the FULL edition on AWS-provided hostnames.
#
# Why self-signed: without a domain of our own there is nothing to prove ownership of, so ACM /
# Let's Encrypt can't issue a cert for `*.elb.<region>.amazonaws.com`. The gateway and Keycloak
# therefore terminate TLS themselves (NLB = TCP passthrough) with this cert; clients accept it
# explicitly (browser warning once; e2e uses RestAssured relaxed HTTPS; curl -k).
#
# Outputs (in $OUT_DIR):
#   tls.crt / tls.key          → Keycloak (KC_HTTPS_CERTIFICATE_FILE / _KEY_FILE)
#   gateway-keystore.p12       → api-gateway (server.ssl, alias "gateway")
#   keystore-password          → password of the p12
#
# Works the same for a plain EC2 host (docker run -v $OUT_DIR:/etc/vab/tls ... with
# GATEWAY_SSL_KEYSTORE=file:/etc/vab/tls/gateway-keystore.p12). Pass extra SANs (e.g. the EC2
# public DNS name) as arguments:  gen-selfsigned-tls.sh ec2-13-233-1-2.ap-south-1.compute.amazonaws.com
set -euo pipefail
# Git Bash on Windows would rewrite "/CN=..." into a Windows path; no-op on Linux.
export MSYS_NO_PATHCONV=1

REGION="${AWS_REGION:-ap-south-1}"
OUT_DIR="${OUT_DIR:-./tls-out}"
DAYS="${DAYS:-365}"
mkdir -p "$OUT_DIR"

# NLB DNS: <name>-<id>.elb.<region>.amazonaws.com ; classic ELB: <name>-<id>.<region>.elb.amazonaws.com
SAN="DNS:*.elb.${REGION}.amazonaws.com,DNS:*.${REGION}.elb.amazonaws.com,DNS:*.${REGION}.compute.amazonaws.com,DNS:localhost,IP:127.0.0.1"
for extra in "$@"; do SAN="${SAN},DNS:${extra}"; done

PASS="${KEYSTORE_PASSWORD:-$(openssl rand -hex 16)}"

openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
  -keyout "$OUT_DIR/tls.key" -out "$OUT_DIR/tls.crt" \
  -subj "/CN=vabags-selfsigned/O=VA-BAGS demo" \
  -addext "subjectAltName=${SAN}" \
  -addext "keyUsage=digitalSignature,keyEncipherment" \
  -addext "extendedKeyUsage=serverAuth"

openssl pkcs12 -export -name gateway \
  -inkey "$OUT_DIR/tls.key" -in "$OUT_DIR/tls.crt" \
  -out "$OUT_DIR/gateway-keystore.p12" -passout "pass:${PASS}"

printf '%s' "$PASS" > "$OUT_DIR/keystore-password"
chmod 600 "$OUT_DIR/tls.key" "$OUT_DIR/keystore-password" "$OUT_DIR/gateway-keystore.p12" 2>/dev/null || true
echo "TLS material written to $OUT_DIR (SANs: $SAN)"
