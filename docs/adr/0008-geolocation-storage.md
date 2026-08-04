# ADR 0008 — Geolocation stored as numeric lat/lng behind a spatial port, not PostGIS

- **Status:** Accepted · **Date:** 2026-08-05

## Decision
The `geo` module stores coordinates as `latitude`/`longitude` `numeric(9,6)` — not PostGIS
`geography`/`geometry`. Nearby and bounding-box search is a bbox prefilter over the
`(org_id, latitude, longitude)` B-tree index, narrowed by the Haversine formula. **All** coordinate
SQL is confined to the `GeoSearch` port (`HaversineGeoSearch`); no DTO, service, controller, or other
module ever references a coordinate as anything but two decimals. Reverse geocoding is a separate
pluggable `Geocoder` SPI (coordinates are the source of truth; a place is regenerable enrichment).

## Why
For capture-and-attach plus nearby/bbox queries — the Phase 1 scope — numeric + Haversine is exact and
scales to hundreds of thousands of rows with no new infrastructure, and it stays **portable SQL** (geo
does not become an RDBMS-port seam). PostGIS earns its complexity only for true GIS — geofences,
polygon/boundary queries, spatial joins, GiST at scale — which Phase 1 does not need. The trigger to
adopt it was also concrete: PostGIS has **no official arm64 image**, so on the Apple-Silicon dev
machine it would mean an emulated or third-party image swapped into every environment's DB — cost with
no present benefit. Introduce complexity when it delivers value, not before.

## Consequences
A later move to PostGIS is a **persistence-only** change, not a rewrite: enable the extension, add a
`geography` column backfilled from lat/lng, add a `PostgisGeoSearch` (`ST_DWithin`/GiST) and switch the
bean, then contract. At that point geo becomes a documented Postgres seam in `docs/PORTING.md`. Until
then, distance-ordered `near` results are deferred (bbox filtering covers "in this area"); very large
datasets are the signal to execute the migration path (see `docs/GEOLOCATION_PLAN.md` §6).
