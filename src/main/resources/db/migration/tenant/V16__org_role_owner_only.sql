-- Org roles reduce to a single seeded system role: OWNER. ADMIN and MEMBER shipped as immutable
-- system roles; they become ordinary custom roles the owner may rename, re-permission or delete.
--
-- Flipped, not deleted: membership.role_id references org_role(id), so removing these rows would
-- orphan real members (the FK would refuse it, and forcing it would revoke access silently). Existing
-- members keep exactly the permissions they had — this changes who may EDIT the role, not what it
-- grants. An org that never used them can now delete them; RoleService already refuses to delete a
-- role that still has members.
--
-- One-way by design: RoleSeeder no longer defines ADMIN/MEMBER, so nothing re-creates or re-reconciles
-- them. A rollback would need the old seeder back, not just an inverse UPDATE.
-- updated_by is NULL, not a 'flyway:V16' marker: the column is uuid holding person.id, and a schema
-- migration is not a person. That is the same rule AuditLogImpl applies to audit_log.actor — a null
-- actor IS the statement "no human did this". Which migration touched the row is answerable from
-- flyway_schema_history and updated_at, which is where that fact belongs.

update org_role
set system_role = false,
    updated_at  = now(),
    updated_by  = null
where system_role = true
  and code in ('ADMIN', 'MEMBER');
