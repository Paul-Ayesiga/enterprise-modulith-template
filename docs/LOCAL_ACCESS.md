# Local Access — URLs, Credentials & How to Drive It

Two ways to run the platform on this machine, then the shared API reference. **All credentials here are
dev-only** (from `docker/.env.example` and the Keycloak realm export — never used in staging/prod).

- **[Part 1 · Docker Compose](#part-1--docker-compose-the-dev-inner-loop)** — the fast inner loop: the app
  from Gradle + its stack in Docker, on this machine's `2xxxx` ports. Best for coding.
- **[Part 2 · Kubernetes (k3s)](#part-2--kubernetes-k3s-on-the-utm-vm)** — the production-shaped cluster in
  the UTM VM, reached at `*.smsone.local`. Best for the "production feeling" (rolling updates, GitOps, CI).
- **[Part 3 · Driving the API](#part-3--driving-the-api-either-environment)** — tokens, endpoints, RBAC,
  impersonation — identical against either.

---

# Part 1 · Docker Compose (the dev inner loop)

> The URLs here use **this machine's** local ports — its gitignored `docker/.env` maps every service to a
> **`2xxxx` prefix** to dodge conflicts. The shipped defaults (a clean machine) drop the `2`: app `8080`,
> gateway `8090` / admin `9090`, Keycloak `8081`, Postgres `5432`, Valkey `6379`, … — see `docker/.env.example`.

## Run it

The system runs as **two processes in two terminals**: the **modulith** (which auto-starts the whole
Compose stack — Postgres, Keycloak, Valkey, …) and the **gateway** (the edge in front of it).

```bash
make env         # first time only — writes docker/.env from the example (tweak ports if any clash)
make run         # terminal A — modulith on :28080 + the whole stack (Ctrl-C stops both); provisions paul
make gateway     # terminal B — the edge on :28090 (admin :29090), proxying :28080
```

Use `make seed` instead of `make run` to also seed the `acme` demo org (owner `paul`). `make` (no target)
lists every target:

| Target | What it does |
|---|---|
| `make run` | Modulith + auto-started stack (Ctrl-C stops both); provisions the platform admin `paul` |
| `make seed` | Like `run`, plus seeds the `acme` demo org (owner `paul`) + Kill Bill tenant & catalog |
| `make gateway` | The API gateway (`:28090`, admin `:29090`) fronting the modulith — start `make run` first, this in a 2nd terminal |
| `make gateway-build` · `gateway-test` | Build · test the gateway subprojects alone |
| `make multi-demo` | **Docker**: builds + runs the modulith as **3 replicas** behind the gateway — round-robin + zero-downtime. `make multi-token` mints an in-network token; `make multi-down` stops it |
| `make up` · `down` · `restart` | Infra stack only — e.g. to run the modulith from your IDE |
| `make ps` · `logs S=keycloak` | Stack status · tail one service's logs |
| `make token` | Print a dev access token for `paul` (`U=<user>` for one you added) |
| `make build` · `test` · `openapi` | Modulith: build (no tests) · full suite · regenerate the OpenAPI spec |
| `make nuke` | `down -v` — wipe data volumes for a clean slate |
| `make clean` | Stop Gradle daemons and drop `build/`, `.gradle/`, `data/` (keeps downloaded deps and images) |
| `make fresh` | **The one command after a bad state**: clean + nuke + up + wait for Keycloak + run (modulith) |

> **If the app starts serving 500s on every endpoint**, its schema is probably gone: Flyway runs only at
> startup, so an app left running across a `make nuke` reconnects to an empty database and stays there.
> `/actuator/health` still reports UP (the datasource check proves the connection, not the schema). `make fresh` fixes it.

## Service URLs & credentials (Compose)

| Service | URL | Credentials |
|---|---|---|
| **API gateway** — the front door | http://localhost:28090/api/v1/… | Bearer JWT; the edge validates it, applies quotas/tracing, then proxies to the modulith |
| Gateway **admin** (separate port) | http://localhost:29090/actuator/{gatewayroutes,gatewaycatalog,gatewayopenapi,prometheus} | route table · product catalog · OpenAPI · metrics — keep off the public network |
| **App API** — direct (bypasses the edge) | http://localhost:28080 | Bearer JWT (see above) |
| **Swagger UI** | http://localhost:28080/swagger-ui/index.html | Authorize → paste token, or use the Keycloak OAuth2 flow |
| OpenAPI spec | http://localhost:28080/v3/api-docs · `/v3/api-docs.yaml` | public (its default server is the gateway) |
| Actuator health | http://localhost:28080/actuator/health · `/actuator/info` | public |
| **Keycloak** admin console | http://localhost:28081 | `admin` / `admin` (realm **`smsone`**) |
| Keycloak token endpoint | http://localhost:28081/realms/smsone/protocol/openid-connect/token | client `smsone-web` (public, password grant) |
| **Kill Bill** (billing API) | http://localhost:28082 | basic `admin` / `password`; tenant key `smsone` / `smsone-secret` (dev bootstrap creates it when `BILLING_BOOTSTRAP=true`) |
| **Kaui** (Kill Bill admin UI) | http://localhost:29095 | `admin` / `password` |
| **Grafana** (traces/metrics/logs) | http://localhost:23000 | anonymous admin (no login); fallback `admin` / `admin`. The **SMSOne** folder holds two file-provisioned dashboards — see `docker/grafana/README.md` |
| **Mailpit** (dev email inbox) | http://localhost:28025 | none |
| **Postgres** (OLTP) | `localhost:25432` db `modulith` | `modulith` / `modulith` |
| **Valkey** (cache + locks) | `localhost:26379` | none |
| **SeaweedFS** S3 API | http://localhost:28333 | access `smsone` / secret `smsone-secret`, bucket `smsone` (SigV4-signed only — a browser gets `403 AccessDenied`, which is normal) |
| SeaweedFS **Filer UI** | http://localhost:28888 | none — browse objects under **`/buckets/smsone/`** |
| SMTP sink (Mailpit) | `localhost:21025` | none |
| OTLP ingest | `localhost:24317` (gRPC) · `localhost:24318` (HTTP) | none |

Get a token: **`make token`** (or `TOKEN=$(scripts/token.sh)`). Driving the API → **Part 3**.

---

# Part 2 · Kubernetes (k3s on the UTM VM)

The platform also runs on a real k3s cluster in an Ubuntu VM (UTM). Build it with the operator guide
[`../deploy/k3s-local/README.md`](../deploy/k3s-local/README.md); the design is
[`plans/K8S_LOCAL_PLAN.md`](plans/K8S_LOCAL_PLAN.md); a newcomer tour is
[`guides/k8s-local-walkthrough.html`](guides/k8s-local-walkthrough.html).

## One-time setup (per Mac)

```bash
make k3s-kubeconfig                        # copies the VM's kubeconfig -> ~/.kube/smsone-k3s.yaml
export KUBECONFIG=~/.kube/smsone-k3s.yaml  # add this to your shell profile so every terminal sees the cluster

# /etc/hosts — one line, persists across reboots (the browser/curl resolve these names to the VM):
sudo sh -c 'echo "192.168.64.5  api.smsone.local auth.smsone.local argocd.smsone.local jenkins.smsone.local" >> /etc/hosts'
```

## Switching the VM off / on (your daily flow)

- **Off** — just power the VM off in UTM. All data lives on PVCs; there's nothing to stop or clean up.
- **On** — start the VM. k3s is a **systemd service**, so it and *every* Deployment come back on their own.
  Give it **~1–2 minutes**, then confirm from the Mac:
  ```bash
  export KUBECONFIG=~/.kube/smsone-k3s.yaml
  kubectl get pods -A          # wait until smsone / argocd / jenkins pods show Running + READY
  ```
  Rough timings after boot: Postgres/Valkey ~15 s, Keycloak ~30 s, each modulith ~60–90 s; Argo CD then
  reconciles the app automatically. Nothing to redeploy.
- **If `kubectl` can't reach it** — the VM holds **192.168.64.5 statically** (netplan `99-static.yaml`, with
  cloud-init's network management disabled), so the address survives reboots and neither `~/.kube/smsone-k3s.yaml`
  nor `/etc/hosts` should go stale. Give it a minute first — the ingress answers before every pod is ready.
  If the address ever does move, re-run `make k3s-kubeconfig`, update the `/etc/hosts` line, and re-check the
  procedure in the operator README → [*Keep the VM's IP stable*](../deploy/k3s-local/README.md#keep-the-vms-ip-stable-recommended).

## URLs (Kubernetes)

Everything is reached through the VM's Traefik ingress by hostname. No `/etc/hosts`? Use the port-forward
(run it **on the Mac**, leave it open) and browse `localhost` instead.

| Service | URL (with /etc/hosts) | Port-forward alternative |
|---|---|---|
| **API** — the gateway/edge | http://api.smsone.local/api/v1/… | `kubectl -n smsone port-forward svc/gateway 8080:8080` → http://localhost:8080 |
| **Keycloak** — login / tokens | http://auth.smsone.local (admin console `/admin`) | `kubectl -n smsone port-forward svc/keycloak 8081:80` → http://localhost:8081 |
| **Argo CD** — GitOps deploys | http://argocd.smsone.local | `kubectl -n argocd port-forward svc/argocd-server 8080:80` |
| **Jenkins** — self-hosted CI | http://jenkins.smsone.local | `kubectl -n jenkins port-forward svc/jenkins 8080:8080` |

Postgres / Valkey / SeaweedFS have **no ingress** (in-cluster only) — reach them with
`kubectl -n smsone port-forward svc/<postgres|valkey|seaweedfs> <local>:<port>`.

## Credentials (Kubernetes)

| Service | User | Password |
|---|---|---|
| **Keycloak** realm `smsone` (app login / API tokens) | `paul` | `Paul123` |
| **Keycloak** admin console (`auth.smsone.local/admin`) | `admin` | `admin` |
| **Argo CD** | `admin` | `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' \| base64 -d` |
| **Jenkins** (generated on install; there is no setup wizard) | `admin` | `kubectl -n jenkins get secret jenkins-secrets -o jsonpath='{.data.JENKINS_ADMIN_PASSWORD}' \| base64 -d` |
| **Postgres** (in-cluster) | `modulith` | `modulith` |
| **SeaweedFS** S3 (in-cluster) | `smsone` | `smsone-secret` |

## Get a token + call the API (Kubernetes)

```bash
export KUBECONFIG=~/.kube/smsone-k3s.yaml
TOKEN=$(scripts/k3s-token.sh)                                             # paul, minted via auth.smsone.local
curl -H "Authorization: Bearer $TOKEN" http://api.smsone.local/api/v1/me  # -> 200, requestId "gw-…"
```

The endpoints, roles, and response envelope are the same as Compose — see **Part 3** (swap the base URL for
`http://api.smsone.local`).

## Bring the cluster up from scratch (fresh VM)

```bash
make k3s-kubeconfig    # kubeconfig from the VM -> ~/.kube/smsone-k3s.yaml
make k3s-images        # build the app images and load them into the VM's containerd
make k3s-up            # namespace + secret + state + dev-Keycloak + CoreDNS + the app (modulith + gateway)
make k3s-argocd        # optional — Argo CD (GitOps: deploy by committing)
make k3s-jenkins       # optional — self-hosted Jenkins (CI without GitHub-hosted minutes)
make k3s-demo          # optional — the rolling-update / kill / scale zero-downtime demo
```

---

# Part 3 · Driving the API (either environment)

Base URL: **`http://localhost:28090`** (Compose) **or** **`http://api.smsone.local`** (k3s) — the endpoints,
roles, and envelope are identical. `scripts/api.sh` / `scripts/token.sh` default to the Compose ports; for
k3s grab a token with `scripts/k3s-token.sh` and `curl` against `api.smsone.local`.

```bash
scripts/api.sh GET "/api/v1/settings?page[size]=5"      # auto-fetches a token, encodes page[...] (Compose)
scripts/api.sh PUT /api/v1/settings/my.key -d '{"value":"hi"}'
scripts/api.sh GET /api/v1/admin/users                  # platform user listing (platform-support)
```

Only the super-admin ships in the realm. To exercise a 403, add a plain `USER` in the Keycloak admin
console and call with `API_USER=<name> API_PASSWORD=<pass> scripts/api.sh …`.

## Dev users (Keycloak realm `smsone`)

| Username | Password | Email | Realm roles |
|---|---|---|---|
| `paul` | `Paul123` | `ayesigapo@gmail.com` | `platform-superadmin`, `USER` — top of the ladder |

### The platform tier ladder

Platform access is three **hierarchical** realm roles. A route names the *minimum* tier that may pass it,
and every higher tier satisfies it automatically (one `RoleHierarchy` bean, applied to both `@PreAuthorize`
and the `CurrentUser.hasRole(…)` checks):

```
platform-superadmin  →  platform-admin  →  platform-support
```

| Tier | Reaches | Examples |
|---|---|---|
| `platform-support` | read-only platform views | `/admin/users`, `/audit`, `/scheduler/locks`, `/analytics/reports`, reading any user's file |
| `platform-admin` | + changes platform behaviour | `PUT /settings/{key}`, `PUT /feature-flags/{key}`, `POST /orgs`, suspend/reactivate, deleting any user's file |
| `platform-superadmin` | + escalation | impersonating a platform-role holder |

A platform role grants **no** organization permission — the two axes are disjoint by design; reaching
tenant data as an operator is impersonation's job (audited), not a role's. Because access is **no-JIT**, a
valid JWT alone is not access: the subject also needs a local `app_user` row. `make run`/`make seed` set
`IDENTITY_DEV_BOOTSTRAP_ENABLED=true`, which projects that row for the realm account matching
`app.identity.dev-bootstrap.email` (default `ayesigapo@gmail.com`).

## API endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/api/v1/settings` | USER | Cursor-paginated list (`page[size]`, `page[after]`) |
| `GET` | `/api/v1/settings/{key}` | USER | Single setting |
| `PUT` | `/api/v1/settings/{key}` | **platform-admin** | Body `{"value","description?"}` (value not blank) |
| `GET` | `/api/v1/feature-flags` | USER | Cursor-paginated list |
| `PUT` | `/api/v1/feature-flags/{key}` | **platform-admin** | Body `{"enabled":bool,"description?"}` |
| `GET` | `/api/v1/translations` | USER | Cursor-paginated; `?locale=` filters one locale |
| `PUT` | `/api/v1/translations/{locale}/{key}` | **platform-admin** | Upsert; evicts the locale's cached bundle cluster-wide |
| `DELETE` | `/api/v1/translations/{locale}/{key}` | **platform-admin** | Resolution falls back down the chain afterwards |
| `GET` | `/api/v1/notifications` | USER | Current user's in-app notifications (cursor-paginated) |
| `POST` | `/api/v1/notifications/{id}/read` | USER | Mark an in-app notification read |
| `GET` | `/api/v1/me` | USER | Self + roles + active org + provisioning status (allowed while `INVITED`) |
| `GET` | `/api/v1/admin/users` | **platform-support** | Platform user list (cursor-paginated) |
| `POST` | `/api/v1/admin/impersonations` | **platform-support** | Open a session. Body `{"targetSubject","orgId?","reason","mode?","ttl?"}`. `mode=WRITE` needs **platform-admin** |
| `GET` | `/api/v1/admin/impersonations` | **platform-support** | Your own sessions, live + history. `?actor=<sub>` for someone else's needs **platform-admin** |
| `DELETE` | `/api/v1/admin/impersonations/{id}` | opener, or **platform-admin** | End a session now — the next request carrying its id is refused |
| `GET` | `/api/v1/permissions` | USER | The fixed permission catalog |
| `POST` | `/api/v1/orgs` | **platform-admin** | Create org + first owner. Body `{"alias","name","ownerEmail","ownerFirstName?","ownerLastName?"}` |
| `GET`/`PATCH` | `/api/v1/orgs/{orgId}` | `org:read` / `org:update` | Read / rename an org |
| `POST` | `/api/v1/orgs/{orgId}/suspend` · `/reactivate` | **platform-admin** | Suspend a tenant (zero permissions resolve while suspended) / lift it |
| `GET`/`POST` | `/api/v1/orgs/{orgId}/members` | `member:read` / `member:invite` | List / invite (provisions identity + temp creds) |
| `PUT` | `/api/v1/orgs/{orgId}/members/{subject}/role` | `member:role:assign` | Reassign role (last-owner protected) |
| `DELETE` | `/api/v1/orgs/{orgId}/members/{subject}` | `member:remove` | Remove member (keeps the Keycloak user) |
| `GET`/`POST`/`PUT`/`DELETE` | `/api/v1/orgs/{orgId}/roles[/{roleId}]` | `role:*` | Custom-role CRUD (system roles immutable) |
| `POST` | `/api/v1/files` | USER | Multipart upload (`file`); key namespaced under `u/<sub>/…` |
| `GET`/`DELETE` | `/api/v1/files/{key}` | USER (owner) / **platform-support**·**admin** | 302 → presigned download / delete an object |
| `POST` | `/api/v1/files/presign` | USER (owner) or **platform-support** | Presigned `PUT`/`GET` URL |
| `GET`/`POST` | `/api/v1/orgs/{orgId}/documents` | `document:read` / `document:manage` | Org document catalog |
| `GET`/`POST` | `/api/v1/documents` | USER | Personal documents (support may read others', admin delete) |
| `GET` | `/api/v1/orgs/{orgId}/search` · `/api/v1/admin/search` | `org:read` / **platform-support** | Ranked FTS (org-scoped / platform-wide) |
| `GET` | `/api/v1/audit` · `/api/v1/orgs/{orgId}/audit` | **platform-support** / `audit:read` | Audit trail (all orgs / that org); filter `action`/`from`/`to` |
| `GET`/`POST`/`PUT`/`DELETE` | `/api/v1/orgs/{orgId}/webhooks[/{id}]` | `webhook:manage` | Outbound webhook subscriptions (secret shown once); `/{id}/deliveries` = delivery log; `POST …/deliveries/{deliveryId}/redeliver` re-queues a FAILED one (202) |
| `POST`/`GET` | `/api/v1/orgs/{orgId}/geo/stamps` | `geo:capture` / `geo:read` | Attach a location; query by subject or `bbox` (exact coords need `geo:read_precise`) |
| `GET`/`PUT` | `/api/v1/orgs/{orgId}/geo/policies/{subjectType}` | `geo:policy:manage` | Per-record-type capture policy (OFF/OPTIONAL/REQUIRED) |
| `GET` | `/api/v1/scheduler/locks` | **platform-support** | ShedLock rows (clustered-job observability) |
| `GET` | `/api/v1/analytics/reports[/{code}]` | **platform-support** | Report catalog / run a report (Postgres → DuckDB → aggregate) |

### Organizations & org-scoped RBAC (no-JIT)

Org endpoints need the token's **active org** to match `{orgId}` — `scripts/token.sh` (and
`scripts/k3s-token.sh`) request the `organization` scope so paul's token carries it. Seed a demo org `acme`
with paul as OWNER via `make seed` (Compose) or `POST /orgs`.

```bash
scripts/api.sh GET /api/v1/me                              # -> data.attributes.activeOrgId
ORG=<activeOrgId>
scripts/api.sh GET  "/api/v1/orgs/$ORG/members"            # owner: 200; unscoped token: 403
# A fresh org has exactly ONE role, OWNER. Mint the role you want to hand out BEFORE inviting into it —
# an unknown roleCode is a 404, not a silent default.
scripts/api.sh POST "/api/v1/orgs/$ORG/roles" \
  -d '{"code":"AUDITOR","name":"Auditor","permissions":["org:read","member:read"]}'
scripts/api.sh POST "/api/v1/orgs/$ORG/members" \
  -d '{"email":"newbie@smsone.co.ug","firstName":"New","roleCode":"AUDITOR"}'   # invites + emails creds
```

### Impersonation

The one sanctioned way for an operator to see a tenant's data, and the only one that leaves a trail. You
open a session, then repeat your **own** requests with `X-Impersonate: <sessionId>` — same token, same
operator, one extra header.

```bash
# 1. Open a session against a member of that org. reason is required and must be >= 8 characters.
SESSION=$(scripts/api.sh POST /api/v1/admin/impersonations \
  -d '{"targetSubject":"<their-keycloak-sub>","orgId":"'"$ORG"'","reason":"ticket 4711 refund missing"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')

# 2. Same call, twice. Without the header you are the operator; with it you are the member.
scripts/api.sh GET "/api/v1/orgs/$ORG/members"                                # 403 — no org permission
scripts/api.sh GET "/api/v1/orgs/$ORG/members" -H "X-Impersonate: $SESSION"   # 200 — their permissions
scripts/api.sh GET /api/v1/admin/users        -H "X-Impersonate: $SESSION"    # 403 — no platform role

# 3. Look at what it recorded, then end it.
scripts/api.sh GET "/api/v1/audit?action=platform.impersonation_started"
scripts/api.sh DELETE "/api/v1/admin/impersonations/$SESSION"                 # 204; next request is refused
```

| | |
|---|---|
| Default mode is **read-only** | only `GET`/`HEAD`/`OPTIONS` pass. Add `"mode":"WRITE"` (needs **platform-admin**) for anything else |
| The session expires on its own | default 15 min, hard cap 30. `"ttl":"PT5M"` asks for less; asking for more is a 422 |
| The tier does not travel into the session | `/api/v1/admin/**` 403s while impersonating — including the endpoint that opens sessions |
| The id is not a bearer token | it only works for the operator it was issued to |
| Everything is attributed to **you** | `audit_log.actor` is your subject, `on_behalf_of` is theirs, `impersonation_id` links the reason |
| Turn it off entirely | `IMPERSONATION_ENABLED=false` — the routes and the header both **403**, naming the switch |

### Response envelope (every response)

```json
{ "data": { "id": "…", "type": "setting", "attributes": { … } },
  "meta": { "requestId": "01K…", "timestamp": "…", "apiVersion": "1" },
  "links": { "self": "…" } }
```

- **`meta.requestId`** is on every response (+ the `X-Request-Id` header) — quote it in bug reports.
- Errors use `{"errors":[{id,status,code,title,detail,source}], "meta":{requestId}}` — **never** a stack trace.
- **Cursor pagination**: `meta.page {size,count,hasMore,nextCursor}` + `links.next` (no total counts).

## Agents (MCP)

The platform speaks the Model Context Protocol at `POST /mcp` (via the gateway:
`http://localhost:28090/mcp` — the production-faithful path and the `.mcp.json` default; direct to
the modulith: `http://localhost:28080/mcp` when the gateway isn't running). Auth is an **org API key** — mint one with
`POST /api/v1/orgs/{orgId}/api-keys` (permissions capped to what you hold), then either header works:
`X-Api-Key: sk_…` or `Authorization: Bearer sk_…`.

- **Claude Code**: the repo ships `.mcp.json` — `export SMSONE_API_KEY=sk_…` and the `smsone-local`
  server appears with the tools your key allows (`tools/list` is permission-filtered).
- **Smoke test** (JSON-RPC by hand):

```bash
curl -s http://localhost:28080/mcp \
  -H "Content-Type: application/json" -H "Accept: application/json" \
  -H "X-Api-Key: $SMSONE_API_KEY" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"whoami","arguments":{}}}'
```

- 35 tools across organization, subscription/usage, webhooks, support, documents, exchange and
  search; every result carries the `requestId`; writes are refused during maintenance windows and
  paused subscriptions. Full catalog + diagrams: [guides/mcp-guide.html](guides/mcp-guide.html).
