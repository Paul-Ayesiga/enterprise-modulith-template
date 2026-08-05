# Gateway

The platform's reactive edge — a separate Spring Cloud Gateway deployable, built hexagonally. Design:
[../docs/plans/GATEWAY_ARCHITECTURE.md](../docs/plans/GATEWAY_ARCHITECTURE.md); plan + phase status:
[../docs/plans/GATEWAY_PLAN.md](../docs/plans/GATEWAY_PLAN.md); decision: [../docs/adr/0007-api-gateway.md](../docs/adr/0007-api-gateway.md).

## Subprojects

| Module | What it is | Status |
|---|---|---|
| `gateway:core` | Runtime- and platform-agnostic: route/service model, ports (`RouteSource`, `ServiceRegistry`), `GatewayAttributes`, error codes. No Spring Cloud Gateway, no platform imports (enforced by `GatewayCoreArchitectureTest`). | Phase 1 |
| `gateway:app` | The bootable Spring Cloud Gateway runtime: binds `gateway.*` config → the core model → SCG routes; request-id + access-log filters; the error envelope. | Phase 1 |
| `gateway:security` | Reactive OAuth2 resource server (JWT/JWKS), the coarse-authZ `EdgeAuthorizationFilter`, CORS, security headers. Component-scanned into the app. | Phase 2a |
| `gateway:platform-adapter` | This template's platform adapters — `ModulithApiKeyIntrospector` (resolves `X-Api-Key` via the modulith's introspection endpoint). Active only when configured. | Phase 2b |
| `gateway:admin` · `starter` | Land with their phases (Admin, …) — see the plan. | later |

## Run it (dev)

The modulith runs on `:8080` (`./gradlew bootRun`, infra from `docker/docker-compose.yml`). The gateway
runs the same way, fronting it:

```
./gradlew :gateway:app:bootRun            # gateway on :8090 → modulith on :8080
# override: GATEWAY_PORT=8090 MODULITH_URI=http://localhost:8080
curl -i http://localhost:8090/api/v1/...  # proxied to the modulith, X-Request-Id stamped
```

## Configure routes (`gateway.*`)

Routes and services are configuration, not code — the `RouteSource`/`ServiceRegistry` ports read them.

```yaml
gateway:
  services:
    - id: modulith
      uri: http://localhost:8080
      health-path: /actuator/health
  # Reusable policies (attach with policy-ref); products group routes for the catalog / dev portal.
  policies:
    edge-api: { auth: { authenticated: true }, traffic: { rate-limited: true, response-timeout-ms: 15000 } }
  products:
    identity: { name: Identity & Access, description: The current user, permissions, and operator admin. }
  routes:
    - id: identity-api
      order: 10
      service-id: modulith
      product: identity        # groups this route under the 'identity' product in the catalog
      policy-ref: edge-api     # inherit the shared policy (a route's own inline auth/traffic still wins)
      predicates:
        - kind: PATH           # PATH | HOST | HEADER | METHOD | QUERY
          args: [/api/v1/me, /api/v1/permissions, /api/v1/admin/**]   # multiple patterns = OR
```

The shipped config carves `/api/v1/**` into eight products (identity, organizations, configuration,
files & documents, notifications, audit, operations, and a `platform` catch-all) — a request takes the
most specific route by `order`, and `/actuator/gatewaycatalog` groups them for the developer portal.

## Edge security (`gateway.security.*`, Phase 2a)

The gateway validates a bearer JWT against the IdP's JWKS (no platform call) and enforces each route's
**coarse** policy; services keep their own fine-grained checks (ADR 0007 §8).

```yaml
gateway:
  security:
    jwk-set-uri: http://localhost:8081/realms/smsone/protocol/openid-connect/certs
    tenant-claim: tenant            # which JWT claim carries the tenant
    cors:
      allowed-origins: [https://app.example.com]   # empty = no cross-origin
  routes:
    - id: modulith-api
      service-id: modulith
      predicates: [{kind: PATH, args: [/api/v1/**]}]
      auth:
        authenticated: true                         # a valid token is required (else 401)
        scopes: [api]                               # every listed scope must be present (else 403)
        tenant-path-template: /api/v1/orgs/{tenant}/**   # path tenant must equal the token's (else 403)
```

On success the gateway stamps `X-Auth-Subject` and `X-Tenant-Id` downstream and forwards the bearer.

An `X-Api-Key` is authenticated the same way, via the platform adapter — the gateway calls the
modulith's introspection endpoint (the modulith verifies the key with its own store) and drives the
same route policy. Configure the seam (both secrets must match):

```yaml
gateway:
  platform:
    introspection:
      uri: http://localhost:8080/internal/gateway/api-key/introspect
      secret: dev-gateway-secret        # == the modulith's app.gateway.introspection-secret
```

Unset the `uri` to turn API-key auth off — the adapter is not created and the bearer path is unaffected.

Trusted service-to-service callers present an `X-Internal-Token` (constant-time matched) and resolve to
a service principal with configured scopes that bypasses tenant enforcement:

```yaml
gateway:
  security:
    internal-tokens:
      - name: reporting
        token: ${REPORTING_TOKEN}      # a strong shared secret
        scopes: [reports]
```

## Phase 1 (shipped)

Config-driven routing to a backend by any predicate kind; a `NO_ROUTE` 404 envelope for no match;
request-id minted / honored / propagated / echoed; the gateway's own `/actuator/health`. Gated by
`RoutingTest` against a real backend stub. Backend health-gating lands with load-balancing (Phase 3).
