package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The RBAC authority end-to-end against REAL Postgres: seed system roles, attach members, then assert
 * the shared {@link OrgAuthorization} port (which drives {@code ApiPermissionEvaluator}) resolves the
 * right effective permissions per role — plus strict cross-org isolation.
 */
class OrgRbacAuthorityTest extends AbstractIntegrationTest {

    @Autowired
    private RoleSeeder roleSeeder;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private OrgAuthorization authorization; // shared port, backed by OrgAuthorizationImpl + PermissionResolver

    private UUID orgId;
    private String owner;
    private String admin;
    private String member;

    @BeforeEach
    void seed() {
        orgId = UUID.randomUUID();
        roleSeeder.seedSystemRoles(orgId);

        owner = attach("owner-" + UUID.randomUUID(), "OWNER");
        admin = attach("admin-" + UUID.randomUUID(), "ADMIN");
        member = attach("member-" + UUID.randomUUID(), "MEMBER");
    }

    private String attach(String subject, String roleCode) {
        Role role = roles.findByOrgIdAndCode(orgId, roleCode).orElseThrow();
        memberships.save(Membership.create(orgId, subject, role.getId(), roleCode));
        return subject;
    }

    @Test
    void ownerHasEveryPermissionIncludingOrgDelete() {
        for (Permission permission : Permission.values()) {
            assertThat(authorization.hasPermission(owner, orgId, permission.code()))
                    .as("owner should hold %s", permission.code())
                    .isTrue();
        }
    }

    @Test
    void adminHasEverythingExceptOrgDelete() {
        assertThat(authorization.hasPermission(admin, orgId, Permission.ORG_DELETE.code())).isFalse();
        assertThat(authorization.hasPermission(admin, orgId, Permission.MEMBER_INVITE.code())).isTrue();
        assertThat(authorization.hasPermission(admin, orgId, Permission.ROLE_CREATE.code())).isTrue();
        assertThat(authorization.hasPermission(admin, orgId, Permission.ORG_SETTINGS_UPDATE.code())).isTrue();
    }

    @Test
    void memberHasOnlyReadPermissions() {
        assertThat(authorization.permissions(member, orgId)).containsExactlyInAnyOrder(
                Permission.ORG_READ.code(),
                Permission.MEMBER_READ.code(),
                Permission.ROLE_READ.code(),
                Permission.ORG_SETTINGS_READ.code());
        assertThat(authorization.hasPermission(member, orgId, Permission.MEMBER_INVITE.code())).isFalse();
        assertThat(authorization.hasPermission(member, orgId, Permission.ORG_UPDATE.code())).isFalse();
    }

    @Test
    void nonMemberHasNoPermissions() {
        assertThat(authorization.permissions("stranger-" + UUID.randomUUID(), orgId)).isEmpty();
        assertThat(authorization.hasPermission("stranger", orgId, Permission.ORG_READ.code())).isFalse();
    }

    @Test
    void permissionsAreScopedToTheOwningOrganization() {
        UUID otherOrg = UUID.randomUUID();
        // The owner of `orgId` is a stranger in an unrelated org — no leakage across the tenant boundary.
        assertThat(authorization.permissions(owner, otherOrg)).isEmpty();
        assertThat(authorization.hasPermission(owner, otherOrg, Permission.ORG_READ.code())).isFalse();
    }
}
