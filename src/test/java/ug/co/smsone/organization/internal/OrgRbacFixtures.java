package ug.co.smsone.organization.internal;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import ug.co.smsone.organization.Permission;

/**
 * The org/role/membership seed the two RBAC test classes share.
 *
 * <p>{@link OrgRbacApiTest} and {@link OrgRbacAuthorityTest} are a deliberate complementary pair — one
 * asserts the HTTP surface, the other the {@code OrgAuthorization} port — but that pairing is about what
 * each class <em>asserts</em>, not about how it seeds. The seed was byte-identical in both, javadoc
 * included, which meant a change to what "manager" means had to be made twice or the pair would quietly
 * stop testing the same world. Each class still owns its own {@code @BeforeEach} and its own org alias,
 * so a failure still names which class wrote the row.
 */
final class OrgRbacFixtures {

    private OrgRbacFixtures() {
    }

    /** Everything an owner has, minus the one permission that can destroy the tenant. */
    static final Set<Permission> MANAGER_PERMISSIONS = EnumSet.complementOf(
            EnumSet.of(Permission.ORG_DELETE));

    static final Set<Permission> VIEWER_PERMISSIONS = EnumSet.of(
            Permission.ORG_READ, Permission.MEMBER_READ, Permission.ROLE_READ, Permission.ORG_SETTINGS_READ);

    /** The one role {@code RoleSeeder} creates; the subject joins it as a member. */
    static String attachToSeededOwner(RoleRepository roles, MembershipRepository memberships,
            UUID orgId, String subject) {
        Role role = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow();
        memberships.save(Membership.create(orgId, subject, role.getId(), Role.OWNER_CODE));
        return subject;
    }

    /** An owner-created role: {@code system_role=false}, any permission subset, org-scoped. */
    static String attachToNewRole(RoleRepository roles, MembershipRepository memberships,
            UUID orgId, String subject, String code, Set<Permission> permissions) {
        Role role = roles.save(Role.create(orgId, code, code, false, null, permissions));
        memberships.save(Membership.create(orgId, subject, role.getId(), code));
        return subject;
    }
}
