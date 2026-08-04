# admin-portal

The **control plane** for the SMSOne gateway — a small Next.js app that reads the gateway's admin
endpoints and manages routes live. Separate from the [`developer-portal`](../developer-portal) (which is
the read-only, public-facing API catalog): this one is for **operators**, talks to the gateway's **admin
port**, and can **mutate** the live route table.

## What it does

- **Overview** — route / service / product counts and edge health at a glance.
- **Routes** — the live table (id, order, service, paths, access, lifecycle), sorted by priority.
- **Register a route** — POST to `gatewayroutes`; the edge picks it up immediately, no restart.
- **Delete a route** — two-click confirm, then `DELETE gatewayroutes/{id}`.
- **Services** — the backends the routes target, with route counts.
- Links out to **Grafana** and the raw `gatewayroutes` JSON.

## Run

The gateway must be up (`make gateway`, admin on `:29090`). Then:

```bash
cd web/admin-portal
npm install
npm run dev          # http://localhost:3002
```

## Config (env)

| Var | Default | Meaning |
|---|---|---|
| `GATEWAY_ADMIN_URL` | `http://localhost:29090` | The gateway's management/admin base URL |
| `GRAFANA_URL` | `http://localhost:23000` | Grafana link in the header |

## Security & scope

- This app talks to the gateway's **admin port only** — keep that port (and this app) **off the public
  network**, exactly as the gateway's own docs say.
- The `gatewayroutes` write operation creates **open** routes (no token) with **default** traffic policy.
  For an authenticated, rate-limited, product-grouped route, add it to
  `gateway/app/src/main/resources/application.yml` instead — this UI is for quick, ad-hoc routing.
