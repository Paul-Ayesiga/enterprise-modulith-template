#!/usr/bin/env bash
# The "production feeling" demo on local k3s: run a request loop through the edge (Traefik -> gateway ->
# modulith) while we (1) roll the modulith + gateway to fresh pods, (2) kill a pod, (3) scale out and
# back — then print the tally. A healthy run ends `RESULT ... bad=0`: zero 5xx through every disruption.
#
#   scripts/k3s-demo.sh
#
# A `kubectl rollout restart` reuses the exact RollingUpdate + readiness-probe path a real image bump
# (`helm upgrade --set global.imageTag=...`) takes — same zero-downtime mechanics, no second build.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export KUBECONFIG="${KUBECONFIG:-$HOME/.kube/smsone-k3s.yaml}"
NS=smsone
DEMO="$root/deploy/k3s-local/demo"

echo "==> starting the in-cluster load generator"
kubectl create configmap loadgen-script -n "$NS" --from-file=loadgen.sh="$DEMO/loadgen.sh" \
  --dry-run=client -o yaml | kubectl apply -f -
kubectl delete pod loadgen -n "$NS" --ignore-not-found >/dev/null 2>&1
kubectl apply -f "$DEMO/loadgen-pod.yaml"
kubectl wait --for=jsonpath='{.status.phase}'=Running pod/loadgen -n "$NS" --timeout=40s

echo "==> (1) rolling update: modulith + gateway -> fresh pods"
kubectl rollout restart deploy/modulith deploy/gateway -n "$NS"
kubectl rollout status deploy/modulith -n "$NS" --timeout=320s
kubectl rollout status deploy/gateway  -n "$NS" --timeout=180s

echo "==> (2) self-heal: delete a modulith pod"
VICTIM=$(kubectl get pods -n "$NS" -l app.kubernetes.io/name=modulith -o jsonpath='{.items[0].metadata.name}')
kubectl delete pod "$VICTIM" -n "$NS" --wait=false
kubectl rollout status deploy/modulith -n "$NS" --timeout=180s

echo "==> (3) scale out 3 -> 5 -> 3"
kubectl scale deploy/modulith -n "$NS" --replicas=5
kubectl rollout status deploy/modulith -n "$NS" --timeout=220s
kubectl scale deploy/modulith -n "$NS" --replicas=3
kubectl rollout status deploy/modulith -n "$NS" --timeout=120s

echo "==> tally (waiting for the loadgen to finish its window)"
for _ in $(seq 1 60); do kubectl logs loadgen -n "$NS" 2>/dev/null | grep -q RESULT && break; sleep 3; done
kubectl logs loadgen -n "$NS" 2>&1 | grep -E 'RESULT|BAD' || kubectl logs loadgen -n "$NS" 2>&1 | tail -3
kubectl delete pod loadgen -n "$NS" --ignore-not-found >/dev/null 2>&1
echo "Done. bad=0 means zero 5xx through the rolling update, the pod kill, and the scale."
