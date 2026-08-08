-- Organization module: the tenant, the identifiers other systems know it by, and org-scoped RBAC.
-- Permissions are a fixed enum; roles are editable bundles of permissions (system roles are immutable).
--
-- organization.id IS the tenant key. It used to be decoration. The table carried both `id` and
-- `kc_org_id`, the module's public port returned kc_org_id, and so every org_id column in every other
-- module stored a KEYCLOAK identifier — which then escaped in three directions: to the public API as
-- a JSON:API resource id, to Kill Bill as an account externalKey, and to the gateway as the usage
-- consumer id. kc_org_id is now one row in external_organization, the org-side twin of
-- external_identity (V10), and `org_id` everywhere in this schema means organization.id.
--
-- FK RULE (AGENTS §1): organization, external_organization, org_role, role_permission and membership
-- are ONE module, so org_id may finally be a real foreign key — and is, below. That is not a new
-- exception; it is what becomes possible once org_id names a row in a table we own instead of an
-- identifier in a system we do not. membership.person_id is the case that proves the rule still
-- holds: person lives in the identity module, so it stays a SOFT ref with no FK.
--
-- SOFT DELETE is declared here rather than retrofitted in V17, for the reason given in V10's header.
--
-- SPLIT (ADR 0010 §4.1): this is the platform half of V11. Its sibling is db/migration/tenant/V11__organization_rbac.sql.

create table organization
(
    id         uuid         not null primary key,
    alias      varchar(120) not null,          -- local slug; also the key the org claim is keyed by,
                                               -- but the claim is parsed at the edge and resolved
                                               -- through external_organization, never trusted as one
    name       varchar(200) not null,
    status     varchar(20)  not null,          -- ACTIVE | SUSPENDED
    version    bigint       not null,
    created_at timestamptz  not null,
    created_by uuid        ,
    updated_at timestamptz,
    updated_by uuid        ,
    deleted_at timestamptz
);

-- Partial, not a unique constraint: a soft-deleted org still occupies its alias, and a total unique
-- would forbid ever re-creating it — the failure V17's header describes as a 409 against a row
-- nobody can see.
create unique index uq_organization_alias_live on organization (alias)
    where deleted_at is null;

create index idx_organization_deleted on organization (deleted_at)
    where deleted_at is not null;

-- ---------------------------------------------------------------------------------------------
-- external_organization — the tenant-side adapter. Same shape as external_identity, same reason.
-- ---------------------------------------------------------------------------------------------
-- `kc_org_id uuid not null unique -- Keycloak organization id (tenant key)` was the org-side twin of
-- app_user.subject, and it was worse, because it escaped its own module: org_role.org_id and
-- membership.org_id were both commented "Keycloak organization id", and every other module copied
-- the value. After this table, organization.id is the only internal tenant key and the Keycloak id is
-- one row here — which is also what lets the edge accept either identifier during a cutover, and what
-- lets the billing adapter keep sending the id the third party already has.
create table external_organization
(
    id              uuid         not null primary key,
    organization_id uuid         not null references organization (id) on delete cascade,
    provider        varchar(20)  not null,        -- same vocabulary as external_identity.provider
    issuer          varchar(300) not null,        -- not null for the NULL-distinctness reason in V10
    external_org_id varchar(255) not null,
    external_alias  varchar(120),
    linked_at       timestamptz  not null,
    version         bigint       not null,
    created_at      timestamptz  not null,
    created_by      uuid        ,
    updated_at      timestamptz,
    updated_by      uuid        ,
    deleted_at      timestamptz
);

-- WHY external_org_id IS varchar AND NOT uuid: kc_org_id was `uuid` only because a Keycloak org id
-- happens to be a UUID — the identical shape-assumption as `subject varchar(64)`. A Google Workspace
-- customer id is 'C01abcdef'. Typing this column to one provider's format would rebuild the coupling
-- inside the table built to remove it.
--
-- WHY on delete cascade HERE but not on org_role/membership below: this row has no independent
-- existence — it is an alias of the organization, the role_permission species. A role or a membership
-- is an aggregate with its own lifecycle, its own soft delete and its own legal-hold check, so its
-- removal must be the purge job's explicit decision and never a side effect of the parent's.

-- The id-keyed resolution (an org claim carrying an id, a provider webhook naming an org).
create unique index uq_external_organization_ext_live
    on external_organization (provider, issuer, external_org_id)
    where deleted_at is null;

-- The ALIAS-keyed resolution, and it is not optional: Keycloak's `organization` claim is a map KEYED
-- BY ALIAS — {"acme":{"id":"…"}} — so the resolver needs alias -> organization as a first-class
-- lookup, not a scan.
create unique index uq_external_organization_alias_live
    on external_organization (provider, issuer, external_alias)
    where external_alias is not null and deleted_at is null;

-- One live link per org per issuer — the org-side twin of uq_external_identity_person_issuer_live.
create unique index uq_external_organization_org_live
    on external_organization (organization_id, provider, issuer)
    where deleted_at is null;

create index idx_external_organization_deleted on external_organization (deleted_at)
    where deleted_at is not null;
