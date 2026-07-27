# ADR 0002 — Cursor (keyset) pagination, no offsets, no totals

- **Status:** Accepted · **Date:** 2026-07-27

## Decision
Collections paginate with `page[size]` (≤100) + `page[after]` (opaque base64url keyset cursor);
responses carry `meta.page {size, count, hasMore, nextCursor}` and `links.next`. No
`page[number]`, no `totalRecords`/`totalPages`.

## Why
Offset paging needs COUNT queries and shifts under concurrent writes; keyset scrolling
(Spring Data `Window`/`KeysetScrollPosition` over a stable unique sort, e.g. `createdAt desc,
id desc`) is O(page) at any depth and stable. Decided by the owner at Phase 2 start, superseding
the plan's original offset design.

## Consequences
Every listable entity needs a stable unique sort; cursors encode the sort keys (`shared/web/
Cursors`, typed: Instant/UUID/Long/String) and are invalid across sort changes — clients must
treat them as opaque. Invalid cursors → 422 with `source.parameter: page[after]`.
