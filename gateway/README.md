# Gateway

The platform's reactive edge — a separate Spring Cloud Gateway deployable, built hexagonally. Design:
[../docs/GATEWAY_ARCHITECTURE.md](../docs/GATEWAY_ARCHITECTURE.md); plan + phase status:
[../docs/GATEWAY_PLAN.md](../docs/GATEWAY_PLAN.md); decision: [../docs/adr/0007-api-gateway.md](../docs/adr/0007-api-gateway.md).

## Subprojects

| Module | What it is | Status |
|---|---|---|
| `gateway:core` | Runtime- and platform-agnostic: route/service model, ports (`RouteSource`, `ServiceRegistry`), `GatewayAttributes`, error codes. No Spring Cloud Gateway, no platform imports (enforced by `GatewayCoreArchitectureTest`). | Phase 1 |
| `gateway:app` | The bootable Spring Cloud Gateway runtime: binds `gateway.*` config → the core model → SCG routes; request-id + access-log filters; the error envelope. | Phase 1 |
| `gateway:security` · `platform-adapter` · `admin` · `starter` | Land with their phases (Security, Admin, …) — see the plan. | later |

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
  routes:
    - id: modulith-api
      order: 0
      service-id: modulith
      predicates:
        - kind: PATH        # PATH | HOST | HEADER | METHOD | QUERY
          args: [/api/v1/**]
```

## Phase 1 (shipped)

Config-driven routing to a backend by any predicate kind; a `NO_ROUTE` 404 envelope for no match;
request-id minted / honored / propagated / echoed; the gateway's own `/actuator/health`. Gated by
`RoutingTest` against a real backend stub. The security / traffic / observability / admin capabilities
arrive in later phases (see the plan); backend health-gating lands with load-balancing (Phase 3).
