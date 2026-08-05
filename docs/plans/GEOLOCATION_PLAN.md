# Geolocation module — plan

## 1. What and why

Capture **geolocation** for transactions and recordings where the location is part of the record's
meaning — field inspections, asset registrations, service delivery, incident reports — and let an
operator **configure per record-type whether a location is off, optional, or required**. The driving
use case is government / enterprise operations that need location-based data for accuracy and
contextual relevance, including **offline / air-gapped** deployments.

This is distinct from the `audit` module's "where". Audit records the *request origin* (client IP) as
an immutable forensic trail owned by the platform. Geolocation records a *precise, business-meaningful
position* attached to a specific record, captured from the device, queryable, and governed by tenant
policy. Different owner, lifecycle, privacy posture — so it is its own module.

## 2. Decisions (locked)

- **Storage: numeric now, PostGIS later — spatial ops behind a port.** Phase 1 stores `latitude` /
  `longitude` as `numeric(9,6)` (≈0.11 m); nearby / bounding-box search is a bbox prefilter + the
  Haversine formula over B-tree indexes. Portable SQL — no PostGIS, no image swap, no arm64 issue —
  and it scales to hundreds of thousands of rows. **All spatial operations sit behind a `GeoSearch`
  port**, and no domain/DTO type exposes a spatial type, so a later move to PostGIS
  (`geography`/`ST_DWithin`/GiST) is a **persistence-layer swap only** — zero service or business-logic
  change. Migration path in §6. (ADR 0008 records this; the decision followed the discovery that
  PostGIS has no official arm64 image, and the principle of adding complexity only when it earns its
  keep.)
- **Reverse geocoding: a pluggable `Geocoder` SPI**, no provider hard-coded. Coordinates are the
  **source of truth**; a human-readable place is *derived enrichment*, always regenerable. Provider is
  chosen per deployment: `DISABLED` (default) → `OFFLINE` (bundled admin boundaries) → self-hosted
  `NOMINATIM` → `EXTERNAL` (Google/Mapbox/HERE/Azure Maps) → `CUSTOM` (deployment supplies a bean).
  Resolution is **asynchronous** and **cached by rounded coordinates**. Coordinates never leave the
  deployment unless an external provider is explicitly enabled.
- **Precise reads are privileged.** Reading exact coordinates is a distinct permission from capturing
  them; lower-privileged reads get a coarsened location.

## 3. Placement

A new first-class module **`ug.co.smsone.geo`** ("Geolocation"), plus a thin cross-cutting *write* port
in the kernel — mirroring the existing `shared.audit.AuditLog` → `audit.internal.AuditLogImpl` +
`audit.internal.AuditController` shape, so any module attaches a location the same way it writes an
audit entry, with zero package coupling to `geo`.

```
shared/geo/                     kernel (OPEN) — the attach port + DTO value types everyone may use
  GeoStamps.java                port:  attach / requirePolicy / find / coarsen
  GeoFix.java                   value: lat, lng, accuracyM, altitudeM, source, capturedAt, capturedBy, consentRef
  GeoStamp.java                 value: stored stamp = id + fix + resolved place
  CaptureMode.java              enum:  OFF | OPTIONAL | REQUIRED
  GeoSource.java                enum:  DEVICE_GPS | NETWORK | MANUAL | IP

geo/                            the module (CLOSED; reached only through the port above + its own REST)
  package-info.java             @ApplicationModule(displayName = "Geolocation")
  Geocoder.java                 SPI (published so a deployment can implement CUSTOM)
  ResolvedPlace.java            value: countryCode, admin1, locality, formattedAddress, placeId, provider
  GeoStampRecorded.java         event record (carries occurredAt)
  GeoPolicyChanged.java         event record
  internal/
    GeoStampsImpl.java          implements shared.geo.GeoStamps
    GeoStampEntity / GeoCapturePolicy / GeoPlace   (JPA entities)
    *Repository, GeoQueryService, GeoPolicyService
    GeoSearch.java (port) + HaversineGeoSearch.java   spatial seam (Phase 1 impl; PostgisGeoSearch later)
    GeoController / GeoPolicyController              (REST /api/v1/…)
    geocoder/  DisabledGeocoder, OfflineGeocoder, NominatimGeocoder, ExternalGeocoder, GeocoderSelector
    ReverseGeocodeWorker        @ApplicationModuleListener on GeoStampRecorded (idempotent via EventInbox)
    GeoIp.java (port) + adapter (optional IP fallback off the gateway's EdgeClientIp)
```

Why the write port lives in `shared` (not `geo`): attaching a location is cross-cutting, exactly like
auditing. Business modules depend on `shared.geo.GeoStamps` — never on the `geo` package — so
`ApplicationModules.verify()` stays green and there is no dependency cycle. Query, config,
reverse-geocoding and REST all live behind `geo/internal`, like `audit`.

## 4. Boundaries and dependency direction

- `geo` depends only on `shared` (`web`, `persistence`, `events`, `audit.AuditLog`,
  `compliance.LegalHolds`, `retention.RetentionPurges`, `http.SafeOutboundUrl`, `security.CurrentUser`,
  `i18n.Messages`). It depends on **no business module**.
- Business modules that stamp a location depend on `shared.geo.GeoStamps` only.
- The `Geocoder` SPI is in `geo`'s public API (not `internal`) so a deployment can register a `CUSTOM`
  implementation without importing internals.

## 5. Data model (migration `V47__geolocation.sql`)

House rules throughout: app-generated `id uuid`, `org_id uuid not null`, `timestamptz`, `BaseEntity`
audit columns, soft-delete via partial indexes, **no cross-module FK** (subject is a soft ref).

**`geo_stamp`** — the polymorphic attach:

| column | type | note |
|---|---|---|
| id | uuid pk | app-generated |
| org_id | uuid not null | tenant (Keycloak org) |
| subject_type | varchar(64) not null | e.g. `exchange_job` — soft ref, no FK |
| subject_id | varchar(64) not null | soft ref |
| latitude | numeric(9,6) not null | ≈0.11 m precision |
| longitude | numeric(9,6) not null | |
| accuracy_m | numeric | reported GPS accuracy |
| altitude_m | numeric null | |
| source | varchar(16) not null | GeoSource |
| captured_by | varchar(64) | token subject (soft ref) |
| captured_at | timestamptz not null | device fix time |
| consent_ref | varchar(64) null | consent record, when required |
| country_code / admin1 / locality / formatted_address / place_id / geocoder_provider | resolved place | nullable, filled async |
| *(BaseEntity)* created_at/by, updated_at/by, version | | |
| deleted_at | timestamptz null | soft-delete |

Indexes: `(org_id, subject_type, subject_id) where deleted_at is null`;
`(org_id, captured_at) where deleted_at is null`; `(org_id, latitude, longitude) where deleted_at is
null` (bbox prefilter); `(deleted_at) where deleted_at is not null` (purge).

**`geo_capture_policy`** — the "configure which forms need geo" table: `(org_id, subject_type)` unique
(partial, `where deleted_at is null`) → `mode`, `min_accuracy_m`, `allowed_sources`,
`max_fix_age_seconds`, `retention_days`, `coarsen_after_days`.

**`geo_place`** — reverse-geocode cache keyed by `(provider, lat_rounded, lng_rounded)`; holds the
resolved place so nearby points reuse one lookup. Rounding precision configurable (default ~4 dp).

No extension needed — plain B-tree indexes, all inside the migration transaction. The `near`/`bbox`
queries run through the `GeoSearch` port: a bounding-box prefilter (`latitude between ? and ?` and
`longitude between ? and ?`, longitude window widened by `1/cos(lat)`) narrowed and ordered by the
Haversine distance. The SQL is confined to `HaversineGeoSearch`, so it is swappable for PostGIS later.

## 6. Storage abstraction and the PostGIS migration path

**No infrastructure change in Phase 1** — no image swap, no new dependency, no `create extension`. The
stack stays `postgres:18.4-alpine`, and because Haversine + bbox is ordinary SQL, geo does **not**
become an RDBMS-port seam (it stays portable, unlike the FTS seam).

The one design rule that makes the future cheap: **spatial operations are confined to the `GeoSearch`
port** (`geo/internal/GeoSearch.java`), implemented in Phase 1 by `HaversineGeoSearch` (bbox prefilter
+ Haversine over B-tree indexes). Nothing above the persistence layer — not the DTOs, the service, the
controller, or any other module — references a coordinate as anything but two decimals.

When advanced GIS (geofencing, polygon/boundary queries, spatial joins, GiST at scale) becomes a real
requirement, the migration is a bounded, additive sequence — no application rewrite:

1. Enable PostGIS (a `create extension postgis` migration) on a PostGIS-capable image (by then, ideally
   an official arm64 tag; otherwise the multi-arch/emulated options from the infra review).
2. Add a `location geography(Point,4326)` column (nullable), backfill from `latitude`/`longitude`.
3. Add a second `GeoSearch` implementation (`PostgisGeoSearch`, `ST_DWithin`/GiST) and switch the
   active bean — the service is unchanged.
4. Contract step: make `location` not-null, drop the Haversine impl. Geo then becomes a documented
   Postgres seam in `docs/PORTING.md`.

**ADR `0008-geolocation-storage.md`** records: numeric+Haversine for Phase 1, spatial ops behind
`GeoSearch`, PostGIS deferred behind this migration path, and the arm64/complexity rationale.

## 7. Geocoder SPI

`Geocoder` (public): `Optional<ResolvedPlace> reverse(double lat, double lng)`. Selection by
`geo.geocoder.provider` (`disabled` default). Adapters:

- **DisabledGeocoder** — no-op; coordinates only.
- **OfflineGeocoder** — resolves country/admin1 from a bundled administrative-boundary dataset (Natural
  Earth); fully offline; no network.
- **NominatimGeocoder** — self-hosted OSM; rich addresses without third-party cloud; via
  `SafeOutboundUrl`.
- **ExternalGeocoder** — Google/Mapbox/HERE/Azure Maps, per-deployment config; via `SafeOutboundUrl`,
  rate-limited; **only path that leaves the deployment**, and only when explicitly enabled.
- **CUSTOM** — any deployment-provided `@Bean Geocoder`; `GeocoderSelector` prefers it.

Execution: `GeoStampRecorded` → `ReverseGeocodeWorker` (async, idempotent via `EventInbox`) → check
`geo_place` cache by rounded coords → on miss call the active `Geocoder`, cache, update the stamp's
place columns. Capture-path latency never waits on a geocoder.

## 8. API surface (`/api/v1`, envelope + cursor pagination as always)

- `POST /api/v1/geo/stamps` — attach a fix `{subjectType, subjectId, lat, lng, accuracyM, source,
  capturedAt, consentRef?}`; also attachable inline in a record-creating request (the owning module
  forwards the `geo` block to `GeoStamps.attach`). Permission `geo:capture`.
- `GET /api/v1/geo/stamps?subjectType=&subjectId=` | `?near=lat,lng,radiusM` | `?bbox=` — query;
  `geo:read` returns coarsened, `geo:read_precise` returns exact coordinates.
- `GET|PUT /api/v1/orgs/{orgId}/geo/policies/{subjectType}` — read/set the capture policy;
  `geo:policy:manage`.

Controllers return `ResourceObject` / `WindowedResult<ResourceObject>` (auto-wrapped); failures throw
typed `ApiException` with `ApiSource`. Collections keyset-paginate on a stable `Sort` (no totals).

## 9. Capture flow and policy enforcement

Client reads the device location (W3C Geolocation API / mobile GPS) and sends the fix with the form.
The owning module calls `geoStamps.attach(subjectType, id, fix)`, which validates against the
`(org_id, subject_type)` policy: **mode** (`REQUIRED` + missing/too-coarse fix → `ValidationException`,
record rejected), **min accuracy**, **freshness** (`max_fix_age_seconds`), **allowed sources**. The
gateway already derives `EdgeClientIp` (trusted-proxy-hops) → an *optional* coarse `source=IP` fallback
via the `GeoIp` port when a device fix is unavailable and policy allows it.

## 10. Events

- `geo.GeoStampRecorded(orgId, stampId, subjectType, subjectId, capturedBy, occurredAt)` — on save;
  consumed by `ReverseGeocodeWorker` (and available to other modules).
- `geo.GeoPolicyChanged(orgId, subjectType, mode, occurredAt)` — on policy change (published
  explicitly; a `delete`/update fires no `@DomainEvents`).

Both listed in `docs/EVENTS.md` (publisher + consumer tables). Any side-effecting consumer guards with
`EventInbox.recordIfNew(listenerId, messageId)`, `messageId` derived from business identity +
`occurredAt`.

## 11. Privacy, compliance, security (government posture)

- **Audit**: every policy change and every *precise* read goes through `shared.audit.AuditLog`.
- **Retention & holds**: honors `RetentionPurges` (per-policy `retention_days`) and `LegalHolds`; a
  **coarsen** operation truncates precision to region after `coarsen_after_days` (analytics-safe)
  instead of deleting.
- **Consent**: `consent_ref` captured per stamp when policy requires it.
- **Least privilege**: `geo:read_precise` is disjoint from `geo:capture`; ambiguous states deny;
  cross-tenant denied before any DB hit.
- **No egress by default**: coordinates leave the deployment only via `ExternalGeocoder`, only when
  explicitly enabled, only through `SafeOutboundUrl`.

## 12. Documentation & test duties (AGENTS §13/§14, same change-set)

`api-guide.html` (new endpoints), `system-diagram.html` (new module + geo store),
`./gradlew exportOpenApi` (`docs/openapi/`), `docs/SRS.md` §4.6 + a requirement & traceability row,
`docs/LOCAL_ACCESS.md`, `docs/DATA_MODEL.md` (3 tables + migration history — note V47, and fix the stale
"next free V40" prose), `docs/EVENTS.md` (both tables), `docs/ARCHITECTURE.md` +
`./gradlew exportModulithDocs` (`docs/modulith/`), ADR `0008-geolocation-storage.md`,
`docs/CHECKLIST.md`. Tests (real containers, `AbstractIntegrationTest`) assert the negatives:
REQUIRED-rejects-missing-fix, too-coarse-rejected, precise-read-denied-without-permission,
cross-tenant-denied, reverse-geocode-idempotent, `ST_DWithin` radius query correct.

## 13. Phased rollout (each phase has an acceptance gate)

1. **MVP** — `shared.geo` port + value types, the `GeoSearch` seam (`HaversineGeoSearch`), the `geo`
   module with `geo_stamp` + `geo_capture_policy`, attach + query (`near`/`bbox`) REST, policy
   enforcement, audit/retention wiring, `GeoStampRecorded` event, integration tests, doc updates.
   *Gate*: `./gradlew build` green (ModularityTests + real-container tests); attach→query round-trip
   and REQUIRED-rejection proven.
2. **Reverse geocoding** — `Geocoder` SPI, `DisabledGeocoder` + `OfflineGeocoder`, `geo_place` cache,
   async `ReverseGeocodeWorker`. *Gate*: offline resolve + cache-hit proven; capture latency unchanged.
3. **Providers + config UX + IP fallback** — Nominatim/External adapters (`SafeOutboundUrl`,
   rate-limited), policy management surface, `GeoIp` off `EdgeClientIp`. *Gate*: provider swap by config
   only; egress gated.
4. **Advanced spatial (if needed)** — geofences, region rollups/analytics, coarsen job. *Gate*:
   geofence-enter detection + retention/coarsen jobs proven.

## 14. Open risks

- Haversine + bbox is accurate and fast to ~hundreds of thousands of rows; very large datasets or true
  GIS (geofences, polygons, spatial joins) are the trigger to execute the PostGIS migration path (§6).
- The bbox longitude window must widen by `1/cos(latitude)` and guard the ±180° antimeridian and the
  poles — handled in `HaversineGeoSearch` and asserted in tests.
- `numeric(9,6)` lat/lng is the domain contract; keep spatial types out of business logic so the future
  PostGIS swap stays persistence-only.
