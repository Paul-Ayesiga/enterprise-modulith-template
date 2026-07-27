# Architecture

Modular monolith on Spring Boot 4.1 + Spring Modulith 2.1. Modules are Java packages under
`ug.co.smsone`; boundaries are enforced on every build by `ApplicationModules.verify()` and the
generated diagrams below are refreshed on every build.

- **Generated Modulith docs** (C4 PlantUML + per-module canvases with events): [modulith/](modulith/) — regenerate with `./gradlew exportModulithDocs`
- **Event catalog**: [EVENTS.md](EVENTS.md)
- **Decisions**: [adr/](adr/)

## Module map

```mermaid
flowchart TB
    subgraph app["ug.co.smsone (single deployable)"]
        shared["shared (OPEN kernel)\nweb envelope · errors · security\npersistence · cache · idempotency · events"]
        settings["settings\nkey/value config + feature flags"]
        files["files\nFileStorageProvider → S3"]
        scheduler["scheduler\nShedLock cron jobs"]
        analytics["analytics\nAnalyticsEngine → DuckDB"]
    end

    settings --> shared
    files --> shared
    scheduler --> shared
    analytics --> shared

    settings -. "SettingChanged / FeatureFlagChanged\n(DB-backed event registry)" .-> listeners(("future\nlisteners"))

    postgres[("PostgreSQL 18\nsystem of record")]
    valkey[("Valkey 8\nL2 cache + pub/sub")]
    seaweed[("SeaweedFS\nS3 objects")]
    keycloak["Keycloak 26\nOIDC issuer"]
    duckdb[("DuckDB\nembedded, in-process")]
    lgtm["grafana/otel-lgtm\nOTLP sink"]

    app --> postgres
    shared --> valkey
    files --> seaweed
    shared --> keycloak
    analytics --> duckdb
    analytics -- "materialize marts" --> postgres
    app -- "traces·metrics·logs" --> lgtm
```

## Request path (write with idempotency)

```mermaid
sequenceDiagram
    participant C as Client
    participant RF as RequestIdFilter
    participant SEC as Security (JWT)
    participant IF as IdempotencyFilter
    participant CTRL as Controller
    participant DB as Postgres

    C->>RF: PUT /api/v1/... (Idempotency-Key, Bearer)
    RF->>SEC: requestId in MDC + response header
    SEC->>IF: authenticated principal
    IF->>DB: claim (principal, key) [lease takeover]
    IF->>CTRL: cached-body request
    CTRL->>DB: business tx (+ event registry row)
    CTRL-->>IF: envelope response
    IF->>DB: complete (status < 400) / release
    IF-->>C: envelope + X-Request-Id
```

## Cross-cutting contracts

| Contract | Where |
|---|---|
| Envelope (`data XOR errors`, `meta.requestId`) / RFC 9457 on request | `shared/web`, `shared/error` |
| Cursor pagination (`page[size]`/`page[after]`, no totals) | `shared/web` (`Cursors`, `WindowedResult`) |
| Two-level cache (Caffeine L1 + Valkey L2, pub/sub invalidation) | `shared/cache` |
| Idempotency keys (per principal, claim + lease, replay) | `shared/idempotency` |
| Outbox = Modulith DB event registry; Inbox = `EventInbox` | `shared/events`, `event_publication` |
| Distributed locks for cron | `scheduler` (ShedLock JDBC) |
