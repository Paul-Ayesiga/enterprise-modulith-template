# Documentation

How the docs are arranged: **current, load-bearing documents live at this level — one document, one
job.** Decisions live in [adr/](adr/), artifacts the build regenerates live in [modulith/](modulith/)
and [openapi/](openapi/), point-in-time audits live in [reviews/](reviews/), and finished plans move
to [archive/](archive/) with a header saying what superseded them. The authoritative "you changed X,
so update Y" duties table is [../AGENTS.md](../AGENTS.md) §13 — this page only says what each
document is.

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
| [EVENTS.md](EVENTS.md) | Domain event catalog: publishers, payloads, consumers, idempotency keys |
| [SLO.md](SLO.md) | Service-level objectives: the promise, the exact measuring expression, the error budget, the discipline when it runs out |
| [runbooks/](runbooks/) | One runbook per provisioned alert — what fired, first five minutes, diagnosis, remediation |
| [openapi/](openapi/) | Generated OpenAPI 3.1 spec (`./gradlew exportOpenApi`) — Postman imports it natively |
| [modulith/](modulith/) | Generated C4/PlantUML diagrams and per-module canvases (`./gradlew exportModulithDocs`) |

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

## Project management — where work stands

| Document | One job |
|---|---|
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | The living plan: pinned versions, contracts, phases — and the Boot-4/Testcontainers-2 gotchas (§10) |
| [NEXT_MODULES_PLAN.md](NEXT_MODULES_PLAN.md) | The active build-out plan: localization → search → document → exchange → observability, plus the carried backlog |
| [GATEWAY_ARCHITECTURE.md](GATEWAY_ARCHITECTURE.md) | The gateway north star: a stateless reactive Spring Cloud Gateway, hexagonal ports & adapters, the request pipeline, coarse-vs-fine authZ |
| [GATEWAY_PLAN.md](GATEWAY_PLAN.md) | The phased gateway build (Core → Security → Traffic → Observability → Extensibility → Admin → Enterprise), each with its gate |
| [reusable-data-exchange-platform-guidelines.md](reusable-data-exchange-platform-guidelines.md) | Principles for the exchange (import/export) platform — the spec the `exchange` module implements |
| [CHECKLIST.md](CHECKLIST.md) | Gate ledger — a box is ticked only when the deliverable's acceptance gate passed |
| [COMPLETED_MODULES.md](COMPLETED_MODULES.md) | Per-module inventory of what is built, tested and gated |

## Reviews — [reviews/](reviews/)

Dated, point-in-time code audits. Actionable findings get fixed or graduate into the backlog; the
reports themselves stay as records.

## Archive — [archive/](archive/)

Superseded plans and the original roadmap, kept because the decision history explains the code. Each
file's header states what superseded it.
