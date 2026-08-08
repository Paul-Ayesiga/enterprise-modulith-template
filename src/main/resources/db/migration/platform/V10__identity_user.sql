-- Identity module: PERSON is the canonical identity, and every identifier minted by somebody else
-- lives in external_identity. Nothing below the edge ever sees an issuer or a subject again.
--
-- This replaces `app_user`, which was two things welded together — its own javadoc said so: "local
-- projection of a Keycloak user + provisioning lifecycle". The projection half is external_identity;
-- the lifecycle half is person. Three facts forced a split rather than a rename:
--
--   (a) The subject column already did NOT hold a Keycloak sub. A machine principal writes
--       "key:<api_key.id>" and the auditor wrote that into created_by, so
--       `subject varchar(64) -- Keycloak user id (JWT sub)` was already false for every row a machine
--       touched, and the width was sized to one provider's UUID shape for no reason that survived a
--       second principal type.
--   (b) Adding an identity provider was a MIGRATION, because the sub WAS the human — there was
--       nowhere to put a second sub for one person. It is now an insert into external_identity.
--   (c) Nothing in the schema referenced app_user.id. Every reader keyed on the subject string
--       instead, so there was no local identity to point at even where the module boundary allowed
--       it. An aggregate root whose identity nobody uses is a lookup table with an unused key.
--
-- FK RULE (AGENTS §1): person, external_identity, person_profile, person_contact and
-- person_preference are ONE module, so the foreign keys here and in V28 are intra-module and real.
-- Everything OUTSIDE identity still reaches a person by a SOFT ref with no FK — the rule did not
-- change. What changed is only what that soft ref carries: person.id, a uuid of ours, instead of a
-- string of Keycloak's.
--
-- SOFT DELETE is declared here rather than retrofitted in V17. V17 still owns the design rationale
-- (and still retrofits the three tables that predate it), but these tables are authored knowing soft
-- delete exists, and the partial unique indexes below are inseparable from the comments that explain
-- why they are partial.

-- ---------------------------------------------------------------------------------------------
-- 1. person — the canonical identity, and nothing else
-- ---------------------------------------------------------------------------------------------
-- Holds ONLY what is true of the human independent of how they sign in: are they allowed in, and
-- where are they in that lifecycle. No email, no name, no locale, no subject. Each of those is either
-- provider-shaped (subject), contact-shaped (email) or presentation-shaped (name) and lives in its
-- own table, because each has a different owner, a different mutation rate and — for email — a
-- verification state the identity has no business carrying.
--
-- status / activated_at are lifted verbatim from app_user: they are the one part of that table that
-- was NOT a projection of Keycloak. Keycloak neither knows nor stores that a human turned up.
create table person
(
    id           uuid        not null primary key,
    status       varchar(20) not null,          -- INVITED | ACTIVE | DISABLED
    invited_at   timestamptz not null,          -- was app_user.provisioned_at; "provisioned" named
                                                -- the Keycloak call, not the fact being recorded
    activated_at timestamptz,                   -- first real API hit; null until the human turns up
    disabled_at  timestamptz,                   -- NEW. disable() set status and nothing else, so
                                                -- "when did access stop" was answerable only by
                                                -- grepping audit_log; retention and erasure (V34)
                                                -- need a date, not a scan

    -- NAME, modelled on SCIM 2.0's `name` complex attribute (RFC 7643 §4.1.1) and the OIDC standard
    -- claims (OpenID Connect Core 1.0 §5.1) rather than on first/last. The anglocentric pair gets
    -- three things wrong the moment this platform onboards beyond one market:
    --   * ORDER is cultural. Family-name-first is normal across much of East Asia and in Hungary, so
    --     rendering given || ' ' || family prints many people's names backwards. NEVER build a display
    --     string from the parts — render formatted_name. That is what it is for.
    --   * PART COUNT varies. Mononyms are ordinary (much of Indonesia), Icelandic names are patronymic
    --     rather than family names, and Spanish and Portuguese names carry two surnames. Every part
    --     below is therefore NULLABLE, family_name included, and a row carrying only formatted_name is
    --     valid rather than broken.
    --   * The parts exist for SORTING, MATCHING and form pre-fill. formatted_name exists for DISPLAY.
    -- Widths are generous deliberately: 100 chars of UTF-8 is roughly 25 CJK glyphs, and truncating
    -- somebody's name is the kind of insult a user does not forgive.
    formatted_name   varchar(200),               -- SCIM `formatted` / OIDC `name`. THE display value,
                                                 -- supplied by the provider or the person; never derived
    given_name       varchar(100),               -- SCIM `givenName` / OIDC `given_name`
    family_name      varchar(100),               -- SCIM `familyName` / OIDC `family_name`; nullable —
                                                 -- see mononyms above
    middle_name      varchar(100),               -- SCIM `middleName` / OIDC `middle_name`
    honorific_prefix varchar(50),                -- SCIM `honorificPrefix` — Dr., Prof., Rev.
    honorific_suffix varchar(50),                -- SCIM `honorificSuffix` — Jr., PhD, MBE
    preferred_name   varchar(100),               -- OIDC `nickname` — what they asked to be called.
                                                 -- Distinct from person_profile.display_name (V28),
                                                 -- which is a UI preference; this is part of who they are

    version      bigint      not null,
    created_at   timestamptz not null,
    created_by   uuid        ,
    updated_at   timestamptz,
    updated_by   uuid        ,
    deleted_at   timestamptz
);

-- The reconciliation job's candidate scan: non-DISABLED rows older than the grace period, walked in
-- keyset order (invited_at asc, id asc). Partial because that job is the only reader and it never
-- wants deleted rows.
create index idx_person_scan on person (invited_at, id)
    where deleted_at is null;

create index idx_person_deleted on person (deleted_at)
    where deleted_at is not null;

-- Sorting a member list by name is the one thing the parts are genuinely for, and this index is
-- deliberately NOT (family_name, given_name): callers sort on whatever index exists, so whichever
-- order is baked in here becomes the order every UI inherits — and family-name-first is wrong for
-- roughly half the world. Sort on formatted_name and let a caller that needs one apply a locale
-- collation.
create index idx_person_name on person (formatted_name)
    where deleted_at is null;

-- ---------------------------------------------------------------------------------------------
-- 2. external_identity — the adapter. One row per (provider, issuer, subject over there).
-- ---------------------------------------------------------------------------------------------
-- The ONLY table in the schema permitted to store an identifier minted by somebody else. Adding
-- Google, Apple or a SAML federation is an INSERT, not a migration — that is the entire point, and it
-- is why `provider` is a varchar with a documented vocabulary rather than a Postgres enum type: an
-- enum would put `alter type ... add value` back on the migration path, which is precisely the thing
-- being removed. Every other vocabulary in this schema is expressed the same way (person.status
-- above, V29's platform_tier, V34's scope) and none of them is a CHECK constraint either.
--
-- It also replaces an HTTP call. "Which providers is this person linked to?" was answered by asking
-- Keycloak on every request. Once the links are rows that is a local select, and it keeps answering
-- after Keycloak stops being the only issuer — which the remote call cannot do at all.
--
-- API_KEY is in the vocabulary for PERSONAL access tokens only, and NO SUCH ROW IS WRITTEN YET.
-- api_key (V29) has org_id and platform_tier but no owner column of any kind, so every key today
-- belongs to an org or to the platform, and an org-owned key is not any person. Manufacturing a
-- synthetic person per machine key would be actively harmful: it would flow into created_by, be
-- eligible for a membership row, be scanned by the reconciliation job (which would find no account
-- and disable it), and be a valid subject for a GDPR erasure request against a robot. The value is
-- RESERVED AND UNUSED until api_key gains `owner_person_id` — stated here rather than discovered
-- later. Machines keep their own attribution string in created_by; they never resolve to a person.
create table external_identity
(
    id                    uuid         not null primary key,
    person_id             uuid         not null references person (id) on delete cascade,
    provider              varchar(20)  not null,   -- KEYCLOAK | GOOGLE | MICROSOFT | APPLE | PASSKEY
                                                   -- | SAML | LDAP | API_KEY | INTERNAL
    issuer                varchar(300) not null,   -- the realm/tenant this subject is unique WITHIN
    external_subject      varchar(255) not null,   -- their id over there
    external_username     varchar(320),            -- what they are called over there; DISPLAY ONLY
    linked_at             timestamptz  not null,
    last_authenticated_at timestamptz,
    version               bigint       not null,
    created_at            timestamptz  not null,
    created_by            uuid        ,
    updated_at            timestamptz,
    updated_by            uuid        ,
    deleted_at            timestamptz
);

-- WHY issuer IS not null AND NOT NULLABLE: Postgres treats NULLs as DISTINCT in a unique index, so a
-- nullable issuer would let (KEYCLOAK, null, 'abc') be inserted twice — two persons claiming one
-- external identity, precisely the duplicate this table exists to make impossible. Providers with no
-- issuer URL write a canonical constant ('internal', 'ldap://<host>/<base-dn>'), never null.
--
-- WHY issuer EXISTS AT ALL: 'GOOGLE' names a product, not a namespace. A staging realm and a prod
-- realm both emit provider = KEYCLOAK over disjoint subject spaces; without the issuer in the key,
-- importing one into the other silently merges two different people.
--
-- WHY external_subject IS varchar(255) AND NOT varchar(64): app_user.subject was varchar(64) because
-- a Keycloak sub is a UUID. A SAML NameID or an LDAP DN routinely exceeds that. Sizing the column to
-- one provider's shape IS the coupling being removed.

-- THE resolution key: the index every authenticated request hits exactly once, turning a validated
-- (issuer, sub) pair into a person_id. This index must never be dropped.
create unique index uq_external_identity_subject_live
    on external_identity (provider, issuer, external_subject)
    where deleted_at is null;

-- One live link per person per issuer. Deliberate: two accounts at the SAME issuer for one person
-- would force the resolver to choose, and "choose" is where sync bugs live. A fork that genuinely
-- needs multi-account-per-issuer drops THIS index; the one above is not negotiable.
create unique index uq_external_identity_person_issuer_live
    on external_identity (person_id, provider, issuer)
    where deleted_at is null;

-- The reverse read: every link a person holds (the linked-accounts surface, which used to be a call
-- to Keycloak).
create index idx_external_identity_person on external_identity (person_id)
    where deleted_at is null;

create index idx_external_identity_deleted on external_identity (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------------------------------------
-- person_contact — how the platform REACHES a person. Lives in identity, not profile.
-- ---------------------------------------------------------------------------------------------
-- This is identity's own data, not a display preference. app_user.email lived here for that
-- reason, and UserDirectory — identity's PUBLISHED port — is what serves address lookups to the
-- other modules (findSubjectByEmail, emailsBySubjects; organization calls both). Parking the
-- address in `profile` would invert that dependency: identity would have to call profile to
-- answer a question about its own accounts, and the FK below would cross a module boundary that
-- is a pre-drawn service seam. Display fields (person_profile) and settings (person_preference)
-- stay in profile and reference person WITHOUT a foreign key, because those two genuinely can
-- split off into their own service and a constraint cannot cross a network.

create table person_contact
(
    id            uuid         not null primary key,
    person_id     uuid         not null references person (id) on delete cascade,
    kind          varchar(10)  not null,              -- EMAIL | PHONE | OTHER
    contact_value varchar(320) not null,              -- 320 = the RFC 5321 ceiling, and the widest
                                                      -- width any table already used for an address;
                                                      -- the old contact column was 150, which would
                                                      -- truncate or fail on a real long address
    label         varchar(50),
    is_primary    boolean      not null default false,
    verified_at   timestamptz,                        -- null = claimed but never proven
    version       bigint       not null,
    created_at    timestamptz  not null,
    created_by    uuid        ,
    updated_at    timestamptz,
    updated_by    uuid        ,
    deleted_at    timestamptz
);

-- WHY verified_at IS LOAD-BEARING AND NOT DECORATION: nothing in this schema recorded that an address
-- was ever proven. The signup flow (V42) runs a real email-verification handshake and then throws the
-- result away — status flips PENDING -> COMPLETED and no verified flag survives — while the edge read
-- the raw `email` claim with no reference to `email_verified`. So the platform held an unverified
-- string and treated it as authoritative.
--
-- Global uniqueness of a PROVEN address. Each predicate earns its place:
--   lower()                 - the case fold belongs in the index, so the database AGREES with the
--                             case-insensitive lookup the code already does rather than tolerating it
--   deleted_at is null      - V17's rule, uniformly applied: a deleted person must not hold an
--                             address hostage forever
--   verified_at is not null - this is what makes the constraint safe to enforce over real data. A
--                             re-created account (new subject, same address) and a case variant both
--                             land as unverified duplicates and violate nothing. Uniqueness then
--                             arrives one proof at a time: the first person to verify an address
--                             claims it, and every later attempt fails AT VERIFICATION — where a
--                             human is present, an error message makes sense, and support has a trail
--                             — instead of at provisioning, where it would be a 500 in a batch job.
create unique index uq_person_contact_verified_live
    on person_contact (kind, lower(contact_value))
    where verified_at is not null and deleted_at is null;

-- One primary per kind per person. The old shape gave is_primary a default and no constraint, so
-- "the primary email" was whichever row the planner happened to return first.
create unique index uq_person_contact_primary_live on person_contact (person_id, kind)
    where is_primary and deleted_at is null;

-- The hot read: this person's contacts (the /me surface, notification fan-out).
create index idx_person_contact_person on person_contact (person_id, kind)
    where deleted_at is null;

-- The directory lookup: find the person behind an address. Deliberately NOT restricted to verified
-- rows — the lookup must still FIND an unverified address in order to report it as unverified, which
-- is a different answer from not finding it at all.
create index idx_person_contact_value on person_contact (kind, lower(contact_value))
    where deleted_at is null;

create index idx_person_contact_deleted on person_contact (deleted_at)
    where deleted_at is not null;
