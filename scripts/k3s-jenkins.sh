#!/usr/bin/env bash
# Install the Jenkins controller on the local k3s cluster (self-hosted CI — no GitHub-hosted minutes) and
# print how to reach it. The pipeline is the repo's Jenkinsfile; wiring the job + credentials is a few
# clicks in the UI (see deploy/k3s-local/README.md § Self-hosted CI with Jenkins).
#
#   scripts/k3s-jenkins.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/smsone-k3s.yaml}"

echo "==> apply Jenkins (namespace jenkins: controller + PVC + RBAC + Service + ingress)"
kubectl apply -f "$root/deploy/k3s-local/jenkins/jenkins.yaml"

echo "==> waiting for the controller (first boot pulls the image + unpacks plugins)"
kubectl -n jenkins rollout status deploy/jenkins --timeout=300s

echo
echo "Initial admin password:"
kubectl -n jenkins exec deploy/jenkins -- cat /var/jenkins_home/secrets/initialAdminPassword 2>/dev/null || \
  echo "  (not ready yet — retry: kubectl -n jenkins exec deploy/jenkins -- cat /var/jenkins_home/secrets/initialAdminPassword)"
cat <<'EOF'

Open the UI (add jenkins.smsone.local to the Mac's /etc/hosts -> VM IP), then finish setup:
  http://jenkins.smsone.local     (or: kubectl -n jenkins port-forward svc/jenkins 8080:8080)

  1. Unlock with the password above; install suggested plugins + "Kubernetes" and "Pipeline: SCM polling".
  2. Manage Jenkins > Clouds > add a "Kubernetes" cloud (in-cluster; namespace: jenkins).
  3. Credentials: add 'ghcr' (username + GHCR PAT, write:packages) and 'git-push'
     (SSH private key allowed to push to main).
  4. New Item > "Pipeline" (or Multibranch) > Pipeline from SCM > this repo > script path: Jenkinsfile.
     Polling picks up pushes within ~5 min.
EOF
