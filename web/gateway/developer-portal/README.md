# SMSOne Developer Portal

A **Next.js (App Router, TypeScript)** developer portal over the SMSOne gateway. It reads the gateway
live and stores nothing (except the login session cookie on the credentials page).

## What it does

| Page | What |
|---|---|
| **`/` APIs** | The API-product catalog — every product and route, with paths, lifecycle, access, and a copy-as-curl / Try shortcut. |
| **`/reference`** | The full OpenAPI (every operation, parameters, responses), grouped by area and searchable. |
| **`/try`** | Interactive request runner — method, path, paste a token, send; proxied server-side (no CORS). |
| **`/products/[id]`** | Per-product detail with each route's edge policy (rate-limit, timeout, body cap, cache). |
| **`/credentials`** | Sign in with Keycloak (OIDC + PKCE) and manage your org's API keys — create, reveal-once, rotate, revoke. |
| **`/changelog` · `/support`** | Curated changelog and an FAQ + resources. |

## Run

The gateway must be up (`make gateway`), and for `/reference` + `/credentials` the modulith + Keycloak too
(`make run`, or `make seed` to also get the `acme` org so key management has an org to target).

```bash
npm install
npm run dev            # http://localhost:3001
```

If the gateway isn't reachable, pages render a friendly message instead of crashing.

## Configuration (env)

Defaults target this machine's local `2xxxx` ports (a clean machine drops the `2`).

| Env var | Default | Meaning |
|---|---|---|
| `GATEWAY_ADMIN_URL` | `http://localhost:29090` | Gateway management base — the catalog + OpenAPI (route map). |
| `GATEWAY_API_URL` | `http://localhost:28090` | Gateway **front door** — used by Try it, curl examples, and the credentials API calls. |
| `MODULITH_DOCS_URL` | `http://localhost:28080` | The app's own base — full OpenAPI (`/v3/api-docs`) + Swagger UI + health. |
| `KEYCLOAK_ISSUER` | `http://localhost:28081/realms/smsone` | OIDC issuer for login. |
| `KEYCLOAK_CLIENT_ID` | `smsone-web` | Public client used with PKCE (its `http://localhost:*` redirects already cover the portal). |
| `KEYCLOAK_SCOPE` | `openid profile email organization` | The `organization` scope carries the active-org claim key management needs. |
| `PORTAL_URL` | `http://localhost:3001` | This app's own URL, for the OIDC `redirect_uri`. |

## Notes

- Reads happen **server-side** (RSC), so there is no CORS or token in the browser for the read pages.
- Login uses **Authorization Code + PKCE** against the public client; the access token is kept in an
  **httpOnly** cookie and only ever read server-side. Logout is local (clears the portal session); the
  Keycloak SSO session persists, so signing back in is a silent redirect.
- The portal session lasts as long as the access token (~5 min by default) — re-login is a one-click SSO
  redirect. Token refresh is a natural next enhancement (a middleware refresh with the refresh token).
