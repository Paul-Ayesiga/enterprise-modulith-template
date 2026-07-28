# Local Access — URLs, Credentials & Test Helpers

Everything you need to run and poke at the app locally. **All credentials here are dev-only** (they
live in `docker/.env.example` and the Keycloak realm export — never used in staging/prod).

> Ports below are this machine's values from the gitignored `docker/.env` (8080/8081/… were taken).
> On a clean machine the defaults are `8080` (app), `8081` (Keycloak), `5432`, `6379`, `8333`, `8025`, `3000`.

---

## Run it

`make run` starts the app, which **auto-starts the whole Compose stack** for you. `make` (no target)
lists every target:

| Target | What it does |
|---|---|
| `make run` | App + auto-started stack (Ctrl-C stops both) |
| `make seed` | Like `run`, plus seeds the `acme` demo org (owner `david`) at startup |
| `make up` · `down` · `restart` | Infra stack only — e.g. to run the app from your IDE |
| `make ps` · `logs S=keycloak` | Stack status · tail one service's logs |
| `make env` | Create `docker/.env` from the example (one-time) |
| `make pull` | Pre-pull images (Colima pull-storm workaround, one-time) |
| `make token U=jane` | Print a dev access token (default `david`) |
| `make build` · `test` · `openapi` | Build (no tests) · full suite · regenerate the OpenAPI spec |
| `make nuke` | `down -v` — wipe data volumes for a clean slate |

---

## Get a token

```bash
make token                       # david (ADMIN+USER);  make token U=jane for jane (USER)
TOKEN=$(scripts/token.sh)        # capture into a variable
scripts/api.sh GET "/api/v1/settings?page[size]=5"           # auto-fetches a token, encodes page[...]
scripts/api.sh PUT /api/v1/settings/my.key -d '{"value":"hi"}'
API_USER=jane scripts/api.sh PUT /api/v1/feature-flags/x -d '{"enabled":true}'   # -> 403
```

---

## Service URLs & credentials

| Service | URL | Credentials |
|---|---|---|
| **App API** | http://localhost:18080 | Bearer JWT (see above) |
| **Swagger UI** | http://localhost:18080/swagger-ui/index.html | Authorize → paste token, or use the Keycloak OAuth2 flow |
| OpenAPI spec | http://localhost:18080/v3/api-docs · `/v3/api-docs.yaml` | public |
| Actuator health | http://localhost:18080/actuator/health · `/actuator/info` | public |
| **Keycloak** admin console | http://localhost:18081 | `admin` / `admin` (realm **`smsone`**) |
| Keycloak token endpoint | http://localhost:18081/realms/smsone/protocol/openid-connect/token | client `smsone-web` (public, password grant) |
| **Grafana** (traces/metrics/logs) | http://localhost:3000 | anonymous admin (no login); fallback `admin` / `admin` |
| **Mailpit** (dev email inbox) | http://localhost:18025 | none |
| **Postgres** (OLTP) | `localhost:15432` db `modulith` | `modulith` / `modulith` |
| **Valkey** (cache + locks) | `localhost:16379` | none |
| **SeaweedFS** S3 API | http://localhost:18333 | access `smsone` / secret `smsone-secret`, bucket `smsone` (SigV4-signed only — a browser hitting it gets `403 AccessDenied`, which is normal) |
| SeaweedFS **Filer UI** | http://localhost:18888 | none — browse uploaded objects under **`/buckets/smsone/`** |
| SeaweedFS **Master UI** | http://localhost:19333 | none — cluster status / topology |
| SMTP sink (Mailpit) | `localhost:11025` | none |
| OTLP ingest | `localhost:4317` (gRPC) · `localhost:4318` (HTTP) | none |

## Dev users (Keycloak realm `smsone`)

| Username | Password | Realm roles |
|---|---|---|
| `david` | `david123` | `ADMIN`, `USER` |
| `jane` | `jane123` | `USER` |

Keycloak admin: `admin` / `admin`. SeaweedFS creds live in `docker/seaweedfs/s3-config.json`.

---

## API endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `GET` | `/api/v1/settings` | USER | Cursor-paginated list (`page[size]`, `page[after]`) |
| `GET` | `/api/v1/settings/{key}` | USER | Single setting |
| `PUT` | `/api/v1/settings/{key}` | **ADMIN** | Body `{"value","description?"}` (value not blank) |
| `GET` | `/api/v1/feature-flags` | USER | Cursor-paginated list |
| `GET` | `/api/v1/feature-flags/{key}` | USER | Single flag |
| `PUT` | `/api/v1/feature-flags/{key}` | **ADMIN** | Body `{"enabled":bool,"description?"}` |
| `GET` | `/api/v1/notifications` | USER | Current user's in-app notifications (cursor-paginated) |
| `POST` | `/api/v1/notifications/{id}/read` | USER | Mark an in-app notification read |
| `GET` | `/api/v1/me` | USER | Self + roles + active org + provisioning status (allowed while `INVITED`) |
| `GET` | `/api/v1/admin/users` | **ADMIN** | Platform user list (cursor-paginated) |
| `GET` | `/api/v1/permissions` | USER | The fixed permission catalog |
| `POST` | `/api/v1/orgs` | **ADMIN** | Create org + first owner. Body `{"alias","name","ownerEmail","ownerFirstName?","ownerLastName?"}` |
| `GET`/`PATCH` | `/api/v1/orgs/{orgId}` | `org:read` / `org:update` | Read / rename an org |
| `GET`/`POST` | `/api/v1/orgs/{orgId}/members` | `member:read` / `member:invite` | List / invite (provisions identity + temp creds) |
| `PUT` | `/api/v1/orgs/{orgId}/members/{subject}/role` | `member:role:assign` | Reassign role (last-owner protected) |
| `DELETE` | `/api/v1/orgs/{orgId}/members/{subject}` | `member:remove` | Remove member (keeps the Keycloak user) |
| `GET`/`POST`/`PUT`/`DELETE` | `/api/v1/orgs/{orgId}/roles[/{roleId}]` | `role:*` | Custom-role CRUD (system roles immutable) |
| `POST` | `/api/v1/files` | USER | Multipart upload (`file`); key namespaced under `u/<sub>/…` |
| `GET` | `/api/v1/files/{key}` | USER (owner/ADMIN) | 302 → short-lived presigned download URL |
| `DELETE` | `/api/v1/files/{key}` | USER (owner/ADMIN) | Delete an object |
| `POST` | `/api/v1/files/presign` | USER (owner/ADMIN) | Presigned `PUT`/`GET` URL. Body `{"operation","key?","contentType?"}` |
| `GET` | `/api/v1/scheduler/locks` | **ADMIN** | ShedLock rows (clustered-job observability) |
| `GET` | `/api/v1/analytics/reports` | **ADMIN** | Curated report catalog |
| `GET` | `/api/v1/analytics/reports/{code}` | **ADMIN** | Run a report (Postgres → DuckDB → aggregate) |

### Organizations & org-scoped RBAC (no-JIT)
Org endpoints need the token's **active org** to match `{orgId}` — `scripts/token.sh` requests the
`organization` scope so david's token carries it. The Keycloak Admin API (provisioning, org creation)
uses the `smsone-admin` service account; its dev secret is baked in (override `KEYCLOAK_ADMIN_SECRET`
in prod). To seed a demo org `acme` with david as OWNER at startup, run the app with
`ORG_DEV_BOOTSTRAP_ENABLED=true` (needs Keycloak up; idempotent, best-effort).
```bash
# once bootstrapped (or after POST /orgs), find your active org id and drive the RBAC surface:
scripts/api.sh GET /api/v1/me                              # -> data.attributes.activeOrgId
ORG=<activeOrgId>
scripts/api.sh GET  "/api/v1/orgs/$ORG/members"            # owner/admin: 200; unscoped token: 403
scripts/api.sh POST "/api/v1/orgs/$ORG/members" \
  -d '{"email":"newbie@smsone.co.ug","firstName":"New","roleCode":"MEMBER"}'   # invites + emails creds
scripts/api.sh POST "/api/v1/orgs/$ORG/roles" \
  -d '{"code":"AUDITOR","name":"Auditor","permissions":["org:read","member:read"]}'
API_USER=jane scripts/api.sh GET "/api/v1/orgs/$ORG/members"   # 403 (jane isn't a member of the org)
```

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
