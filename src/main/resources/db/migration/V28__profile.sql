-- Person self-service enrichment: profile (display fields + avatar key), contacts (reachability),
-- preferences (small per-person key/value). All three key on person.id (V10), and the foreign keys
-- are REAL because an intra-module FK is permitted and free (AGENTS §1, §4.5).
--
-- THAT CARRIES A REQUIREMENT ON THE JAVA SIDE, so it is stated here rather than discovered at review:
-- these three tables belong to the IDENTITY module, alongside person. Today's entities live in
-- `profile`, and if they stay there the three foreign keys below become CROSS-module links, which
-- AGENTS §1 fails a review for. Either the entities move into identity with the person aggregate, or
-- these FKs must come back out. They are a facet of a person, not a separate root — the one-live-row
-- uniqueness below is the argument — so the move is the right half to choose. user_device (V31) is
-- the counter-example that stays in `profile`: it is its own aggregate and reaches person by a soft
-- ref with no FK.
--
-- Three things changed from the user_* shape these replace, each forced by evidence rather than taste:
--
--   * The key is person_id uuid, not `subject varchar(64)`. On the profile that was a soft ref to a
--     Keycloak sub; on preferences it was not even a ref — it was half a PRIMARY KEY holding another
--     system's identifier, referencing nothing, so orphan preference rows for subjects that never
--     existed were silently possible.
--
--   * phone LEAVES the profile. The old shape shipped BOTH user_profile.phone and
--     user_contact(kind = 'PHONE') — one fact in two places with no constraint keeping them equal.
--     The contact row is strictly the better home: it can be labelled, marked primary, and verified.
--     A column on the profile could be none of those.
--
--   * Contacts hang off PERSON, not the profile, and they become addressable rows with a lifecycle.
--     A person with no profile still has an email — every provisioned account had one and only some
--     had a profile row — so parenting contacts on a display preference meant deleting the display
--     preference deleted the address the system mails.
--
-- Species: person_profile is a soft-deletable aggregate. person_contact is now one too, because a
-- verification is an event about a specific row and rows you cannot address cannot be verified,
-- revoked, or pointed at by an audit entry. person_preference stays the idempotency-key species —
-- plain rows, no lifecycle beyond their person, because a preference has no independent existence
-- whose deletion is worth recording.

-- WHY NO FOREIGN KEY on person_id here, when identity's own tables have one:
-- a module boundary in this modulith is a pre-drawn service seam, and profile is a plausible
-- service of its own — display fields and settings have no bearing on authentication. A foreign
-- key is the strongest coupling available (the database refusing to let the two exist apart) and
-- it cannot survive that split, so it is not taken now. The cost is stated rather than hidden:
-- deleting a person no longer cascades these rows, so erasure must delete them explicitly —
-- which is how every other cross-module cleanup in this schema already works (SoftDeletePurgeJob
-- carries an ordered map precisely because it cannot lean on cascades), and how it would have to
-- work as an event once these are separate services.
create table person_profile
(
    id           uuid         not null primary key,
    person_id    uuid         not null,                       -- SOFT REF to person.id, no FK
                                                              -- (see the note above the table)
    display_name varchar(150),
    avatar_key   varchar(300),                        -- files-port key (avatar/p/<person_id>/…). The
                                                      -- old key embedded the Keycloak sub, which made
                                                      -- the object store a third place holding one
    timezone     varchar(50),
    locale       varchar(20),
    version      bigint       not null,
    created_at   timestamptz  not null,
    created_by   uuid        ,
    updated_at   timestamptz,
    updated_by   uuid        ,
    deleted_at   timestamptz
);

-- One live profile per person. This 1:1-per-live-row shape is the strongest evidence that a profile
-- is a FACET of a person rather than a second identity — which is what it had become when it was
-- keyed on the same subject string as the account table with nothing linking the two.
create unique index uq_person_profile_person_live on person_profile (person_id)
    where deleted_at is null;

create index idx_person_profile_deleted on person_profile (deleted_at)
    where deleted_at is not null;

-- No id, no version, no deleted_at, no audit columns — deliberately. The one addition over the shape
-- this replaces is the FK: the old preference rows referenced nothing at all, so preferences for a
-- deleted account lingered with no way to find them except a string join against a table that hides
-- its deleted rows. `on delete cascade` makes the hard-delete purge correct without the purge job
-- needing to know this table exists.

create table person_preference
(
    person_id  uuid         not null,                         -- SOFT REF to person.id, no FK
    pref_key   varchar(100) not null,
    pref_value varchar(500) not null,
    updated_at timestamptz  not null,
    primary key (person_id, pref_key)
);
