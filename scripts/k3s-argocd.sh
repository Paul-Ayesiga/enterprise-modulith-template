#!/usr/bin/env bash
# Install Argo CD on the local k3s cluster and point it at deploy/helm/smsone (GitOps). After this you
# DEPLOY BY COMMITTING to Git — Argo reconciles the `smsone` namespace to match, and reverts manual drift.
#
#   scripts/k3s-argocd.sh
#
# Needs: kubectl (KUBECONFIG set) and gh (authed) — the repo is private, so we add a READ-ONLY deploy key
# for Argo to clone with. Idempotent; safe to re-run.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/smsone-k3s.yaml}"
REPO="${REPO:-Paul-Ayesiga/enterprise-modulith-template}"
REPO_SSH="git@github.com:${REPO}.git"
KEY="${KEY:-$HOME/.ssh/argocd_${REPO##*/}_deploy}"

echo "==> install Argo CD (server-side apply avoids the oversized-CRD limit)"
kubectl create namespace argocd --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n argocd --server-side=true --force-conflicts \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

echo "==> trim components we don't use on a small VM (SSO / appsets / notifications)"
kubectl -n argocd scale deploy/argocd-dex-server deploy/argocd-applicationset-controller \
  deploy/argocd-notifications-controller --replicas=0 || true

echo "==> serve the UI over plain HTTP (local — behind a port-forward or ingress)"
kubectl -n argocd patch configmap argocd-cmd-params-cm --type merge -p '{"data":{"server.insecure":"true"}}'
kubectl -n argocd rollout restart deploy argocd-server
kubectl -n argocd rollout status deploy argocd-server --timeout=180s

echo "==> read-only deploy key so Argo can clone the PRIVATE repo"
[ -f "$KEY" ] || ssh-keygen -t ed25519 -f "$KEY" -N "" -C "argocd-local-readonly"
gh repo deploy-key add "$KEY.pub" -R "$REPO" --title "argocd-local (read-only)" 2>/dev/null \
  || echo "   (a deploy key with that title already exists — reusing)"
kubectl -n argocd create secret generic repo-smsone \
  --from-literal=type=git --from-literal=url="$REPO_SSH" \
  --from-file=sshPrivateKey="$KEY" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n argocd label secret repo-smsone argocd.argoproj.io/secret-type=repository --overwrite

echo "==> the Application (auto-sync + self-heal)"
kubectl apply -f "$root/deploy/k3s-local/argocd/application.yaml"

echo
echo "Argo admin password:"
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
cat <<EOF

Open the UI (leave running, then browse http://localhost:8080 — user: admin):
  kubectl -n argocd port-forward svc/argocd-server 8080:80

From now on, DEPLOY BY COMMITTING to the tracked branch. Argo syncs within ~3 min, or force it:
  kubectl -n argocd annotate application smsone argocd.argoproj.io/refresh=hard --overwrite
EOF
