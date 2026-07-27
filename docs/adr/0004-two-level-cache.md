# ADR 0004 — Two-level cache: Caffeine L1 + Valkey L2

- **Status:** Accepted · **Date:** 2026-07-27

## Decision
`TwoLevelCacheManager` (@Primary) pairs an in-process Caffeine cache (short TTL, bounded) with a
shared Valkey `RedisCacheManager` (longer TTL, Jackson-3 JSON values). Cross-instance L1
invalidation rides a Valkey pub/sub topic with a per-JVM instance id (nodes skip their own
broadcasts). L2 failures degrade to L1-only; Lettuce command/connect timeouts are pinned to 2s —
the 60s default would turn a Valkey outage into a stall, not a degrade.

## Why
Read-heavy config (settings values, feature flags) wants sub-µs hits (L1) *and* cross-instance
consistency after writes (L2 + invalidation) without making the cache a single point of failure.

## Consequences
Writers evict both levels and broadcast; a FAILED L2 evict deliberately skips the broadcast
(peers refilling from the stale L2 entry would resurrect the old value — everyone stays equally
stale until TTL). Non-String keys can't ride the text topic: peers get a full-cache clear
instead. Cache String-keyed, JSON-serializable values.
