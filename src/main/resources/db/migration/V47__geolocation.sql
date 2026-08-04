-- Geolocation. `geo_stamp` attaches a position to any record through a POLYMORPHIC soft reference
-- (subject_type + subject_id) — never a foreign key, so geo couples to no other module's schema and
-- any record type can be stamped. Coordinates are plain numeric WGS-84 (no PostGIS by decision, see
-- docs/GEOLOCATION_PLAN.md §6): nearby/bounding-box search is a bbox prefilter over the
-- (latitude, longitude) index, narrowed by Haversine — confined to HaversineGeoSearch so a later move
-- to PostGIS is persistence-only. The place columns are filled asynchronously by reverse geocoding
-- (Phase 2) and stay null until then. `geo_capture_policy` is the per-org, per-record-type switch that
-- decides whether a location is off, optional, or required.
create table geo_stamp (
    id                uuid          primary key,
    org_id            uuid          not null,
    subject_type      varchar(64)   not null,
    subject_id        varchar(64)   not null,
    latitude          numeric(9, 6)  not null,
    longitude         numeric(9, 6)  not null,
    accuracy_m        numeric(10, 2),
    altitude_m        numeric(10, 2),
    source            varchar(16)   not null,
    captured_by       varchar(64),
    captured_at       timestamptz   not null,
    consent_ref       varchar(64),
    country_code      varchar(2),
    admin1            varchar(120),
    locality          varchar(160),
    formatted_address text,
    place_id          varchar(128),
    geocoder_provider varchar(24),
    created_at        timestamptz   not null,
    created_by        varchar(100),
    updated_at        timestamptz,
    updated_by        varchar(100),
    version           bigint        not null,
    deleted_at        timestamptz
);
-- Live rows only (partial): the reads all filter deleted_at is null.
create index geo_stamp_subject_idx  on geo_stamp (org_id, subject_type, subject_id) where deleted_at is null;
create index geo_stamp_captured_idx on geo_stamp (org_id, captured_at)              where deleted_at is null;
create index geo_stamp_bbox_idx     on geo_stamp (org_id, latitude, longitude)      where deleted_at is null;
-- Retention sweep target (the SoftDeletePurgeJob's idx_<table>_deleted convention).
create index geo_stamp_deleted      on geo_stamp (deleted_at)                        where deleted_at is not null;

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
    created_by          varchar(100),
    updated_at          timestamptz,
    updated_by          varchar(100),
    version             bigint      not null
);
create unique index uq_geo_capture_policy on geo_capture_policy (org_id, subject_type);
