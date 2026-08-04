# web/ — all browser frontends

The grouped home for every web app, organized by the product each app is a face of. Not to be
confused with the top-level [`gateway/`](../gateway) — that is the JVM edge service itself;
`web/gateway/` is that product's **user interfaces**.

```
web/
├── gateway/                  the edge product's own UIs
│   ├── developer-portal/     :3001 — API consumers: catalog, try-it console, usage, webhooks
│   └── admin-portal/         :3002 — operators: live route table, consumers, rate limits
├── tenant-portal/            (planned) an organization's self-service UI — members, billing, security policy
└── platform/                 (planned) platform back-office beyond the gateway — tenants, plans, support, compliance
```

Each app is a self-contained Next.js project: its own `package.json`, lockfile, `Dockerfile`, and
port. CI builds the existing two from a matrix (`.github/workflows/ci.yml` → `portal-build`,
working directory `web/gateway/<app>`), and the Helm chart deploys each as its own image.

Adding a new app: create its directory in the right group (a new gateway-facing UI goes under
`web/gateway/`; a platform-facing one at this level) with its own lockfile + `Dockerfile` (copy an
existing portal's), pick the next port, add it to the `portal-build` matrix and the
`publish-images` loop in `.github/workflows/ci.yml`, and give it a deployment in
`deploy/helm/smsone` if it ships. Every app authenticates the same way — Keycloak OIDC + PKCE
behind an edge-middleware gate with httpOnly tokens — and a new one should too.
