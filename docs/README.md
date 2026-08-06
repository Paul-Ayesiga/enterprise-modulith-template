# Documentation

How the docs are arranged: **current, load-bearing reference lives at this top level — one document, one
job.** Rendered visual explainers live in [guides/](guides/), plans and architecture north-stars in
[plans/](plans/), decisions in [adr/](adr/), build-regenerated artifacts in [modulith/](modulith/) and
[openapi/](openapi/), point-in-time audits in [reviews/](reviews/), and superseded plans move to
[archive/](archive/) with a header saying what replaced them. The authoritative "you changed X, so update
Y" duties table is [../AGENTS.md](../AGENTS.md) §13 — this page only says what each document is.

## Start here (new to the repo, in this order)

1. [../README.md](../README.md) — what this is, the stack, quickstart.
2. [ARCHITECTURE.md](ARCHITECTURE.md) — module map, request path, cross-cutting contracts.
3. [../AGENTS.md](../AGENTS.md) — the engineering standard. §1 is the rules that fail the build; §14 is the review checklist.
4. [LOCAL_ACCESS.md](LOCAL_ACCESS.md) — run it, get a token, drive every endpoint.

## Reference — what the system is

| Document | One job |
|---|---|
| [SRS.md](SRS.md) | Requirements with stable IDs, external interfaces, NFRs, and traceability to the tests that verify them |
| [DATA_MODEL.md](DATA_MODEL.md) | Every table, column, index and invariant; migration history; lifecycle and retention |
| [erd/schema.dbml](erd/schema.dbml) | The full schema as dbdiagram.io DBML — 52 tables, real FKs + tenant/soft-ref relationships, per-module groups; generated from the applied migrations, import to visualize |
| [EVENTS.md](EVENTS.md) | Domain event catalog: publishers, payloads, consumers, idempotency keys |
| [SLO.md](SLO.md) | Service-level objectives: the promise, the exact measuring expression, the error budget, the discipline when it runs out |
| [runbooks/](runbooks/) | One runbook per provisioned alert — what fired, first five minutes, diagnosis, remediation — plus the restore/DR drill and [ci-jenkins.md](runbooks/ci-jenkins.md) (self-hosted Jenkins: sign-in, CI credentials, recovering the node after a build exhausts it) |
| [PRODUCTION.md](PRODUCTION.md) | The road to a cluster: CI/images, Helm chart, prod Keycloak, backups, the dev-vs-prod knob table |
| [PORTING.md](PORTING.md) | Porting the template to another RDBMS (Oracle/SQL Server/MySQL): the five Postgres seams + per-vendor recipe |
| [openapi/](openapi/) | Generated OpenAPI 3.1 spec (`./gradlew exportOpenApi`) — Postman imports it natively |
| [modulith/](modulith/) | Generated C4/PlantUML diagrams and per-module canvases (`./gradlew exportModulithDocs`) |

## Guides — [guides/](guides/) · open in a browser

Rendered HTML explainers, each with diagrams and a light/dark toggle.

| Guide | One job |
|---|---|
| [system-diagram.html](guides/system-diagram.html) | The system architecture — the module map and request path as interactive diagrams |
| [api-guide.html](guides/api-guide.html) | The API surface — endpoints, auth, the response envelope, and worked examples |
| [k8s-local-walkthrough.html](guides/k8s-local-walkthrough.html) | Newcomer-friendly walkthrough of the local k3s deployment — the vocabulary, topology, the traced request path, the issuer/CoreDNS trick, the two bugs, and the zero-downtime proof |
| [cicd-gitops-and-cluster.html](guides/cicd-gitops-and-cluster.html) | CI/CD, GitOps & cluster ops — what Jenkins / Argo CD / Rancher are and where each fits, the target pipeline, and where things live on the cluster |
| [mcp-guide.html](guides/mcp-guide.html) | The MCP agent surface — how AI agents connect, the 35-tool catalog, the dispatch pipeline, guards, and the async pattern, with diagrams |

## Decisions — [adr/](adr/)

| ADR | Decision |
|---|---|
| [0001](adr/0001-platform-baseline.md) | Platform baseline: Boot 4.1 / Modulith 2.1 / Java 21, Gradle, Valkey, SeaweedFS |
| [0002](adr/0002-cursor-pagination.md) | Cursor (keyset) pagination — no offsets, no totals |
| [0003](adr/0003-testcontainers-only.md) | Real containers for every infra-touching test — no H2, no fakes |
| [0004](adr/0004-two-level-cache.md) | Two-level cache: Caffeine L1 + Valkey L2 with pub/sub invalidation |
| [0005](adr/0005-idempotency-keys.md) | Per-principal HTTP idempotency keys, claim-first with lease |
| [0006](adr/0006-embedded-duckdb.md) | Embedded DuckDB for analytics, UTC marts, exact decimals |
| [0007](adr/0007-api-gateway.md) | API gateway: a reactive Spring Cloud Gateway as a hexagonal platform product |
| [0008](adr/0008-geolocation-storage.md) | Geolocation stored as numeric lat/lng behind a spatial port (`GeoSearch`), PostGIS deferred |
| [0009](adr/0009-mcp-server.md) | MCP server: the agent surface as a second protocol surface over the same module ports — stateless streamable HTTP, API-key auth |

## Plans — [plans/](plans/)

The living plans and architecture north-stars. Plan first, then code (AGENTS §13).

| Document | One job |
|---|---|
| [IMPLEMENTATION_PLAN.md](plans/IMPLEMENTATION_PLAN.md) | The living plan: pinned versions, contracts, phases — and the Boot-4/Testcontainers-2 gotchas (§10) |
| [NEXT_MODULES_PLAN.md](plans/NEXT_MODULES_PLAN.md) | The active build-out plan: localization → search → document → exchange → observability, plus the carried backlog |
| [PLATFORM_EXPANSION_PLAN.md](plans/PLATFORM_EXPANSION_PLAN.md) | The platform-expansion slices — the enterprise features layered onto the core |
| [TENANT_LIFECYCLE.md](plans/TENANT_LIFECYCLE.md) | Org/tenant lifecycle — trial, active, pause/suspend, and the transitions between them |
| [GEOLOCATION_PLAN.md](plans/GEOLOCATION_PLAN.md) | The `geo` module: numeric lat/lng behind a spatial port, per-record-type capture policy, a pluggable geocoder SPI, and the phased rollout |
| [GATEWAY_ARCHITECTURE.md](plans/GATEWAY_ARCHITECTURE.md) | The gateway north star: a stateless reactive Spring Cloud Gateway, hexagonal ports & adapters, the request pipeline, coarse-vs-fine authZ |
| [GATEWAY_PLAN.md](plans/GATEWAY_PLAN.md) | The phased gateway build (Core → Security → Traffic → Observability → Extensibility → Admin → Enterprise), each with its gate |
| [K8S_LOCAL_PLAN.md](plans/K8S_LOCAL_PLAN.md) | Deploying the platform on local k3s (Ubuntu/UTM, arm64): ctr-imported images, in-cluster state, the Keycloak-issuer/CoreDNS crux, and the "production feeling" demo — **SHIPPED**; operator guide in `../deploy/k3s-local/README.md` |
| [MCP_PLAN.md](plans/MCP_PLAN.md) | The MCP agent surface: locked decisions, the tool catalog, phases 0–6 (shipped) and the flagged OAuth phase |
| [PERF_PLAN.md](plans/PERF_PLAN.md) | The load-test plan (k6, in `perf/`): scenarios, thresholds, and what the numbers mean |
| [reusable-data-exchange-platform-guidelines.md](plans/reusable-data-exchange-platform-guidelines.md) | Principles for the exchange (import/export) platform — the spec the `exchange` module implements |

## Where work stands

| Document | One job |
|---|---|
| [CHECKLIST.md](CHECKLIST.md) | Gate ledger — a box is ticked only when the deliverable's acceptance gate passed |
| [COMPLETED_MODULES.md](COMPLETED_MODULES.md) | Per-module inventory of what is built, tested and gated |

## Reviews — [reviews/](reviews/)

Dated, point-in-time code audits. Actionable findings get fixed or graduate into the backlog; the
reports themselves stay as records.

## Archive — [archive/](archive/)

Superseded plans and the original roadmap, kept because the decision history explains the code. Each
file's header states what superseded it.
