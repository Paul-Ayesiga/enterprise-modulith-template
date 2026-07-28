package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private OrganizationRepository organizations;

    @Autowired
    private OrgAuthorization authorization; // shared port, backed by OrgAuthorizationImpl + PermissionResolver

    @Autowired
    private JdbcTemplate jdbc;

    private UUID orgId;
    private String owner;
    private String admin;
    private String member;

    @BeforeEach
    void seed() {
        orgId = UUID.randomUUID();
        // The resolver grants nothing without an ACTIVE org projection — seed it like production does.
        organizations.save(Organization.register(orgId, "auth-" + orgId, "Auth Org"));
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

    @Test
    void suspendedOrganizationGrantsNothingUntilReactivated() {
        assertThat(authorization.hasPermission(owner, orgId, Permission.ORG_READ.code())).isTrue();

        Organization organization = organizations.findByKcOrgId(orgId).orElseThrow();
        organization.suspend(); // publishes OrganizationStatusChanged -> async cache eviction
        organizations.save(organization);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.permissions(owner, orgId)).isEmpty());

        Organization suspended = organizations.findByKcOrgId(orgId).orElseThrow();
        suspended.reactivate();
        organizations.save(suspended);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.hasPermission(owner, orgId, Permission.ORG_READ.code())).isTrue());
    }

    @Test
    void seederReconcilesADriftedSystemRoleBackToTheCatalog() {
        // Simulate catalog drift (e.g. an enum value shipped after this org was seeded) by removing a
        // permission row out-of-band — the entity API forbids editing system roles on purpose.
        Role ownerRole = roles.findByOrgIdAndCode(orgId, "OWNER").orElseThrow();
        jdbc.update("delete from role_permission where role_id = ? and permission = ?",
                ownerRole.getId(), Permission.ORG_DELETE.name());

        roleSeeder.seedSystemRoles(orgId); // reconciling upsert, not presence-gated

        assertThat(roles.findByOrgIdAndCode(orgId, "OWNER").orElseThrow().getPermissions())
                .contains(Permission.ORG_DELETE);
    }

    @Test
    void permissionCacheIsEvictedAfterAMembershipRoleChange() {
        // Prime the cache: as a MEMBER, the subject must NOT have org:delete.
        assertThat(authorization.hasPermission(member, orgId, Permission.ORG_DELETE.code())).isFalse();

        // Promote to OWNER — assignRole registers MembershipRoleChanged, save() publishes it, and the
        // OrgPermissionCacheEvictor clears 'org-permissions' after commit (async).
        Membership membership = memberships.findByOrgIdAndUserSubject(orgId, member).orElseThrow();
        UUID ownerRoleId = roles.findByOrgIdAndCode(orgId, "OWNER").orElseThrow().getId();
        membership.assignRole(ownerRoleId);
        memberships.save(membership);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.hasPermission(member, orgId, Permission.ORG_DELETE.code())).isTrue());
    }
}
