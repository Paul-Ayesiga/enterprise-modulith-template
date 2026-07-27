# ADR 0003 — Real containers for every infra-touching test

- **Status:** Accepted · **Date:** 2026-07-27

## Decision
Integration tests run against real infrastructure via Testcontainers: Postgres 18
(`@ServiceConnection` singleton), Keycloak 26 (plain TC2 `GenericContainer` — the dasniko module
is TC1-only and clashes with Testcontainers 2's renamed artifacts), SeaweedFS 4.40, Valkey 8
(`@ServiceConnection(name = "redis")`). No H2, no embedded substitutes, no mocked repositories.

## Why
Owner directive; and this template's value is *verified* behavior: Hibernate 7 against real
Postgres, real JWKS validation, real S3 semantics ("never trust S3 parity"), real Redis protocol.

## Consequences
Suites need Docker; the singleton Postgres container amortizes cost. On VM-based Docker
(Colima/Docker Desktop) `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` is required
(defaulted in the build). Pre-pull images before container-heavy runs — image-pull storms have
crashed constrained Docker VMs. Pure web-contract tests may stay MockMvc slices.
