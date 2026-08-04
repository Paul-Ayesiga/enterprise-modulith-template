# Portal E2E (Playwright)

Login-gate smoke for both portals: any unauthenticated visit — home or deep link — must land on
the Keycloak login form. This is the invariant the edge middleware + OIDC/PKCE flow exists for.

Needs a running stack (`make up`, the modulith, and both portals). Then:

    cd e2e
    npm ci
    npx playwright install --with-deps chromium
    npm test                       # both portals
    npx playwright test --project=developer-portal

Point at another environment: `PORTAL_URL=https://portal.example.com ADMIN_PORTAL_URL=... npm test`.

Deliberately not in the PR path (it starts nothing itself) — run post-deploy, or wire into the CI
deploy job once the stub is promoted (docs/PRODUCTION.md). Grow it honestly: the next tests to add
are the logged-in journeys (token in hand → try-console call, webhook create → delivery row), which
need a seeded test user via `scripts/token.sh`-style credentials in CI secrets.
