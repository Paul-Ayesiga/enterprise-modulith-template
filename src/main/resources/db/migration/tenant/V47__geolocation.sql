-- Geolocation. `geo_stamp` attaches a position to any record through a POLYMORPHIC soft reference
-- (subject_type + subject_id) — never a foreign key, so geo couples to no other module's schema and
-- any record type can be stamped. Coordinates are plain numeric WGS-84 (no PostGIS by decision, see
-- docs/GEOLOCATION_PLAN.md §6): nearby/bounding-box search is a bbox prefilter over the
-- (latitude, longitude) index, narrowed by Haversine — confined to HaversineGeoSearch so a later move
-- to PostGIS is persistence-only. The place columns are filled asynchronously by reverse geocoding
-- (Phase 2) and stay null until then. `geo_capture_policy` is the per-org, per-record-type switch that
-- decides whether a location is off, optional, or required.
--
-- org_id is organization.id (V11) — a SOFT ref with no FK, geo being its own module. It leads all
-- three live-row indexes below and every read is org-scoped, so the tenant retarget changes the value
-- in every row and the shape of none.
--
-- captured_by_person_id is person.id (V10), also a soft ref with no FK. It was `captured_by
-- varchar(64)` holding a Keycloak subject, and this rename is not cosmetic: the value is ON THE WIRE.
-- It rides the GeoStampRecorded event across the bus and is rendered in the stamp response, so the
-- column, the event and the API guide move together (AGENTS §14). Its NULLability is now load-bearing
-- twice over: null for an unauthenticated capture, as before, and null for a MACHINE capture, which
-- is new — an API key is not any person, and "a robot stamped this" is honestly recorded as no person
-- id rather than as a manufactured one.
--
-- subject_id STAYS varchar(64) AND STAYS POLYMORPHIC. It is the column a "convert every identifier to
-- person_id" sweep will reach for, and converting it would be the bug rather than the fix: the pair
-- (subject_type, subject_id) points at ANY record type, and it holds a person key only on the rows
-- whose subject_type is person-shaped. Re-keying those is the PRODUCER's job, per subject_type — never
-- a column-wide conversion, and never a foreign key. That is the design commitment in the first
-- paragraph, not an oversight. consent_ref is likewise not a subject: it names the consent record
-- (V34) a capture was taken under.

create table geo_stamp (
    id                    uuid           primary key,
    org_id                uuid           not null,
    subject_type          varchar(64)    not null,
    subject_id            varchar(64)    not null,
    latitude              numeric(9, 6)  not null,
    longitude             numeric(9, 6)  not null,
    accuracy_m            numeric(10, 2),
    altitude_m            numeric(10, 2),
    source                varchar(16)    not null,
    captured_by_person_id uuid,                     -- null = unauthenticated OR machine capture
    captured_at           timestamptz    not null,
    consent_ref           varchar(64),              -- consent_record ref (V34), not a subject
    country_code          varchar(2),
    admin1                varchar(120),
    locality              varchar(160),
    formatted_address     text,
    place_id              varchar(128),
    geocoder_provider     varchar(24),
    created_at            timestamptz    not null,
    created_by            uuid        ,
    updated_at            timestamptz,
    updated_by            uuid        ,
    version               bigint         not null,
    deleted_at            timestamptz
);

-- Live rows only (partial): the reads all filter deleted_at is null.
create index geo_stamp_subject_idx  on geo_stamp (org_id, subject_type, subject_id) where deleted_at is null;

create index geo_stamp_captured_idx on geo_stamp (org_id, captured_at)              where deleted_at is null;

create index geo_stamp_bbox_idx     on geo_stamp (org_id, latitude, longitude)      where deleted_at is null;

-- Retention sweep target (the SoftDeletePurgeJob's idx_<table>_deleted convention).
create index geo_stamp_deleted      on geo_stamp (deleted_at)                        where deleted_at is not null;

-- The per-org, per-record-type switch. org_id is organization.id (V11), same soft ref as above, but
-- the shape differs from its neighbour in a way that matters to anyone retargeting both in one pass:
-- this table has no deleted_at, so its unique is TOTAL rather than partial. It is configuration, not
-- an aggregate — and a duplicate here fails hard and loudly instead of quietly admitting a second
-- policy for the same (org, subject_type), which is the behaviour you want on a switch.
create table geo_capture_policy (
    id                  uuid        primary key,
    org_id              uuid        not null,
    subject_type        varchar(64) not null,
    mode                varchar(16) not null,
    min_accuracy_m      numeric(10, 2),
    allowed_sources     varchar(64),
    max_fix_age_seconds integer,
    retention_days      integer,
    coarsen_after_days  integer,
    created_at          timestamptz not null,
    created_by          uuid        ,
    updated_at          timestamptz,
    updated_by          uuid        ,
    version             bigint      not null
);

create unique index uq_geo_capture_policy on geo_capture_policy (org_id, subject_type);
