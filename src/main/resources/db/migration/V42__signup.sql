-- Self-service signup: an email-verification handshake in front of the standard org-provisioning
-- path. One row per request; the token is stored HASHED (a DB leak must not mint organizations);
-- a new request for the same email supersedes (deletes) its pending predecessors.
--
-- This is the PRE-IDENTITY table, and it is the cleanest argument for person being the root rather
-- than a projection of an identity provider: it describes a human — an email, a name — before any
-- external_identity (V10) exists for them, and it always did. So nothing in it needed retargeting;
-- no column here ever held a Keycloak subject.
--
-- What did need fixing is the other end of the handshake. org_id is organization.id (V11), still
-- nullable and still meaning "not completed yet". owner_person_id closes a gap this table has had
-- since it was written: a completed request produced a person and recorded no link to them, so the
-- request -> person relationship was recoverable only by matching the email string back. Both columns
-- are set at COMPLETION, in the same transaction that creates the organization and the person, and
-- never at request time — minting an identity from an address nobody has verified yet is precisely
-- the thing the handshake exists to prevent.
--
-- The name columns are given_name/family_name, not first_name/last_name, because the row they
-- eventually become is a person (V10) and the two must not disagree about what a name is. The
-- vocabulary rule is the same one that keeps kc_org_id inside external_organization: firstName /
-- lastName survives only inside the Keycloak adapter, which speaks Keycloak's vocabulary at the
-- boundary. Everywhere above it — including this public self-service body — the platform's own
-- vocabulary applies. Both stay NULLABLE: a signup that supplies only an email is a valid request.
create table signup_request (
    id              uuid primary key,
    email           varchar(255) not null,
    org_name        varchar(80)  not null,
    given_name      varchar(60),
    family_name     varchar(60),
    token_hash      varchar(64)  not null unique,
    status          varchar(16)  not null,       -- PENDING | COMPLETED
    expires_at      timestamptz  not null,
    created_at      timestamptz  not null,
    completed_at    timestamptz,
    org_id          uuid,                        -- organization.id; soft ref, no cross-module FK.
                                                 -- Null while PENDING
    owner_person_id uuid                         -- person.id of the human this request produced; soft
                                                 -- ref, no FK — identity is another module (AGENTS §1).
                                                 -- Null while PENDING
);
create index idx_signup_email on signup_request (email, status);
