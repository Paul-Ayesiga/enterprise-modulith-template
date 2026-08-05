#!/usr/bin/env bash
# Fetch the k3s kubeconfig from the Ubuntu/UTM VM and rewrite its server address so `kubectl` on the
# Mac can drive the cluster. Writes a SEPARATE file (default ~/.kube/smsone-k3s.yaml) — it does not
# touch your existing ~/.kube/config. Use it with `export KUBECONFIG=~/.kube/smsone-k3s.yaml`.
#
#   scripts/k3s-kubeconfig.sh                 # gopher@192.168.64.5, key ~/.ssh/smsone_k3s
#   VM=user@10.0.0.9 scripts/k3s-kubeconfig.sh
set -euo pipefail

VM="${VM:-gopher@192.168.64.5}"
VM_IP="${VM_IP:-${VM#*@}}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/smsone_k3s}"
OUT="${OUT:-$HOME/.kube/smsone-k3s.yaml}"

mkdir -p "$(dirname "$OUT")"
echo "Fetching /etc/rancher/k3s/k3s.yaml from $VM ..."
ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VM" 'sudo cat /etc/rancher/k3s/k3s.yaml' \
  | sed "s#https://127.0.0.1:6443#https://${VM_IP}:6443#" > "$OUT"
chmod 600 "$OUT"

echo "Wrote $OUT (server → https://${VM_IP}:6443)"
echo "Try:  KUBECONFIG=$OUT kubectl get nodes"
