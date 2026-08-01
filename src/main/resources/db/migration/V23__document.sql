-- Document: the business record OF a stored file — name, owner, org, provenance — over keys the
-- files module holds. Soft-deletable like every user-facing aggregate, with one deliberate
-- asymmetry stated here because the schema cannot show it: DELETE removes the OBJECT immediately
-- (storage is not soft-deletable) while the row soft-remains as the metadata trail — a restore
-- recovers the record of the document, never its content.
create table document
(
    id            uuid primary key,
    org_id        uuid,                       -- null = personal document (owner-scoped, files-style tiering)
    owner_subject varchar(64)  not null,      -- token subject, never a username
    storage_key   varchar(300) not null,      -- soft ref into the files namespace; bytes live behind the port
    name          varchar(255) not null,
    content_type  varchar(100) not null,
    size_bytes    bigint       not null,
    source        varchar(20)  not null,      -- UPLOAD | EXCHANGE
    version       bigint       not null,
    created_at    timestamptz  not null,
    created_by    varchar(100),
    updated_at    timestamptz,
    updated_by    varchar(100),
    deleted_at    timestamptz
);

-- A storage key is one document while it lives; a deleted row frees it (the object is gone anyway).
create unique index uq_document_storage_key on document (storage_key) where deleted_at is null;
-- Org listing, house keyset sort.
create index idx_document_org_recent on document (org_id, created_at desc, id desc) where deleted_at is null;
-- Personal listing.
create index idx_document_owner_recent on document (owner_subject, created_at desc, id desc)
    where deleted_at is null and org_id is null;
-- Retention scan.
create index idx_document_deleted on document (deleted_at) where deleted_at is not null;
