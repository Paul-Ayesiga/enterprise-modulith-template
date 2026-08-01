# Local Access — URLs, Credentials & Test Helpers

Everything you need to run and poke at the app locally. **All credentials here are dev-only** (they
live in `docker/.env.example` and the Keycloak realm export — never used in staging/prod).

> Ports below are the **shipped defaults**. This machine's gitignored `docker/.env` overrides them
> (the defaults were taken here): app `18080`, Keycloak `18081`, Postgres `15432`, Valkey `16379`,
> SeaweedFS S3 `18333` / Filer `18888` / Master `19333`, Mailpit UI `18025` / SMTP `11025`.

---

## Run it

`make run` starts the app, which **auto-starts the whole Compose stack** for you. `make` (no target)
lists every target:

| Target | What it does |
|---|---|
| `make run` | App + auto-started stack (Ctrl-C stops both); provisions the platform admin `paul` |
| `make seed` | Like `run`, plus seeds the `acme` demo org (owner `paul`) at startup |
| `make up` · `down` · `restart` | Infra stack only — e.g. to run the app from your IDE |
| `make ps` · `logs S=keycloak` | Stack status · tail one service's logs |
| `make env` | Create `docker/.env` from the example (one-time) |
| `make pull` | Pre-pull images (Colima pull-storm workaround, one-time) |
| `make token` | Print a dev access token for `paul` (`U=<user>` for one you added) |
| `make build` · `test` · `openapi` | Build (no tests) · full suite · regenerate the OpenAPI spec |
| `make nuke` | `down -v` — wipe data volumes for a clean slate |
| `make clean` | Stop Gradle daemons and drop `build/`, `.gradle/`, `data/` (keeps downloaded deps and images) |
| `make fresh` | **The one command after a bad state**: clean + nuke + up + wait for Keycloak + run |

---

## Get a token

```bash
make token                       # paul — the platform super-admin (platform-superadmin + USER)
TOKEN=$(scripts/token.sh)        # capture into a variable
scripts/api.sh GET "/api/v1/settings?page[size]=5"           # auto-fetches a token, encodes page[...]
scripts/api.sh PUT /api/v1/settings/my.key -d '{"value":"hi"}'
scripts/api.sh GET /api/v1/admin/users                       # platform user listing (platform-support)
```

> **If the app starts serving 500s on every endpoint**, its schema is probably gone: Flyway runs only at
> startup, so an app left running across a `make nuke` reconnects to an empty database and stays there.
> `/actuator/health` still reports UP, because the datasource check proves the connection, not the schema.
> `make fresh` is the fix.

Only the super-admin ships in the realm. To exercise a 403, add a plain `USER` in the Keycloak admin
console and call with `API_USER=<name> API_PASSWORD=<pass> scripts/api.sh …`.

---

## Service URLs & credentials

| Service | URL | Credentials |
|---|---|---|
| **App API** | http://localhost:8080 | Bearer JWT (see above) |
| **Swagger UI** | http://localhost:8080/swagger-ui/index.html | Authorize → paste token, or use the Keycloak OAuth2 flow |
| OpenAPI spec | http://localhost:8080/v3/api-docs · `/v3/api-docs.yaml` | public |
| Actuator health | http://localhost:8080/actuator/health · `/actuator/info` | public |
| **Keycloak** admin console | http://localhost:8081 | `admin` / `admin` (realm **`smsone`**) |
| Keycloak token endpoint | http://localhost:8081/realms/smsone/protocol/openid-connect/token | client `smsone-web` (public, password grant) |
| **Grafana** (traces/metrics/logs) | http://localhost:3000 | anonymous admin (no login); fallback `admin` / `admin` |
| **Mailpit** (dev email inbox) | http://localhost:8025 | none |
| **Postgres** (OLTP) | `localhost:5432` db `modulith` | `modulith` / `modulith` |
| **Valkey** (cache + locks) | `localhost:6379` | none |
| **SeaweedFS** S3 API | http://localhost:8333 | access `smsone` / secret `smsone-secret`, bucket `smsone` (SigV4-signed only — a browser hitting it gets `403 AccessDenied`, which is normal) |
| SeaweedFS **Filer UI** | http://localhost:8888 | none — browse uploaded objects under **`/buckets/smsone/`** |
| SeaweedFS **Master UI** | http://localhost:9333 | none — cluster status / topology |
| SMTP sink (Mailpit) | `localhost:1025` | none |
| OTLP ingest | `localhost:4317` (gRPC) · `localhost:4318` (HTTP) | none |

## Dev users (Keycloak realm `smsone`)

| Username | Password | Email | Realm roles |
|---|---|---|---|
| `paul` | `Paul123` | `ayesigapo@gmail.com` | `platform-superadmin`, `USER` — top of the ladder |

### The platform tier ladder

Platform access is three **hierarchical** realm roles. A route names the *minimum* tier that may pass
it, and every higher tier satisfies it automatically (one `RoleHierarchy` bean, applied to both
`@PreAuthorize` and the `CurrentUser.hasRole(…)` checks in code):

```
platform-superadmin  →  platform-admin  →  platform-support
```

| Tier | Reaches | Examples |
|---|---|---|
| `platform-support` | read-only platform views | `/admin/users`, `/audit`, `/scheduler/locks`, `/analytics/reports`, reading any user's file |
| `platform-admin` | + changes platform behaviour | `PUT /settings/{key}`, `PUT /feature-flags/{key}`, `POST /orgs`, suspend/reactivate, deleting any user's file |
| `platform-superadmin` | + escalation | impersonating a platform-role holder |

A platform role grants **no** organization permission — the two axes are disjoint by design, and
reaching tenant data as an operator is impersonation's job (audited), not a role's. See
[Impersonation](#impersonation) below for how to actually do it.

Because access is **no-JIT**, a
valid JWT alone is not access: the subject also needs a local `app_user` row. `make run`/`make seed`
set `IDENTITY_DEV_BOOTSTRAP_ENABLED=true`, which projects that row for the realm account matching
`app.identity.dev-bootstrap.email` (default `ayesigapo@gmail.com`) — it never creates the Keycloak
account, and the property is `false` by default so nothing is seeded outside those targets.

```bash
scripts/api.sh GET "/api/v1/admin/users?page[size]=10"   # -> 200 with paul's own row
```

Changing the realm export only takes effect on a fresh Keycloak container (`--import-realm` skips an
existing realm): run `make restart`, or `make nuke && make up` to also wipe the app DB.

Keycloak admin: `admin` / `admin`. SeaweedFS creds live in `docker/seaweedfs/s3-config.json`.

---

## API endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/api/v1/settings` | USER | Cursor-paginated list (`page[size]`, `page[after]`) |
| `GET` | `/api/v1/settings/{key}` | USER | Single setting |
| `PUT` | `/api/v1/settings/{key}` | **platform-admin** | Body `{"value","description?"}` (value not blank) |
| `GET` | `/api/v1/feature-flags` | USER | Cursor-paginated list |
| `GET` | `/api/v1/feature-flags/{key}` | USER | Single flag |
| `PUT` | `/api/v1/feature-flags/{key}` | **platform-admin** | Body `{"enabled":bool,"description?"}` |
| `GET` | `/api/v1/translations` | USER | Cursor-paginated; `?locale=` filters one locale |
| `GET` | `/api/v1/translations/{locale}/{key}` | USER | Single translation (locale is a BCP-47 tag, any casing) |
| `PUT` | `/api/v1/translations/{locale}/{key}` | **platform-admin** | Upsert. Body `{"value"}`; evicts the locale's cached bundle cluster-wide |
| `DELETE` | `/api/v1/translations/{locale}/{key}` | **platform-admin** | Resolution falls back down the chain afterwards |
| `GET` | `/api/v1/notifications` | USER | Current user's in-app notifications (cursor-paginated) |
| `POST` | `/api/v1/notifications/{id}/read` | USER | Mark an in-app notification read |
| `GET` | `/api/v1/me` | USER | Self + roles + active org + provisioning status (allowed while `INVITED`) |
| `GET` | `/api/v1/admin/users` | **platform-support** | Platform user list (cursor-paginated) |
| `POST` | `/api/v1/admin/impersonations` | **platform-support** | Open a session. Body `{"targetSubject","orgId?","reason","mode?","ttl?"}`. `mode=WRITE` needs **platform-admin** |
| `GET` | `/api/v1/admin/impersonations` | **platform-support** | Your own sessions, live + history (cursor-paginated). `?actor=<sub>` for someone else's needs **platform-admin** |
| `DELETE` | `/api/v1/admin/impersonations/{id}` | opener, or **platform-admin** | End a session now — the next request carrying its id is refused |
| `GET` | `/api/v1/permissions` | USER | The fixed permission catalog |
| `POST` | `/api/v1/orgs` | **platform-admin** | Create org + first owner. Body `{"alias","name","ownerEmail","ownerFirstName?","ownerLastName?"}` |
| `GET`/`PATCH` | `/api/v1/orgs/{orgId}` | `org:read` / `org:update` | Read / rename an org |
| `POST` | `/api/v1/orgs/{orgId}/suspend` | **platform-admin** | Suspend a tenant — cuts every member's access (zero permissions resolve while suspended) |
| `POST` | `/api/v1/orgs/{orgId}/reactivate` | **platform-admin** | Lift a suspension |
| `GET`/`POST` | `/api/v1/orgs/{orgId}/members` | `member:read` / `member:invite` | List / invite (provisions identity + temp creds) |
| `PUT` | `/api/v1/orgs/{orgId}/members/{subject}/role` | `member:role:assign` | Reassign role (last-owner protected) |
| `DELETE` | `/api/v1/orgs/{orgId}/members/{subject}` | `member:remove` | Remove member (keeps the Keycloak user) |
| `GET`/`POST`/`PUT`/`DELETE` | `/api/v1/orgs/{orgId}/roles[/{roleId}]` | `role:*` | Custom-role CRUD (system roles immutable) |
| `POST` | `/api/v1/files` | USER | Multipart upload (`file`); key namespaced under `u/<sub>/…` |
| `GET` | `/api/v1/files/{key}` | USER (owner) or **platform-support** | 302 → short-lived presigned download URL |
| `DELETE` | `/api/v1/files/{key}` | USER (owner) or **platform-admin** | Delete an object |
| `POST` | `/api/v1/files/presign` | USER (owner) or **platform-support** | Presigned `PUT`/`GET` URL. Body `{"operation","key?","contentType?"}` |
| `GET` | `/api/v1/audit` | **platform-support** | Audit trail (all orgs); filter `action`/`from`/`to`, cursor-paginated |
| `GET` | `/api/v1/orgs/{orgId}/audit` | `audit:read` | That org's audit trail (org admins) |
| `GET`/`POST`/`PUT`/`DELETE` | `/api/v1/orgs/{orgId}/webhooks[/{id}]` | `webhook:manage` | Outbound webhook subscriptions (secret shown once) |
| `GET` | `/api/v1/orgs/{orgId}/webhooks/{id}/deliveries` | `webhook:manage` | Delivery log for a subscription |
| `GET` | `/api/v1/scheduler/locks` | **platform-support** | ShedLock rows (clustered-job observability) |
| `GET` | `/api/v1/analytics/reports` | **platform-support** | Curated report catalog |
| `GET` | `/api/v1/analytics/reports/{code}` | **platform-support** | Run a report (Postgres → DuckDB → aggregate) |

### Organizations & org-scoped RBAC (no-JIT)
Org endpoints need the token's **active org** to match `{orgId}` — `scripts/token.sh` requests the
`organization` scope so paul's token carries it. The Keycloak Admin API (provisioning, org creation)
uses the `smsone-admin` service account; its dev secret is baked in (override `KEYCLOAK_ADMIN_SECRET`
in prod). To seed a demo org `acme` with paul as OWNER at startup, run `make seed` (or set
`ORG_DEV_BOOTSTRAP_ENABLED=true`; needs Keycloak up, idempotent, best-effort).
```bash
# once bootstrapped (or after POST /orgs), find your active org id and drive the RBAC surface:
scripts/api.sh GET /api/v1/me                              # -> data.attributes.activeOrgId
ORG=<activeOrgId>
scripts/api.sh GET  "/api/v1/orgs/$ORG/members"            # owner: 200; unscoped token: 403
# A fresh org has exactly ONE role, OWNER (V16 stopped seeding ADMIN/MEMBER). Mint the role you want
# to hand out BEFORE inviting into it — an unknown roleCode is a 404, not a silent default.
scripts/api.sh POST "/api/v1/orgs/$ORG/roles" \
  -d '{"code":"AUDITOR","name":"Auditor","permissions":["org:read","member:read"]}'
scripts/api.sh POST "/api/v1/orgs/$ORG/members" \
  -d '{"email":"newbie@smsone.co.ug","firstName":"New","roleCode":"AUDITOR"}'   # invites + emails creds
API_USER=other API_PASSWORD=... scripts/api.sh GET "/api/v1/orgs/$ORG/members"   # 403 (not a member)
```

### Impersonation

The one sanctioned way for an operator to see a tenant's data, and the only one that leaves a trail.
You open a session, then repeat your **own** requests with `X-Impersonate: <sessionId>` — same token,
same operator, one extra header.

```bash
# 1. Open a session against a member of that org. reason is required and must be >= 8 characters.
SESSION=$(scripts/api.sh POST /api/v1/admin/impersonations \
  -d '{"targetSubject":"<their-keycloak-sub>","orgId":"'"$ORG"'","reason":"ticket 4711 refund missing"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])')

# 2. Same call, twice. Without the header you are the operator; with it you are the member.
scripts/api.sh GET "/api/v1/orgs/$ORG/members"                                    # 403 — no org permission
scripts/api.sh GET "/api/v1/orgs/$ORG/members" -H "X-Impersonate: $SESSION"       # 200 — their permissions
scripts/api.sh GET /api/v1/admin/users        -H "X-Impersonate: $SESSION"        # 403 — no platform role

# 3. Look at what it recorded, then end it.
scripts/api.sh GET "/api/v1/audit?action=platform.impersonation_started"
scripts/api.sh DELETE "/api/v1/admin/impersonations/$SESSION"                     # 204; next request is refused
```

Worth knowing before you try it:

| | |
|---|---|
| Default mode is **read-only** | only `GET`/`HEAD`/`OPTIONS` pass. Add `"mode":"WRITE"` (needs **platform-admin**) for anything else |
| The session expires on its own | default 15 min, hard cap 30. `"ttl":"PT5M"` asks for less; asking for more is a 422, not a silent clamp |
| The tier does not travel into the session | `/api/v1/admin/**` 403s while impersonating — including the endpoint that opens sessions |
| The id is not a bearer token | it only works for the operator it was issued to. Copying it out of a log gains nobody anything |
| Everything you do is attributed to **you** | `audit_log.actor` is your subject, `on_behalf_of` is theirs, `impersonation_id` links back to the reason you typed |
| Re-opening against the same person supersedes | the old session ends and the supersede is audited; there is never more than one live pair |
| Turn it off entirely | `IMPERSONATION_ENABLED=false` — the routes and the header both **403**, naming the switch (never silently ignored) |

### Response envelope (every response)
```json
{ "data": { "id": "…", "type": "setting", "attributes": { … } },
  "meta": { "requestId": "01K…", "timestamp": "…", "apiVersion": "1" },
  "links": { "self": "…" } }
```
- **`meta.requestId`** is on every response (+ the `X-Request-Id` header) — quote it in bug reports.
- Errors use `{"errors":[{id,status,code,title,detail,source}], "meta":{requestId}}` — **never** a stack trace.
- **Cursor pagination**: `meta.page {size,count,hasMore,nextCursor}` + `links.next` (no total counts).
- Add `-H "Accept: application/problem+json"` to any error to get the RFC 9457 variant instead.
