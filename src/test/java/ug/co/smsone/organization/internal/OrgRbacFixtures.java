package ug.co.smsone.organization.internal;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The org/role/membership seed the two RBAC test classes share.
 *
 * <p>{@link OrgRbacApiTest} and {@link OrgRbacAuthorityTest} are a deliberate complementary pair — one
 * asserts the HTTP surface, the other the {@code OrgAuthorization} port — but that pairing is about what
 * each class <em>asserts</em>, not about how it seeds. The seed was byte-identical in both, javadoc
 * included, which meant a change to what "manager" means had to be made twice or the pair would quietly
 * stop testing the same world. Each class still owns its own {@code @BeforeEach} and its own org alias,
 * so a failure still names which class wrote the row.
 *
 * <p><b>Both helpers pin the org's axis themselves</b> (ADR 0010 §3.4). {@code org_role},
 * {@code role_permission} and {@code membership} are tenant-tier, and the harness pins PLATFORM — which
 * is honest for a test thread, since a test method is not a request and belongs to no tenant. Seeding a
 * tenant's own rows is the one thing on that thread that DOES belong to a tenant, and these two methods
 * are where it happens, in both classes and mid-test as well as in {@code @BeforeEach}. Pinning inside
 * them puts the declaration next to the write instead of at four call sites that would drift.
 */
final class OrgRbacFixtures {

    private OrgRbacFixtures() {
    }

    /** Everything an owner has, minus the one permission that can destroy the tenant. */
    static final Set<Permission> MANAGER_PERMISSIONS = EnumSet.complementOf(
            EnumSet.of(Permission.ORG_DELETE));

    static final Set<Permission> VIEWER_PERMISSIONS = EnumSet.of(
            Permission.ORG_READ, Permission.MEMBER_READ, Permission.ROLE_READ, Permission.ORG_SETTINGS_READ);

    /** The one role {@code RoleSeeder} creates; the person joins it as a member. */
    static UUID attachToSeededOwner(RoleRepository roles, MembershipRepository memberships,
            UUID orgId, UUID personId) {
        return TenantContext.callAs(orgId, () -> {
            Role role = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow();
            memberships.save(Membership.create(orgId, personId, role.getId(), Role.OWNER_CODE));
            return personId;
        });
    }

    /** An owner-created role: {@code system_role=false}, any permission subset, org-scoped. */
    static UUID attachToNewRole(RoleRepository roles, MembershipRepository memberships,
            UUID orgId, UUID personId, String code, Set<Permission> permissions) {
        return TenantContext.callAs(orgId, () -> {
            Role role = roles.save(Role.create(orgId, code, code, false, null, permissions));
            memberships.save(Membership.create(orgId, personId, role.getId(), code));
            return personId;
        });
    }
}
