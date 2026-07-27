# Local Access — URLs, Credentials & Test Helpers

Everything you need to run and poke at the app locally. **All credentials here are dev-only** (they
live in `docker/.env.example` and the Keycloak realm export — never used in staging/prod).

> Ports below are this machine's values from the gitignored `docker/.env` (8080/8081/… were taken).
> On a clean machine the defaults are `8080` (app), `8081` (Keycloak), `5432`, `6379`, `8333`, `8025`, `3000`.

---

## Start / stop the app

```bash
# start (brings up the whole Compose stack + the app on $SERVER_PORT)
set -a; . docker/.env; set +a
SERVER_PORT=$SERVER_PORT \
KEYCLOAK_ISSUER_URI=http://localhost:$KEYCLOAK_PORT/realms/smsone \
S3_ENDPOINT=http://localhost:$S3_PORT \
SMTP_HOST=localhost SMTP_PORT=$SMTP_PORT \
./gradlew bootRun

# stop the app: Ctrl-C  (Compose stops with it — lifecycle is start-and-stop)
# stop infra manually if needed:
docker compose -f docker/docker-compose.yml --env-file docker/.env down
```

---

## Get a token (the simple command)

```bash
scripts/token.sh                 # david  (ADMIN + USER)
scripts/token.sh jane            # jane   (USER only)
TOKEN=$(scripts/token.sh)        # capture into a variable

# one-off authenticated call (auto-fetches a token, encodes page[...] for you):
scripts/api.sh GET "/api/v1/settings?page[size]=5"
scripts/api.sh PUT /api/v1/settings/my.key -d '{"value":"hi","description":"demo"}'
API_USER=jane scripts/api.sh PUT /api/v1/feature-flags/x -d '{"enabled":true}'   # -> 403
```

Raw curl equivalent (note the **URL-encoded brackets** `%5B%5D` — Tomcat rejects raw `[ ]`):

```bash
TOKEN=$(curl -s -X POST http://localhost:18081/realms/smsone/protocol/openid-connect/token \
  -d grant_type=password -d client_id=smsone-web -d username=david -d password=david123 \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')

curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:18080/api/v1/settings?page%5Bsize%5D=5"
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
| **SeaweedFS** S3 API | http://localhost:18333 | access `smsone` / secret `smsone-secret`, bucket `smsone` |
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

`files`, `scheduler`, and `analytics` modules have no REST surface yet (event/job/query-driven).

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

### Things worth trying
```bash
scripts/api.sh GET "/api/v1/settings?page[size]=1"          # then follow meta.page.nextCursor
scripts/api.sh PUT /api/v1/settings/bad -d '{"value":""}'   # 422 multi-error envelope
API_USER=jane scripts/api.sh PUT /api/v1/feature-flags/z -d '{"enabled":true}'   # 403 (no ADMIN)
curl -s -H "Authorization: Bearer $(scripts/token.sh)" \
  -H "Accept: application/problem+json" http://localhost:18080/api/v1/settings/nope   # RFC 9457

# notifications: toggling a flag emails admins (Mailpit UI :18025) AND creates an in-app message
scripts/api.sh PUT /api/v1/feature-flags/beta -d '{"enabled":true}'
scripts/api.sh GET /api/v1/notifications        # david sees the in-app notification
# then mark one read:  scripts/api.sh POST /api/v1/notifications/<id>/read
```
