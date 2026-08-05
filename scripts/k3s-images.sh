#!/usr/bin/env bash
# Build the arm64 modulith + gateway images on the Mac and load them straight into the VM's containerd
# (k3s), so the chart can run them with imagePullPolicy: Never. No registry in this loop — we stream a
# `docker save` over SSH into `k3s ctr images import`. (The registry alternative is in
# deploy/k3s-local/registries.yaml; this import path is the default because it's zero-infra.)
#
#   scripts/k3s-images.sh                # build + import smsone/{modulith,gateway}:dev
#   TAG=dev2 scripts/k3s-images.sh       # a second tag, e.g. to demo a rolling upgrade
#   SKIP_BUILD=1 scripts/k3s-images.sh   # reuse the existing local images, just (re)import
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VM="${VM:-gopher@192.168.64.5}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/smsone_k3s}"
TAG="${TAG:-dev}"

if [ -z "${SKIP_BUILD:-}" ]; then
  echo "==> building images (Dockerfile targets modulith, gateway)"
  docker build -t "smsone-modulith:local" --target modulith "$root"
  docker build -t "smsone-gateway:local"  --target gateway  "$root"
fi

echo "==> tagging smsone/{modulith,gateway}:${TAG}"
docker tag smsone-modulith:local "smsone/modulith:${TAG}"
docker tag smsone-gateway:local  "smsone/gateway:${TAG}"

echo "==> streaming ~1.6GB into k3s containerd on ${VM} (gzip -1 over SSH)"
docker save "smsone/modulith:${TAG}" "smsone/gateway:${TAG}" \
  | gzip -1 \
  | ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$VM" 'gunzip -c | sudo k3s ctr images import -'

echo "==> present in containerd:"
ssh -i "$SSH_KEY" "$VM" 'sudo k3s ctr images ls -q | grep smsone'
