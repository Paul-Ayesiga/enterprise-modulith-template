/**
 * Organization module: a projection of Keycloak Organizations plus the org-scoped RBAC authority.
 * Permissions are a fixed {@link ug.co.smsone.organization.Permission} catalog; roles are DB-editable
 * bundles of permissions. {@code OWNER} is the only seeded role and the only code the application
 * names — every other org role is one an owner created, and no request path reads its code. Implements the
 * {@code shared.security.OrgAuthorization} port so {@code @PreAuthorize("hasPermission(#orgId,
 * 'organization', 'member:invite')")} resolves the caller's role in the active org. Internals live
 * under {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Organization")
package ug.co.smsone.organization;
