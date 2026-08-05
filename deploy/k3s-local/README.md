# Local Kubernetes (k3s) — run the platform on a cluster on your Mac

Deploy the modulith (×3) + gateway on **k3s** running in an Ubuntu Server VM (UTM, arm64), so you get a
production-shaped Kubernetes environment locally — rolling updates, self-healing, scaling, the works.
The full rationale and phase gates are in [`../../docs/plans/K8S_LOCAL_PLAN.md`](../../docs/plans/K8S_LOCAL_PLAN.md).

**What runs where:** the app comes from the **Helm chart** (`../helm/smsone`) with a
[`values-local.yaml`](../helm/smsone/values-local.yaml) overlay; the **state** (Postgres, Valkey,
SeaweedFS) and a **dev-Keycloak** are plain manifests in [`infra/`](infra/) — the chart is
state-*external*, this layer stands the state up in-cluster.

## Prerequisites

- **VM:** Ubuntu Server arm64 in UTM, ≥ 8 GB RAM / 4 vCPU, reachable from the Mac. Install k3s:
  `curl -sfL https://get.k3s.io | sh -`. No Docker on the VM — k3s ships containerd.
- **Mac:** `docker` (builds the arm64 images), `kubectl`, `helm`, and SSH to the VM
  (default `gopher@192.168.64.5`, key `~/.ssh/smsone_k3s` — override with `VM=` / `SSH_KEY=`).

## Keep the VM's IP stable (recommended)

k3s bakes the VM's IP into the kubeconfig, and you point `/etc/hosts` at it — so a DHCP address that
changes across reboots breaks both (`kubectl` can't connect; the `*.smsone.local` names stop resolving).
Pin it with a static IP on the VM (Ubuntu **netplan**):

```bash
ip -br addr                        # note your interface (UTM arm64: usually enp0s1) + the current IP
# stop cloud-init from re-writing the network on boot, then declare the static address:
echo 'network: {config: disabled}' | sudo tee /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg
sudo tee /etc/netplan/99-static.yaml >/dev/null <<'YAML'
network:
  version: 2
  ethernets:
    enp0s1:                        # <- your interface from `ip -br addr`
      dhcp4: false
      addresses: [192.168.64.5/24] # keep the address you already use
      routes:
        - to: default
          via: 192.168.64.1        # UTM "Shared Network" gateway is .1
      nameservers:
        addresses: [192.168.64.1, 1.1.1.1]
YAML
sudo chmod 600 /etc/netplan/99-static.yaml
sudo netplan apply                 # the SSH session may blip for a second if the IP changed
```

Confirm with `ip -br addr` (still `192.168.64.5`). Now the VM keeps that address across power-offs, so
`~/.kube/smsone-k3s.yaml` and `/etc/hosts` stay valid — nothing to re-fetch on the next boot. If UTM ever
hands that address to something else, pick one outside its DHCP pool and update those two files once.

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

## GitOps with Argo CD

Deploy by **committing to Git**: Argo CD runs in the cluster, watches this repo, and reconciles the
`smsone` namespace to `deploy/helm/smsone` + `values-local.yaml`. It self-heals drift too — a manual
`helm --set` or `kubectl edit` is reverted to what Git says. The full CI/CD picture (and how Jenkins /
Rancher fit): [`../../docs/guides/cicd-gitops-and-cluster.html`](../../docs/guides/cicd-gitops-and-cluster.html).

### Install (one command)

```bash
make k3s-argocd     # install Argo, add a read-only deploy key, apply the Application, print the admin password
```

Idempotent and reproducible from [`argocd/application.yaml`](argocd/application.yaml) (the declarative
Application) + [`../../scripts/k3s-argocd.sh`](../../scripts/k3s-argocd.sh).

### See the UI (from your Mac — the VM is headless)

- **Ingress (persistent, recommended)** — add the host to the Mac's `/etc/hosts` once (all hosts on one
  line), then browse **http://argocd.smsone.local**:
  ```bash
  echo "192.168.64.5  api.smsone.local auth.smsone.local argocd.smsone.local jenkins.smsone.local" | sudo tee -a /etc/hosts
  ```
- **Port-forward (no /etc/hosts)** — run this **on the Mac** (not in the VM's SSH session) and leave it
  open, then browse **http://localhost:8080**:
  ```bash
  KUBECONFIG=~/.kube/smsone-k3s.yaml kubectl -n argocd port-forward svc/argocd-server 8080:80
  ```

Log in as **`admin`**; fetch the initial password (then change it in the UI → User Info):
```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo
```

### Deploy by committing

Change `values-local.yaml` (e.g. `modulith.replicas`), commit, push. Argo applies it within ~3 min — or
force it now:
```bash
kubectl -n argocd annotate application smsone argocd.argoproj.io/refresh=hard --overwrite
```

### Good to know

- **Private repo → read-only deploy key.** `make k3s-argocd` adds a repo deploy key titled `argocd-local
  (read-only)`; the private half lives only in the in-cluster `repo-smsone` secret (never committed). Remove it with:
  ```bash
  gh repo deploy-key list   -R Paul-Ayesiga/enterprise-modulith-template     # find the id
  gh repo deploy-key delete <id> -R Paul-Ayesiga/enterprise-modulith-template
  ```
- **Branch.** The local Application tracks `feat/k3s-local` (where the chart lives today). After that PR
  merges, switch `argocd/application.yaml`'s `targetRevision` to `main` and re-apply.
- **Argo vs Helm.** Once Argo manages the app, stop running `helm upgrade` by hand — change Git instead
  (Argo reverts manual changes anyway).
- **Demo commits.** The `demo(gitops): scale …` commits on the branch were the live scale demo — squash
  them at merge if you like.

### The production loop (a merge deploys itself)

Automated for prod: on every push to `main`, CI's **`gitops-bump`** job records the freshly built image
SHA in [`../helm/smsone/values-prod.yaml`](../helm/smsone/values-prod.yaml) and commits it; a **prod** Argo
Application ([`../argocd/application.yaml`](../argocd/application.yaml), `targetRevision: main`) rolls it out
— zero-downtime, no cluster credentials in CI. Activate it by running Argo on your prod cluster and applying
that Application (the local loop already runs the same way against `values-local.yaml`). Requires the
github-actions bot to be allowed to push to `main`.

## Self-hosted CI with Jenkins

Run CI on your own cluster instead of GitHub-hosted runners (no per-minute bill). `scripts/k3s-jenkins.sh`
installs a Jenkins controller ([`jenkins/jenkins.yaml`](jenkins/jenkins.yaml)); the pipeline is the repo's
[`Jenkinsfile`](../../Jenkinsfile) — it mirrors the GitHub Actions CI (**test → build+push images → GitOps
image-tag bump**) in an on-demand agent pod with a Docker-in-Docker sidecar (so Testcontainers and
`bootBuildImage` get a Docker daemon).

```bash
make k3s-jenkins     # install the controller + print the initial admin password
```

Open **http://jenkins.smsone.local** (or `kubectl -n jenkins port-forward svc/jenkins 8080:8080`), then finish
the one-time setup in the UI:

1. Unlock with the printed password; install the **suggested plugins** + **Kubernetes** + **Pipeline: SCM step**.
2. **Manage Jenkins → Clouds → New cloud → Kubernetes** (in-cluster; Jenkins namespace `jenkins`).
3. **Credentials** — add two: `ghcr` (username + a GHCR PAT with `write:packages`) and `git-push` (an SSH
   private key allowed to push to `main`, for the GitOps bump).
4. **New Item → Pipeline** (or Multibranch) → *Pipeline script from SCM* → this repo → script path `Jenkinsfile`.
   SCM polling picks up pushes within ~5 min (a laptop Jenkins can't receive GitHub webhooks).

**RAM:** the build agent wants ~2 GB; on an 8 GB VM alongside the app + Argo that's tight — give the VM more
memory or use an external agent before relying on it for every push.

**Handing off from GitHub Actions:** CI's expensive jobs (image build + GitOps bump) now run only when the
repo variable `USE_GITHUB_ACTIONS_CD` is `true` (default off), and doc-only changes skip CI entirely — so
GitHub stops billing. Delete `.github/workflows/ci.yml` once you trust Jenkins for everything.

## Teardown

```bash
helm uninstall smsone -n smsone            # the app (if still Helm-managed; skip once Argo owns it)
kubectl delete -f infra/ ; kubectl delete ns smsone   # state + dev-Keycloak (drops PVC data)
```
