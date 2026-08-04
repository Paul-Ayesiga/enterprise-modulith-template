# web/ — all browser frontends

The grouped home for every web app, the same way [`gateway/`](../gateway) groups the edge. Each
portal is a self-contained Next.js app: its own `package.json`, lockfile, `Dockerfile`, and port.
CI builds them from a matrix over this directory (`.github/workflows/ci.yml` → `portal-build`),
and the Helm chart deploys each as its own image.

| App | Port | Who it serves |
|---|---|---|
| [`developer-portal/`](developer-portal) | 3001 | API consumers: catalog, try-it console, usage, webhooks, getting started |
| [`admin-portal/`](admin-portal) | 3002 | Platform operators: live gateway route table, consumers, rate limits |

Planned additions live here too, one directory each, same shape:

- `tenant-portal/` — an organization's own self-service UI (members, billing, security policy).
- `platform/` — the platform back-office beyond the gateway (tenants, plans, support, compliance).

Adding a new app: create the directory with its own lockfile + `Dockerfile` (copy an existing
portal's), pick the next port, add the directory name to the `portal-build` matrix and the
`publish-images` loop in `.github/workflows/ci.yml`, and give it a deployment in
`deploy/helm/smsone` if it ships. Both portals authenticate the same way — Keycloak OIDC + PKCE
behind an edge-middleware gate with httpOnly tokens — and a new app should too.
