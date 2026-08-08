-- One security policy per organization (soft-deletable aggregate). Every field TIGHTENS access;
-- an absent policy (or an absent field) means the platform default applies. Enforced in a filter
-- AFTER authentication for org-scoped calls — a policy denial is a distinct, audited, counted 403
-- naming the policy, never mistakable for an RBAC decision.
--
-- org_id is organization.id (V11) and stays a SOFT ref with no FK — access and organization are
-- different modules (AGENTS §1). Only the value space changed, from a Keycloak organization id to a
-- row we own; the name and the meaning did not.
--
-- KNOWN GAP, recorded here because this is the table that declares the rule: require_trusted_device
-- is per-ORG, and user_device (V31) carries no org column, so a device trusted inside org A already
-- satisfies org B's rule for the same person. Retargeting the device table onto person.id did not
-- change that either way — the fix is deciding whether trust belongs on a (person, organization,
-- device) triple, which is its own slice.

create table org_security_policy
(
    id                       uuid        not null primary key,
    org_id                   uuid        not null,          -- organization.id; soft ref, no FK
    ip_allowlist             text,                          -- comma-joined CIDRs; empty = no IP restriction
    require_trusted_device   boolean     not null default false,
    session_max_age_seconds  bigint,                        -- reject tokens older than this for the org
    version                  bigint      not null,
    created_at               timestamptz not null,
    created_by               uuid        ,
    updated_at               timestamptz,
    updated_by               uuid        ,
    deleted_at               timestamptz
);

create unique index uq_org_security_policy_org_live on org_security_policy (org_id)
    where deleted_at is null;

create index idx_org_security_policy_deleted on org_security_policy (deleted_at)
    where deleted_at is not null;
