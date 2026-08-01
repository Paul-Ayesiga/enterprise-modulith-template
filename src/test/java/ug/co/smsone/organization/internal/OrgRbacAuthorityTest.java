package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ug.co.smsone.organization.internal.OrgRbacFixtures.MANAGER_PERMISSIONS;
import static ug.co.smsone.organization.internal.OrgRbacFixtures.VIEWER_PERMISSIONS;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.organization.MemberRemoved;
import ug.co.smsone.organization.Permission;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The RBAC authority end-to-end against REAL Postgres: seed the org, attach members, then assert the
 * shared {@link OrgAuthorization} port (which drives {@code ApiPermissionEvaluator}) resolves the right
 * effective permissions — plus strict cross-org isolation.
 *
 * <p>Only {@code OWNER} is seeded. Every other role here is built by the test the way an owner builds
 * one through the API, which is the point: authority comes from the permission set, and a role code is
 * a label the resolver never reads.
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

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ApplicationEventPublisher events;

    private UUID orgId;
    private String owner;
    private String manager;
    private String viewer;

    @BeforeEach
    void seed() {
        orgId = UUID.randomUUID();
        // The resolver grants nothing without an ACTIVE org projection — seed it like production does.
        organizations.save(Organization.register(orgId, "auth-" + orgId, "Auth Org"));
        roleSeeder.seedSystemRoles(orgId);

        owner = attachToSeededOwner("owner-" + UUID.randomUUID());
        manager = attachToNewRole("manager-" + UUID.randomUUID(), "MANAGER", MANAGER_PERMISSIONS);
        viewer = attachToNewRole("viewer-" + UUID.randomUUID(), "VIEWER", VIEWER_PERMISSIONS);
    }

    private String attachToSeededOwner(String subject) {
        return OrgRbacFixtures.attachToSeededOwner(roles, memberships, orgId, subject);
    }

    private String attachToNewRole(String subject, String code, Set<Permission> permissions) {
        return OrgRbacFixtures.attachToNewRole(roles, memberships, orgId, subject, code, permissions);
    }

    @Test
    void aFreshOrganizationHasExactlyOneRole() {
        assertThat(roles.findByOrgId(orgId))
                .filteredOn(Role::isSystemRole)
                .extracting(Role::getCode)
                .containsExactly(Role.OWNER_CODE);
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
    void aCustomRoleGrantsExactlyWhatItWasGiven() {
        assertThat(authorization.hasPermission(manager, orgId, Permission.ORG_DELETE.code())).isFalse();
        assertThat(authorization.hasPermission(manager, orgId, Permission.MEMBER_INVITE.code())).isTrue();
        assertThat(authorization.hasPermission(manager, orgId, Permission.ROLE_CREATE.code())).isTrue();
        assertThat(authorization.hasPermission(manager, orgId, Permission.ORG_SETTINGS_UPDATE.code())).isTrue();
    }

    @Test
    void aReadOnlyRoleResolvesToExactlyItsReadPermissions() {
        assertThat(authorization.permissions(viewer, orgId)).containsExactlyInAnyOrder(
                Permission.ORG_READ.code(),
                Permission.MEMBER_READ.code(),
                Permission.ROLE_READ.code(),
                Permission.ORG_SETTINGS_READ.code());
        assertThat(authorization.hasPermission(viewer, orgId, Permission.MEMBER_INVITE.code())).isFalse();
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_UPDATE.code())).isFalse();
    }

    /**
     * The core invariant of the permission-based model: a role code carries no authority. Two roles
     * with identical permission sets resolve identically, even when one is called {@code ADMIN} — the
     * name that used to be a seeded system role and would be the natural thing for a check to key on.
     */
    @Test
    void aRoleCodeGrantsNothingByItself() {
        String onAdmin = attachToNewRole("looks-privileged-" + UUID.randomUUID(), "ADMIN", VIEWER_PERMISSIONS);
        String onPlain = attachToNewRole("looks-ordinary-" + UUID.randomUUID(), "SOMETHING_ELSE", VIEWER_PERMISSIONS);

        assertThat(authorization.permissions(onAdmin, orgId))
                .isEqualTo(authorization.permissions(onPlain, orgId));
        assertThat(authorization.hasPermission(onAdmin, orgId, Permission.MEMBER_INVITE.code())).isFalse();
        assertThat(authorization.hasPermission(onAdmin, orgId, Permission.ORG_DELETE.code())).isFalse();
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
        Role ownerRole = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow();
        jdbc.update("delete from role_permission where role_id = ? and permission = ?",
                ownerRole.getId(), Permission.ORG_DELETE.name());

        roleSeeder.seedSystemRoles(orgId); // reconciling upsert, not presence-gated

        assertThat(roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getPermissions())
                .contains(Permission.ORG_DELETE);
    }

    /** Reconciliation must not resurrect the roles V16 demoted, nor re-freeze them as system roles. */
    @Test
    void seederLeavesFormerSystemRolesAloneAsCustomRoles() {
        Role legacyAdmin = roles.save(Role.create(orgId, "ADMIN", "Administrator", false, null,
                MANAGER_PERMISSIONS));

        roleSeeder.seedSystemRoles(orgId);

        Role after = roles.findByOrgIdAndCode(orgId, "ADMIN").orElseThrow();
        assertThat(after.getId()).isEqualTo(legacyAdmin.getId());
        assertThat(after.isSystemRole()).isFalse();
        assertThat(after.getPermissions()).isEqualTo(MANAGER_PERMISSIONS);
    }

    /**
     * Soft delete must REVOKE, not merely hide: the membership row survives with {@code deleted_at}
     * set, and the resolver — which reads through {@code @SQLRestriction} — must resolve the subject
     * to nothing. A soft delete that only hid the row from listings while authorization kept passing
     * would be the worst possible outcome of this change.
     */
    @Test
    void aSoftDeletedMembershipResolvesToZeroPermissions() {
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_READ.code())).isTrue();
        UUID membershipId = memberships.findByOrgIdAndUserSubject(orgId, viewer).orElseThrow().getId();

        removeMemberAsProductionDoes(viewer);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.permissions(viewer, orgId)).isEmpty());
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_READ.code())).isFalse();
        assertThat(memberships.findByOrgIdAndUserSubject(orgId, viewer)).isEmpty();
        // The row is still there: access ended because the restriction hides it, not because it was lost.
        assertThat(jdbc.queryForObject(
                "select count(*) from membership where id = ? and deleted_at is not null",
                Integer.class, membershipId)).isEqualTo(1);
    }

    /**
     * The narrow window {@code RoleService.delete} documents: soft delete means the FK no longer
     * backstops the "role still assigned" check, so a membership can outlive the role it points at.
     * The resolver must FAIL CLOSED — the holder loses access rather than keeping a role that nobody
     * can see, list or edit.
     */
    @Test
    void aMemberPointingAtASoftDeletedRoleResolvesToZeroPermissions() {
        Role viewerRole = roles.findByOrgIdAndCode(orgId, "VIEWER").orElseThrow();

        roles.delete(viewerRole);

        assertThat(authorization.permissions(viewer, orgId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from membership where org_id = ? and role_id = ?",
                Integer.class, orgId, viewerRole.getId())).isEqualTo(1); // still points at it
    }

    /**
     * The membership half of the partial-index story: {@code uq_membership_org_user_live} exists so a
     * removal is reversible. Without it, removing someone would permanently forbid ever re-inviting
     * them to that organization — a support ticket nobody could resolve.
     */
    @Test
    void aRemovedMemberCanBeInvitedBackIntoTheSameOrganization() {
        Membership original = memberships.findByOrgIdAndUserSubject(orgId, viewer).orElseThrow();
        removeMemberAsProductionDoes(viewer);

        UUID ownerRoleId = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getId();
        Membership reinvited = memberships.save(
                Membership.create(orgId, viewer, ownerRoleId, Role.OWNER_CODE));

        assertThat(reinvited.getId()).isNotEqualTo(original.getId());
        assertThat(memberships.findByOrgIdAndUserSubject(orgId, viewer).orElseThrow().getId())
                .isEqualTo(reinvited.getId());
        assertThat(jdbc.queryForObject(
                "select count(*) from membership where org_id = ? and user_subject = ?",
                Integer.class, orgId, viewer)).isEqualTo(2); // one dead, one live
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_DELETE.code())).isTrue());
    }

    /**
     * {@code MemberService.remove} in miniature. The explicit publish is the point: a repository delete
     * does NOT fire {@code @DomainEvents}, so without it a removed member keeps their cached
     * permissions until the entry ages out of L2.
     */
    private void removeMemberAsProductionDoes(String subject) {
        transactions.executeWithoutResult(tx -> {
            memberships.delete(memberships.findByOrgIdAndUserSubject(orgId, subject).orElseThrow());
            events.publishEvent(new MemberRemoved(orgId, subject, Instant.now()));
        });
    }

    @Test
    void permissionCacheIsEvictedAfterAMembershipRoleChange() {
        // Prime the cache: on a read-only role, the subject must NOT have org:delete.
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_DELETE.code())).isFalse();

        // Promote to OWNER — assignRole registers MembershipRoleChanged, save() publishes it, and the
        // OrgPermissionCacheEvictor clears 'org-permissions' after commit (async).
        Membership membership = memberships.findByOrgIdAndUserSubject(orgId, viewer).orElseThrow();
        UUID ownerRoleId = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getId();
        membership.assignRole(ownerRoleId);
        memberships.save(membership);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_DELETE.code())).isTrue());
    }
}
