-- Compliance: consent, legal holds, erasure requests. None are soft-deletable — like audit_log and
-- impersonation_session, these are the records that must not themselves be quietly removable.
--
-- Every identity column here is person.id (V10), reached as a SOFT ref with no FK: compliance is not
-- the identity module (AGENTS §1). Each of them held a Keycloak subject when it was written, which is
-- the worst place in the schema for a foreign system's identifier to live — a hold that fails to
-- match, or an erasure that matches the wrong rows, is a legal problem rather than a bug.
--
-- placed_by / released_by / requested_by were subjects too, not usernames, despite their varchar(100)
-- width: every caller passes CurrentUser.accountableSubject(). They are now *_person_id, and that
-- carries a CONSEQUENCE worth stating rather than discovering: a machine principal has no person, so
-- it can no longer place a hold, release one, or file an erasure request. placed_by_person_id and
-- requested_by_person_id stay NOT NULL — a hold or an erasure with no accountable human is not a
-- record worth keeping — so the admin surface must refuse an API-key caller outright rather than
-- reach for a null it cannot write. released_by_person_id stays nullable for the reason it always
-- was: a hold still in force has no releaser yet.
--
-- TWO BINDINGS ON THE JAVA SIDE, stated here because both fail at RUNTIME rather than at compile time:
--
--   * SoftDeletePurgeJob builds its legal-hold clause from string literals — "subject" for a
--     person-owned table and "org_id" for an org-owned one — and joins the org side against
--     organization.kc_org_id, a column that no longer exists. Both are now wrong. The purge, the
--     HELD_OWNER map and this table move as ONE slice, with a test proving a held person survives a
--     purge run. A mismatch here does not throw; it matches nothing, and a nightly job resumes
--     hard-deleting data a court said to keep.
--
--   * ComplianceService generates `update <table> set deleted_at = ... where subject = ?` over a
--     hardcoded table list (app_user, user_profile, user_device). The string "subject" is a COLUMN
--     NAME inside that generated SQL. app_user no longer exists and the other two key on person_id,
--     so the erasure path is a runtime error until the list becomes one `where person_id = ?`.
--
-- consent_record is APPEND-ONLY: a withdrawal is a NEW row, never an update, so the full history of
-- what a person agreed to and when is intact. It has no created_by, deliberately: the entity is a
-- plain @Entity that never extended BaseEntity and declared no @CreatedBy, so the column the table
-- used to carry was null on every row ever written — a column the schema promised and nothing kept.
-- created_at plus the append-only rule already answer when, and person_id answers who.
create table consent_record
(
    id         uuid         not null primary key,
    person_id  uuid         not null,             -- person.id; soft ref, no FK
    purpose    varchar(60)  not null,             -- e.g. marketing, analytics, product-updates
    granted    boolean      not null,             -- true = granted, false = withdrawn
    source     varchar(60),                       -- where the decision came from (ui, import, api)
    created_at timestamptz  not null
);

create index idx_consent_person on consent_record (person_id, purpose, created_at desc);

-- legal_hold: while active (released_at null) the person's or org's data must NOT be hard-deleted.
-- The purge job and the erasure executor both consult it. Released, never deleted — a released hold
-- is the record that the hold WAS in force.
--
-- scope stays the discriminator and keeps its vocabulary unchanged: SUBJECT now means "person_id is
-- the one that is set". Renaming that value to PERSON belongs with the writer that spells it, not
-- with a column rename that would leave the stored rows and the Java constant disagreeing.
create table legal_hold
(
    id                    uuid         not null primary key,
    scope                 varchar(10)  not null,      -- SUBJECT | ORG
    person_id             uuid,                       -- person.id; set when scope = SUBJECT
    org_id                uuid,                       -- organization.id; set when scope = ORG
    reason                varchar(300) not null,
    placed_by_person_id   uuid         not null,      -- the human accountable for the hold
    placed_at             timestamptz  not null,
    released_at           timestamptz,
    released_by_person_id uuid
);

-- The purge/erasure hot check: is there an ACTIVE hold for this person / this org?
create index idx_legal_hold_active_person on legal_hold (person_id) where released_at is null;
create index idx_legal_hold_active_org on legal_hold (org_id) where released_at is null;

-- erasure_request: a GDPR art. 17 request and its outcome. Kept (a compliance record), not
-- soft-deletable; execution is orchestrated by the compliance module against machinery that
-- already exists (soft delete + the retention purge). Both columns are persons and they are
-- deliberately separate: on a self-service request they are equal, on an admin-initiated one they
-- are not, and which of the two it was is the question an auditor asks first.
create table erasure_request
(
    id                     uuid         not null primary key,
    person_id              uuid         not null,     -- the person whose data is to be erased
    requested_by_person_id uuid         not null,     -- who asked; equals person_id when self-service
    status                 varchar(20)  not null,     -- RECEIVED | EXECUTED | REFUSED
    detail                 varchar(300),
    created_at             timestamptz  not null,
    updated_at             timestamptz
);

create index idx_erasure_request_person on erasure_request (person_id, created_at desc);
