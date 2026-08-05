# Local Kubernetes (k3s) — run the platform on a cluster on your Mac

Deploy the modulith (×3) + gateway on **k3s** running in an Ubuntu Server VM (UTM, arm64), so you get a
production-shaped Kubernetes environment locally — rolling updates, self-healing, scaling, the works.
The full rationale and phase gates are in [`../../docs/K8S_LOCAL_PLAN.md`](../../docs/K8S_LOCAL_PLAN.md).

**What runs where:** the app comes from the **Helm chart** (`../helm/smsone`) with a
[`values-local.yaml`](../helm/smsone/values-local.yaml) overlay; the **state** (Postgres, Valkey,
SeaweedFS) and a **dev-Keycloak** are plain manifests in [`infra/`](infra/) — the chart is
state-*external*, this layer stands the state up in-cluster.

## Prerequisites

- **VM:** Ubuntu Server arm64 in UTM, ≥ 8 GB RAM / 4 vCPU, reachable from the Mac. Install k3s:
  `curl -sfL https://get.k3s.io | sh -`. No Docker on the VM — k3s ships containerd.
- **Mac:** `docker` (builds the arm64 images), `kubectl`, `helm`, and SSH to the VM
  (default `gopher@192.168.64.5`, key `~/.ssh/smsone_k3s` — override with `VM=` / `SSH_KEY=`).

## Bring it up (four commands)

```bash
scripts/k3s-kubeconfig.sh          # fetch + rewrite kubeconfig -> ~/.kube/smsone-k3s.yaml
export KUBECONFIG=~/.kube/smsone-k3s.yaml
scripts/k3s-images.sh              # build modulith+gateway, stream them into the VM's containerd
scripts/k3s-up.sh                  # namespace, secret, state, dev-Keycloak, CoreDNS, then the chart
```

Then add **one** line to the Mac's `/etc/hosts` (needs sudo — the browser/curl resolve these to the VM):

```
<VM-IP>  api.smsone.local auth.smsone.local
```

Drive it end to end:

```bash
TOKEN=$(scripts/k3s-token.sh)
curl -H "Authorization: Bearer $TOKEN" http://api.smsone.local/api/v1/me      # -> 200, requestId "gw-…"
```

## The "production feeling" demo

```bash
scripts/k3s-demo.sh     # request loop through the edge while we roll / kill / scale — ends bad=0
```

It runs an in-cluster load generator against `/api/v1/me` and, while it samples, rolls the modulith +
gateway to fresh pods, deletes a pod (self-heal), and scales 3→5→3. A healthy run prints
`RESULT total=N ok=N bad=0` — **zero 5xx** through every disruption, because the load balancing is
Kubernetes' (the gateway targets the `modulith` Service; kube-proxy spreads the pods) and zero-downtime
is rolling updates + readiness probes.

## How the tricky parts work

- **The issuer crux (CoreDNS).** Every token's `iss` is `http://auth.smsone.local/realms/smsone`
  (Keycloak `KC_HOSTNAME`). [`infra/coredns-custom.yaml`](infra/coredns-custom.yaml) rewrites that host,
  *inside* the cluster, to the `keycloak` Service — so the modulith (`issuerUri` = same host) and the
  gateway (JWKS = same host) validate the very tokens the browser mints. One issuer, both sides.
- **`enableServiceLinks: false`.** Kubernetes injects Docker-style `{SVC}_PORT=tcp://ip:port` env vars
  for every Service; those collide with the app's own `VALKEY_PORT` / `GATEWAY_PORT`. The app finds
  everything by DNS Service name, so the chart switches the legacy links off.
- **Images: ctr-import, not a registry.** `scripts/k3s-images.sh` streams `docker save` over SSH into
  `k3s ctr images import`, and the chart pulls with `imagePullPolicy: Never`. Zero extra infra. The
  registry alternative (closer to prod) is [`registries.yaml`](registries.yaml).

## Teardown

```bash
helm uninstall smsone -n smsone            # the app
kubectl delete -f infra/ ; kubectl delete ns smsone   # state + dev-Keycloak (drops PVC data)
```
