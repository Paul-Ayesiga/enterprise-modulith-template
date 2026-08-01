-- One security policy per organization (soft-deletable aggregate). Every field TIGHTENS access;
-- an absent policy (or an absent field) means the platform default applies. Enforced in a filter
-- AFTER authentication for org-scoped calls — a policy denial is a distinct, audited, counted 403
-- naming the policy, never mistakable for an RBAC decision.

create table org_security_policy
(
    id                       uuid        not null primary key,
    org_id                   uuid        not null,
    ip_allowlist             text,                          -- comma-joined CIDRs; empty = no IP restriction
    require_trusted_device   boolean     not null default false,
    session_max_age_seconds  bigint,                        -- reject tokens older than this for the org
    version                  bigint      not null,
    created_at               timestamptz not null,
    created_by               varchar(100),
    updated_at               timestamptz,
    updated_by               varchar(100),
    deleted_at               timestamptz
);

create unique index uq_org_security_policy_org_live on org_security_policy (org_id)
    where deleted_at is null;

create index idx_org_security_policy_deleted on org_security_policy (deleted_at)
    where deleted_at is not null;
