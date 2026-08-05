#!/usr/bin/env bash
# Install/refresh the Jenkins controller on the local k3s cluster (self-hosted CI — no GitHub-hosted
# minutes). There is no setup wizard to click through: plugins come from jenkins/plugins.txt and the whole
# controller config — admin user, Kubernetes cloud, credentials, the pipeline job — comes from
# jenkins/jcasc.yaml. Re-run this after editing either file; it is idempotent.
#
#   scripts/k3s-jenkins.sh
#
# Optional env on first run (otherwise placeholders are stored and the pipeline stages that need them fail
# until you fill them in — see "Wiring the CI credentials" in deploy/k3s-local/README.md):
#
#   JENKINS_ADMIN_PASSWORD=...            # default: generated and printed below
#   GHCR_USER=... GHCR_PAT=...            # GitHub username + PAT with write:packages
#   GIT_PUSH_KEY_FILE=~/.ssh/id_ed25519   # private key allowed to push to main
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/smsone-k3s.yaml}"
jenkins_dir="$root/deploy/k3s-local/jenkins"

# Does the controller already exist? Decides whether we need to bounce it to pick up config changes.
existed=$(kubectl -n jenkins get deploy jenkins -o name 2>/dev/null || true)

echo "==> namespace"
kubectl create namespace jenkins --dry-run=client -o yaml | kubectl apply -f -

echo "==> config (plugins.txt + jcasc.yaml -> configmap/jenkins-config)"
kubectl -n jenkins create configmap jenkins-config \
  --from-file="$jenkins_dir/plugins.txt" \
  --from-file="$jenkins_dir/jcasc.yaml" \
  --dry-run=client -o yaml | kubectl apply -f -

# Credentials live only in the cluster, never in Git. Created once, then left alone so re-running this
# script never clobbers credentials you have since filled in.
if kubectl -n jenkins get secret jenkins-secrets >/dev/null 2>&1; then
  echo "==> secret/jenkins-secrets already exists — leaving it untouched"
else
  echo "==> secret/jenkins-secrets (admin password + CI credentials)"
  admin_pw="${JENKINS_ADMIN_PASSWORD:-$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)}"
  key_file="${GIT_PUSH_KEY_FILE:-}"
  git_push_key=""
  if [ -n "$key_file" ] && [ -f "${key_file/#\~/$HOME}" ]; then
    git_push_key="$(cat "${key_file/#\~/$HOME}")"
  fi
  kubectl -n jenkins create secret generic jenkins-secrets \
    --from-literal=JENKINS_ADMIN_PASSWORD="$admin_pw" \
    --from-literal=GHCR_USER="${GHCR_USER:-unset}" \
    --from-literal=GHCR_PAT="${GHCR_PAT:-unset}" \
    --from-literal=GIT_PUSH_KEY="$git_push_key"
fi

echo "==> apply Jenkins (controller + PVC + RBAC + Service + ingress)"
kubectl apply -f "$jenkins_dir/jenkins.yaml"

# A ConfigMap/Secret change alone does not restart pods, and JCasC is only read at boot.
if [ -n "$existed" ]; then
  echo "==> restarting the controller to re-read plugins.txt + jcasc.yaml"
  kubectl -n jenkins rollout restart deploy/jenkins
fi

echo "==> waiting for the controller (first boot pulls the image and downloads plugins — a few minutes)"
kubectl -n jenkins rollout status deploy/jenkins --timeout=600s

admin_pw=$(kubectl -n jenkins get secret jenkins-secrets \
  -o jsonpath='{.data.JENKINS_ADMIN_PASSWORD}' | base64 -d)
ghcr_user=$(kubectl -n jenkins get secret jenkins-secrets -o jsonpath='{.data.GHCR_USER}' | base64 -d)
git_key=$(kubectl -n jenkins get secret jenkins-secrets -o jsonpath='{.data.GIT_PUSH_KEY}' | base64 -d)

cat <<EOF

Jenkins is up and fully configured — no setup wizard, no plugin install, no job to create by hand.

  http://jenkins.smsone.local          (add it to the Mac's /etc/hosts -> VM IP)
  kubectl -n jenkins port-forward svc/jenkins 8080:8080      (alternative: http://localhost:8080)

  user: admin
  pass: $admin_pw

Already configured from deploy/k3s-local/jenkins/jcasc.yaml: the 'k3s' Kubernetes cloud, the 'ghcr' and
'git-push' credentials, and the multibranch job 'enterprise-modulith-template' (scans main every 5 min).
EOF

# Only nag about credentials that are still placeholders.
if [ "$ghcr_user" = "unset" ] || [ -z "$git_key" ]; then
  cat <<'EOF'

STILL TO DO — the CI credentials are placeholders, so builds will fail at the stage that uses them:

  kubectl -n jenkins delete secret jenkins-secrets
  GHCR_USER=<github-user> GHCR_PAT=<PAT with write:packages> \
    GIT_PUSH_KEY_FILE=~/.ssh/id_ed25519 scripts/k3s-jenkins.sh

(That re-creates the secret and restarts the controller. The admin password is regenerated too — pass
JENKINS_ADMIN_PASSWORD=... to keep the one above.)
EOF
fi
