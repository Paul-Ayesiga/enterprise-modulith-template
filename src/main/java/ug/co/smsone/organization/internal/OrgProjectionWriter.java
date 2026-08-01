package ug.co.smsone.organization.internal;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the local organization projection atomically: the org row, its seeded system roles, and the
 * first OWNER membership all commit together or not at all. A separate bean so the {@code @Transactional}
 * boundary is applied by the proxy (a self-invoked {@code @Transactional} method would be a no-op).
 * Called only after the Keycloak-side steps succeed, so a mid-flight failure leaves no partial local state.
 */
@Component
class OrgProjectionWriter {

    private final OrganizationRepository organizations;
    private final RoleSeeder roleSeeder;
    private final RoleRepository roles;
    private final MembershipRepository memberships;

    OrgProjectionWriter(OrganizationRepository organizations, RoleSeeder roleSeeder,
            RoleRepository roles, MembershipRepository memberships) {
        this.organizations = organizations;
        this.roleSeeder = roleSeeder;
        this.roles = roles;
        this.memberships = memberships;
    }

    @Transactional
    Organization projectWithOwner(UUID kcOrgId, String alias, String name, String ownerSubject) {
        Organization organization = organizations.findByKcOrgId(kcOrgId)
                .orElseGet(() -> organizations.save(Organization.register(kcOrgId, alias, name)));
        roleSeeder.seedSystemRoles(kcOrgId); // idempotent; joins this transaction
        Role ownerRole = roles.findByOrgIdAndCode(kcOrgId, Role.OWNER_CODE).orElseThrow();
        memberships.findByOrgIdAndUserSubject(kcOrgId, ownerSubject)
                .orElseGet(() -> memberships.save(
                        Membership.create(kcOrgId, ownerSubject, ownerRole.getId(), Role.OWNER_CODE)));
        return organization;
    }
}
