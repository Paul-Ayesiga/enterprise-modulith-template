# ADR 0005 — Per-principal HTTP idempotency keys, claim-first with lease

- **Status:** Accepted · **Date:** 2026-07-27

## Decision
POST/PUT/PATCH under `/api/**` with an `Idempotency-Key` header get exactly-once semantics:
the key row is claimed *before* the handler runs (PK = principal + key), duplicates replay the
stored response (`Idempotency-Replayed: true`), payload mismatch on a used key → 409. In-progress
claims carry a takeover lease (PT5M) so a crashed instance never wedges a key. Only outcomes with
status < 400 are stored; errors stay retryable. Bodies are capped (256KiB) before buffering.

## Why
Safe client retries are an enterprise API baseline. Per-principal scoping is non-negotiable:
adversarial review proved a global namespace lets one user replay another's stored response —
skipping `@PreAuthorize` — and squat keys to deny writes.

## Consequences
The filter is ordered after the security chain (unauthenticated requests never claim). Replays
serve the original body verbatim — including the original `meta.requestId` — while the transport
`X-Request-Id` header stays fresh. Keys expire via the scheduler (`app.idempotency.retention`).
