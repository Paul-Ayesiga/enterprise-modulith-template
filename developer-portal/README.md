# SMSOne Developer Portal

A minimal **Next.js (App Router, TypeScript)** developer portal that renders the API-product catalog and
OpenAPI document the gateway publishes. It is one of the Phase-7 "developer portal" deliverables, kept as
a **separate front-end app** — the gateway ships the *backend* for it (the `gatewaycatalog` and
`gatewayopenapi` management endpoints); this renders it.

## What it shows

- Every **API product** (`gateway.products.<id>`) with its routes.
- Each route's **path(s)**, **lifecycle** (published / deprecated / retired), and whether it needs a **token**.
- Links to the raw **OpenAPI** document and the gateway **route table**.

It reads the gateway live on each request and stores nothing.

## Run

The portal reads the gateway's **admin (management) port** (default `:9090`).

```bash
# In the repo root — bring up the modulith + infra, then the gateway (two terminals):
make run          # modulith + infra
make gateway      # the edge (:8090, admin :9090)

# Here:
cp .env.local.example .env.local   # optional — override GATEWAY_ADMIN_URL
npm install
npm run dev                        # http://localhost:3001
```

If the gateway isn't running, the portal renders a friendly "could not reach the gateway" message instead of crashing.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `GATEWAY_ADMIN_URL` | `http://localhost:9090` | The gateway's management base URL (serves the catalog + OpenAPI). |

## Notes

- Fetches happen **server-side** (React Server Components), so there is no CORS or token to negotiate in dev.
- In production the admin port sits on a private network; a real portal would consume a curated *public*
  catalog rather than the raw management endpoint, and add docs, interactive try-out, and subscription
  self-service on top of this base.
