#!/usr/bin/env bash
# One-shot local cluster bring-up: in-cluster state + dev-Keycloak + CoreDNS issuer rewrite, then the
# app (modulith ×3 + gateway) via the Helm chart with values-local.yaml. Idempotent — safe to re-run.
# Assumes k3s is already installed on the VM and scripts/k3s-images.sh has loaded the images.
#
#   scripts/k3s-up.sh
#
# Dev credentials only (a LOCAL cluster) — override via env before running for anything shared.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/smsone-k3s.yaml}"
NS=smsone
INFRA="$root/deploy/k3s-local/infra"
CHART="$root/deploy/helm/smsone"

# Dev secret values (kebab keys the chart reads + UPPER keys the infra pods read). Override via env.
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-modulith}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
GATEWAY_SECRET="${GATEWAY_SECRET:-dev-gateway-secret}"

echo "==> namespace + config"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl create configmap keycloak-realm -n "$NS" \
  --from-file=realm-smsone.json="$root/docker/keycloak/realm-smsone.json" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl create configmap seaweedfs-s3-config -n "$NS" \
  --from-file=s3-config.json="$root/docker/seaweedfs/s3-config.json" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> credentials secret"
kubectl create secret generic smsone-credentials -n "$NS" \
  --from-literal=POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD="$KEYCLOAK_ADMIN_PASSWORD" \
  --from-literal=GATEWAY_SECRET="$GATEWAY_SECRET" \
  --from-literal=postgres-user=modulith \
  --from-literal=postgres-password="$POSTGRES_PASSWORD" \
  --from-literal=killbill-user=admin \
  --from-literal=killbill-password=password \
  --from-literal=gateway-secret="$GATEWAY_SECRET" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> in-cluster state (postgres / valkey / seaweedfs)"
kubectl apply -f "$INFRA/state.yaml"

echo "==> issuer crux: CoreDNS rewrite auth.smsone.local -> keycloak Service"
kubectl apply -f "$INFRA/coredns-custom.yaml"
kubectl -n kube-system rollout restart deploy/coredns

echo "==> dev-Keycloak (start-dev --import-realm)"
kubectl apply -f "$INFRA/keycloak.yaml"

echo "==> waiting for state + Keycloak"
kubectl wait --for=condition=available --timeout=240s \
  deploy/postgres deploy/valkey deploy/seaweedfs deploy/keycloak -n "$NS"

echo "==> app via the chart (modulith ×3 + gateway)"
helm upgrade --install smsone "$CHART" -n "$NS" -f "$CHART/values-local.yaml"
kubectl rollout status deploy/modulith -n "$NS" --timeout=300s
kubectl rollout status deploy/gateway  -n "$NS" --timeout=180s

cat <<EOF

Up. Add to the Mac's /etc/hosts (once), then browse/curl:
  $(ssh -i "${SSH_KEY:-$HOME/.ssh/smsone_k3s}" "${VM:-gopher@192.168.64.5}" 'hostname -I 2>/dev/null | awk "{print \$1}"' 2>/dev/null || echo '<VM-IP>')  api.smsone.local auth.smsone.local

  TOKEN=\$(scripts/k3s-token.sh)
  curl -H "Authorization: Bearer \$TOKEN" http://api.smsone.local/api/v1/me
EOF
