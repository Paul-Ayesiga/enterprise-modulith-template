-- Localization: the translation catalog behind the Messages port. Translations are editable
-- platform config — the same species as setting — so the table is soft-deletable with the usual
-- partial unique: a deleted (locale, key) frees its slot for recreation, and stays restorable
-- inside the retention window. Locales are stored as LOWERCASED BCP-47 tags so lookups never
-- depend on tag casing.
create table translation
(
    id         uuid primary key,
    locale     varchar(35)  not null,
    msg_key    varchar(200) not null,
    msg_value  text         not null,
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);

-- The uniqueness contract, live rows only (soft delete must free the key).
create unique index uq_translation_locale_key on translation (locale, msg_key) where deleted_at is null;
-- Bundle load: MessagesImpl reads one whole locale per cache miss.
create index idx_translation_locale on translation (locale) where deleted_at is null;
-- Cursor listing (the house createdAt desc, id desc sort).
create index idx_translation_recent on translation (created_at desc, id desc) where deleted_at is null;
-- Retention scan (SoftDeletePurgeJob walks deleted rows oldest-first).
create index idx_translation_deleted on translation (deleted_at) where deleted_at is not null;
