# Local Kubernetes (k3s) — deployment plan

> **Status — SHIPPED (2026-08-05).** All phases (0–3) are live: cluster + image loop, in-cluster state +
> dev-Keycloak with the issuer/CoreDNS crux proven, the app via the chart (`/api/v1/me` 200 end to end),
> and the production-feeling demo — a rolling `helm upgrade`, a pod kill, and a 3→5→3 scale under a live
> request loop: **699/699 requests, zero 5xx**. Operator guide + the four bring-up commands:
> [`../deploy/k3s-local/README.md`](../deploy/k3s-local/README.md). One deviation from the plan below: the
> image loop shipped as `ctr`-import, not a registry (§1) — the registry stays documented as the alternative.

## 1. Goal & decisions

Run the platform on **k3s on an Ubuntu Server VM (UTM, arm64)** so a MacBook gets a production-shaped
Kubernetes environment locally — the "production feeling" without a cloud. Kubernetes-first per the
Containerization & Kubernetes standard: OCI images, no Docker at runtime, config/secrets/state in the
cluster, Services (not pod IPs), rolling updates, health probes, resource limits, logs to stdout.

Decisions (locked with the user):
- **Image loop: `docker save` → `k3s ctr images import` (shipped).** Build arm64 on the Mac and stream
  the images straight into the VM's containerd over SSH (`scripts/k3s-images.sh`); the chart runs them with
  `imagePullPolicy: Never`. This proved simpler than a registry — zero extra infra — so it's the default.
  The **local-registry** path (build → push → `helm upgrade` pins the tag) is kept as a documented
  alternative (`deploy/k3s-local/registries.yaml`) for a more prod-like pull. Prod stays GHCR (`.github/workflows/ci.yml`).
- **State: plain in-cluster manifests.** Postgres / Valkey / SeaweedFS / dev-Keycloak as our own
  StatefulSet/Deployment + Service + PVC, mirroring `docker/docker-compose.yml`. Kept *separate* from
  the state-external prod chart.
- **Scope: core.** modulith ×3 + gateway + Postgres + Keycloak + Valkey + SeaweedFS. Billing
  (Kill Bill), the portals, TLS, and GitOps are deferred (§8).

Reuse vs. new:
- **Reuse `deploy/helm/smsone`** for the app (modulith, gateway) via a new `values-local.yaml`. The
  chart already carries Deployments, Services, probes, resources, config/secret plumbing, and the
  ingress — it just points at *external* state in prod; locally we point it at in-cluster Services.
- **New `deploy/k3s-local/`** for the in-cluster state, the registry trust, CoreDNS, and the one-shot
  scripts.

## 2. Topology

```
Mac (Apple Silicon)                     Ubuntu VM (UTM, arm64)
  docker · kubectl · helm                k3s: containerd · Traefik · CoreDNS · local-path · metrics-server
  build arm64 image ── push ──▶ local registry (:5000) ──▶ containerd
  ~/.kube/config (server → VM IP)                       ┌──────── namespace: smsone ────────┐
  /etc/hosts:  <VM IP>  api.smsone.local  auth.smsone.local │ gateway ─http://modulith:8080▶ modulith ×3
        │                                                    │ keycloak · postgres · valkey · seaweedfs
        └──── browser/curl ─▶ VM IP:80 ─▶ Traefik ─▶ Ingress ┴───────────────────────────────────┘
```

**Load balancing is Kubernetes'**, not the gateway's: the gateway targets the `modulith` **Service**
(`http://modulith:8080`) and kube-proxy spreads across the 3 pods — the guideline's "route via
Services, not pod IPs," and the counterpart to the SCLB Docker demo. Zero-downtime is **rolling
updates + readiness probes**, not SCLB health-eject. (The gateway's own service config stays
single-URI here; the `multi` profile / SCLB is the Docker-compose path, not the k8s path.)

## 3. Prerequisites

- **Mac:** `docker` (arm64 image build — the multi-stage `Dockerfile` we already have), `kubectl`,
  `helm`. VM reachable over the network.
- **VM:** Ubuntu Server **arm64** (native under UTM on Apple Silicon), ≥ **8 GB RAM** / 4 vCPU (three
  modulith JVMs + Keycloak + Postgres + the rest — size generously and tune requests down, §6).
  UTM network **"Shared Network"** so the VM gets a stable IP reachable from the Mac (`ip -4 addr`).

## 4. Phase 0 — cluster + image loop + networking

*Gate: `kubectl get nodes` from the Mac; a test image pushed to the registry runs as a pod reachable
through Traefik.*

1. **k3s** (single node, arm64) on the VM — ships containerd, Traefik (ingress), local-path
   (PVCs), CoreDNS, metrics-server, ServiceLB. No Docker on the VM.
   ```
   curl -sfL https://get.k3s.io | sh -
   ```
2. **kubeconfig on the Mac:** copy `/etc/rancher/k3s/k3s.yaml` from the VM, rewrite `server:
   https://127.0.0.1:6443` → `https://<VM-IP>:6443`, save as `~/.kube/config` (or merge). `kubectl get
   nodes` should work from the Mac.
3. **Local registry** the cluster trusts:
   - Run `registry:2` on the VM (a systemd container or a k3s Deployment + NodePort) at `<VM-IP>:5000`.
   - `/etc/rancher/k3s/registries.yaml` → declare `<VM-IP>:5000` as an insecure mirror; restart k3s.
   - Push test: `docker build … -t <VM-IP>:5000/smsone/modulith:dev && docker push …`.
4. **/etc/hosts on the Mac:** `<VM-IP>  api.smsone.local auth.smsone.local` so the browser/curl reach
   Traefik on the VM.

## 5. Phase 1 — state in-cluster (`deploy/k3s-local/infra/`)

*Gate: a token minted through the ingress Keycloak carries `iss=http://auth.smsone.local/realms/smsone`
AND an in-cluster pod resolving `auth.smsone.local` reaches Keycloak (issuer consistency proven before
the app exists).*

- **Namespace** `smsone`; a **`smsone-credentials` Secret** (DB creds, `GATEWAY_SECRET`, Keycloak
  admin, the `smsone-api` client secret) created out-of-band — never committed (a `secret.example.yaml`
  template only).
- **Postgres** — StatefulSet + `postgres` Service (:5432) + PVC (local-path). An init ConfigMap creates
  **two** databases: `modulith` (app) and `keycloak` (Keycloak's own — never shared).
- **Valkey** — Deployment + `valkey` Service (:6379).
- **SeaweedFS** — Deployment + `seaweedfs` Service (:8333) + PVC + the `s3-config.json` ConfigMap.
- **Keycloak (dev)** — Deployment + `keycloak` Service (:8080), `start-dev --import-realm` with
  `docker/keycloak/realm-smsone.json` mounted from a ConfigMap (so `paul` exists), DB → `postgres:5432/keycloak`,
  admin from the Secret, **`KC_HOSTNAME=auth.smsone.local`** + proxy headers. A Traefik Ingress
  `auth.smsone.local` → `keycloak:8080`.
- **The crux — issuer consistency (CoreDNS).** Pin one issuer host everywhere:
  - Keycloak frontend `KC_HOSTNAME=auth.smsone.local` → every token's `iss` is
    `http://auth.smsone.local/realms/smsone`.
  - The modulith's `issuerUri` is the **same** host — but in-cluster it must resolve to Keycloak. Add a
    **`coredns-custom` ConfigMap** (kube-system) with a `hosts`/`rewrite` entry mapping
    `auth.smsone.local` → the `keycloak` Service ClusterIP. Now the modulith (in-cluster) and the Mac
    browser (via `/etc/hosts` → Traefik → keycloak) share the identical issuer, so JWTs validate on both
    sides. This is the single most failure-prone step; it gets its own gate above.

## 6. Phase 2 — the app via the chart (`values-local.yaml`)

*Gate: `curl -H "Authorization: Bearer <token>" http://api.smsone.local/api/v1/me` returns 200 end to
end (Traefik → gateway → a modulith pod).*

- **Build + load images** (arm64, our `Dockerfile`) into the VM's containerd: `scripts/k3s-images.sh`
  → `smsone/modulith:dev` and `smsone/gateway:dev` (or the `registries.yaml` push path).
- **`deploy/helm/smsone/values-local.yaml`:**
  - `global.imageBase=smsone`, `imageTag=dev`, `imagePullPolicy=Never` (containerd-local images).
  - `keycloak.enabled=false` — the chart's Keycloak is prod-mode (`start --optimized`, no import); locally
    we use the dev-Keycloak from §5. `modulith.issuerUri=http://auth.smsone.local/realms/smsone`.
  - Point external endpoints at in-cluster Services: `postgres.host=postgres`, `valkey.host=valkey`,
    `seaweedfs.s3Endpoint=http://seaweedfs:8333`.
  - `modulith.replicas=3`; the gateway's `MODULITH_URI=http://modulith:8080` (Service LB) and
    `KEYCLOAK_JWKS=http://auth.smsone.local/realms/smsone/protocol/openid-connect/certs`.
  - `ingress.className=traefik`, `hosts.api=api.smsone.local` (the gateway). Auth host is owned by the
    §5 dev-Keycloak ingress.
  - `TRUSTED_PROXY_HOPS`: Traefik → gateway → modulith, so gateway `=1`, modulith `=2` (as prod does).
  - Trim `resources.requests` for the VM.
- `helm upgrade --install smsone deploy/helm/smsone -n smsone -f deploy/helm/smsone/values-local.yaml`.

## 7. Phase 3 — the production feeling

*Gate: a rolling `helm upgrade` with a request loop running shows zero 5xx.*

- **Rolling update:** bump the image tag, `helm upgrade`; `kubectl rollout status deploy/modulith`
  while a curl loop runs → new pods Ready before old terminate, zero downtime.
- **Self-healing:** `kubectl delete pod <modulith>` → rescheduled; the Service drops the not-Ready pod
  so traffic never hits it.
- **Scale:** `kubectl scale deploy/modulith --replicas=5`; optional **HPA** (metrics-server ships with
  k3s).
- **Ops:** `kubectl logs -f` (logs are stdout only); resource limits enforced; `kubectl top pods`.

## 8. Deliberately deferred

Billing (Kill Bill/MariaDB/Kaui — heft + arm64 uncertainty); the two Next.js portals (OIDC redirect
wiring + images); TLS via cert-manager (local runs http, or a self-signed later); GitOps (Argo CD /
Flux) and the companion **Kubernetes Engineering Standards.md** (namespaces/labels/RBAC/StorageClasses/
Helm conventions).

## 9. Files this plan adds

- `deploy/k3s-local/` — `registries.yaml` (template), `infra/{namespace,secret.example,postgres,
  valkey,seaweedfs,keycloak,coredns-custom}.yaml`, `kustomization.yaml`, `README.md`.
- `deploy/helm/smsone/values-local.yaml`.
- `scripts/k3s-*.sh` — kubeconfig fetch/rewrite, build-and-push, one-shot bring-up.
- Docs: this file (indexed in `docs/README.md`), a `LOCAL_ACCESS.md` pointer.

## 10. Risks

- **Keycloak issuer / CoreDNS** — the wiring in §5; guarded by its own gate.
- **VM memory** — three modulith JVMs + Keycloak + Postgres on one VM; size ≥ 8 GB and tune requests.
- **Chart prod-Keycloak vs local dev-Keycloak** — resolved by `keycloak.enabled=false` + the infra
  dev-Keycloak owning the `keycloak` Service + `auth` ingress.
- **arm64** — core images (postgres/keycloak/valkey/seaweedfs + our modulith/gateway) are all arm64;
  the deferred Kill Bill is the one at risk.
