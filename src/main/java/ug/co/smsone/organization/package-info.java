/**
 * Organization module: the tenant itself, the identifiers other systems know it by, and the org-scoped
 * RBAC authority. {@code organization.id} IS the tenant key — the provider's organization id is one row
 * in {@code external_organization}, the org-side twin of identity's {@code external_identity}, and it
 * never leaves this module. Permissions are a fixed {@link ug.co.smsone.organization.Permission}
 * catalog; roles are DB-editable bundles of permissions. {@code OWNER} is the only seeded role and the
 * only code the application names — every other org role is one an owner created, and no request path
 * reads its code. Members are named by {@code person.id}, a soft ref into the identity module.
 *
 * <p>Implements two {@code shared.security} ports: {@code OrgAuthorization}, so
 * {@code @PreAuthorize("hasPermission(#orgId, 'organization', 'member:invite')")} resolves the caller's
 * role in the active org, and {@code OrgLookup}, which turns a token's {@code organization} claim into
 * an {@code organization.id} at the edge. Internals live under {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Organization")
package ug.co.smsone.organization;
