# ADR 0001 — Platform baseline

- **Status:** Accepted
- **Date:** 2026-07-27

## Context

We are building a reusable enterprise template (modular monolith, future microservice extraction).
The platform anchor must not ship EOL software and must stay license-clean for redistribution.

## Decision

| Decision | Choice |
|---|---|
| Platform | **Spring Boot 4.1.0** + **Spring Modulith 2.1.0** (Framework 7, Jakarta EE 11, Hibernate 7) |
| Language | **Java 21** (LTS), toolchain-provisioned via foojay resolver |
| Build | **Gradle 9.6.1 Kotlin DSL**, version catalog, Gradle-native `platform()` BOM imports — no `io.spring.dependency-management` |
| Group | **`ug.co.smsone`** |
| Cache / locks | **Valkey 8** (BSD) — wire-compatible Redis drop-in, avoids AGPL/SSPL exposure |
| Object storage | **SeaweedFS** (Apache-2.0) via **AWS S3 v2 SDK** — same code for local, self-hosted, and managed S3 |
| Analytics | **DuckDB** embedded (in-process OLAP, not a container) |
| API contract | Unified JSON:API-inspired *lite* envelope, `application/json`, `meta.requestId` everywhere, zero stack-trace leakage |
| Config | YAML for all Spring config; the only `.properties` file is `gradle.properties` (build-daemon tuning) |
| Deployment | Docker Compose now → Kubernetes later; all infra coordinates as `${ENV:default}` properties |

## Consequences

- Boot 3.5 hit OSS EOL 2026-06-30 — anchoring on 4.1 avoids shipping an already-EOL template, at the
  cost of early-adopter risk (Hibernate 7, SpringDoc 3.0.x, Lettuce 7). Mitigation: pinned versions
  were verified against Maven Central on 2026-07-27; Boot-3-only libraries (Resilience4j, Bucket4j)
  are deferred behind smoke tests.
- Single Gradle module; Modulith modules are packages. Physical module splitting is a deliberate
  later step, enforced meanwhile by `ApplicationModules.verify()` + ArchUnit.
- Rejected: Lombok (records + constructor injection instead), Zalando problem-spring-web (Spring-native
  RFC 9457), Togglz (DB-backed flags), MinIO SDK (S3 v2 SDK), JSON:API libraries (hand-rolled lite envelope).

Full rationale and alternatives: [../IMPLEMENTATION_PLAN.md](../IMPLEMENTATION_PLAN.md).
