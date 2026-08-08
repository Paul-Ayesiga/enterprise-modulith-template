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
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The RBAC authority end-to-end against REAL Postgres: seed the org, attach members, then assert the
 * shared {@link OrgAuthorization} port (which drives {@code ApiPermissionEvaluator}) resolves the right
 * effective permissions — plus strict cross-org isolation.
 *
 * <p>Only {@code OWNER} is seeded. Every other role here is built by the test the way an owner builds
 * one through the API, which is the point: authority comes from the permission set, and a role code is
 * a label the resolver never reads.
 *
 * <h2>Two axes on one thread, and the split is what makes the assertions falsifiable (ADR 0010 §3.4)</h2>
 *
 * <p>The harness pins PLATFORM, which is the honest axis for a test method — it is not a request and
 * belongs to no tenant. Two kinds of statement then run on that thread and they are treated
 * differently on purpose:
 *
 * <ul>
 *   <li><b>The fixtures and the direct assertions</b> read and write {@code org_role},
 *       {@code role_permission} and {@code membership} — tenant-tier, addressed bare — so each one says
 *       {@link #callInOrg}/{@link #runInOrg}. This is the test standing in for code inside the tenant,
 *       and it has to declare that the same way production does. {@code organization} is platform-tier
 *       and schema-qualified in its mapping, so every {@code organizations.*} call below is left
 *       alone.</li>
 *   <li><b>Every {@code authorization.*} call is deliberately NOT wrapped.</b> The port takes the org
 *       as an argument and pins its own axis ({@code OrgAuthorizationImpl}), which is precisely the
 *       behaviour the edge depends on — it calls this while still on PLATFORM, because resolving which
 *       tenant a token names is itself a platform read. Wrapping these would hand the port the axis it
 *       is supposed to take for itself and this class would pass with that pin deleted.</li>
 * </ul>
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
    private UUID owner;
    private UUID manager;
    private UUID viewer;

    @BeforeEach
    void seed() {
        // organization.id is Hibernate-assigned at persist and IS the tenant key now, so the seed reads
        // it back off the saved row instead of minting one — there is no provider id to mint. Platform
        // tier, and the mapping names its schema, so this one line needs no axis of its own.
        orgId = organizations.save(Organization.register("auth-" + UUID.randomUUID(), "Auth Org")).getId();
        // org_role and role_permission are the tenant's: the seeder writes them unqualified and only
        // this org's axis says which schema that is.
        runInOrg(() -> roleSeeder.seedSystemRoles(orgId));

        // Members are person ids. membership.person_id is a soft ref with no FK (AGENTS §1), so a bare
        // uuid seeds a member exactly as a provisioned person would — which is the point of the soft ref.
        // The two fixtures pin for themselves; see OrgRbacFixtures.
        owner = attachToSeededOwner(UUID.randomUUID());
        manager = attachToNewRole(UUID.randomUUID(), "MANAGER", MANAGER_PERMISSIONS);
        viewer = attachToNewRole(UUID.randomUUID(), "VIEWER", VIEWER_PERMISSIONS);
    }

    private UUID attachToSeededOwner(UUID personId) {
        return OrgRbacFixtures.attachToSeededOwner(roles, memberships, orgId, personId);
    }

    private UUID attachToNewRole(UUID personId, String code, Set<Permission> permissions) {
        return OrgRbacFixtures.attachToNewRole(roles, memberships, orgId, personId, code, permissions);
    }

    /**
     * Reads or writes THIS organization's own tables — {@code org_role}, {@code role_permission},
     * {@code membership}. They are tenant-tier and addressed bare (ADR 0010 §2), so on the harness's
     * PLATFORM axis they do not resolve to the wrong rows, they resolve to nothing:
     * {@code relation "org_role" does not exist}. See the class note for why the {@code authorization.*}
     * calls are pointedly not routed through here.
     */
    private <T> T callInOrg(java.util.function.Supplier<T> work) {
        return TenantContext.callAs(orgId, work);
    }

    /** @see #callInOrg — the same declaration for a statement that returns nothing. */
    private void runInOrg(Runnable work) {
        TenantContext.runAs(orgId, work);
    }

    /**
     * The two reads every {@code await()} below makes. They carry no pin, and that is the assertion.
     *
     * <p>Awaitility polls on ITS OWN thread, and that thread is not the one the harness pinned
     * (ADR 0010 §3.4): work arrives there with no axis at all, which routes to the empty
     * {@code no_tenant} schema. Before Phase 2 that made these two calls impossible without a pin
     * around them, and they had one. They no longer need it, because {@code OrgAuthorizationImpl} takes
     * the axis itself from the org id in its own signature — so what these helpers now pin down is that
     * the port is self-sufficient on a pooled thread that declared nothing, which is exactly the
     * situation a scheduler puts it in. Give them a pin back and that stops being tested.
     */
    private Set<String> awaitedPermissions(UUID personId) {
        return authorization.permissions(personId, orgId);
    }

    /** @see #awaitedPermissions */
    private boolean awaitedHasPermission(UUID personId, Permission permission) {
        return authorization.hasPermission(personId, orgId, permission.code());
    }

    @Test
    void aFreshOrganizationHasExactlyOneRole() {
        assertThat(callInOrg(() -> roles.findByOrgId(orgId)))
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
        UUID onAdmin = attachToNewRole(UUID.randomUUID(), "ADMIN", VIEWER_PERMISSIONS);
        UUID onPlain = attachToNewRole(UUID.randomUUID(), "SOMETHING_ELSE", VIEWER_PERMISSIONS);

        assertThat(authorization.permissions(onAdmin, orgId))
                .isEqualTo(authorization.permissions(onPlain, orgId));
        assertThat(authorization.hasPermission(onAdmin, orgId, Permission.MEMBER_INVITE.code())).isFalse();
        assertThat(authorization.hasPermission(onAdmin, orgId, Permission.ORG_DELETE.code())).isFalse();
    }

    @Test
    void nonMemberHasNoPermissions() {
        assertThat(authorization.permissions(UUID.randomUUID(), orgId)).isEmpty();
        assertThat(authorization.hasPermission(UUID.randomUUID(), orgId, Permission.ORG_READ.code())).isFalse();
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

        Organization organization = organizations.findById(orgId).orElseThrow();
        organization.suspend(); // publishes OrganizationStatusChanged -> async cache eviction
        organizations.save(organization);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(awaitedPermissions(owner)).isEmpty());

        Organization suspended = organizations.findById(orgId).orElseThrow();
        suspended.reactivate();
        organizations.save(suspended);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(awaitedHasPermission(owner, Permission.ORG_READ)).isTrue());
    }

    @Test
    void seederReconcilesADriftedSystemRoleBackToTheCatalog() {
        // Simulate catalog drift (e.g. an enum value shipped after this org was seeded) by removing a
        // permission row out-of-band — the entity API forbids editing system roles on purpose.
        // Every statement here is the tenant's own — the role, its permission rows, and the reconciling
        // seed that repairs them — so one declaration covers the whole scenario.
        runInOrg(() -> {
            Role ownerRole = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow();
            jdbc.update("delete from role_permission where role_id = ? and permission = ?",
                    ownerRole.getId(), Permission.ORG_DELETE.name());

            roleSeeder.seedSystemRoles(orgId); // reconciling upsert, not presence-gated

            assertThat(roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getPermissions())
                    .contains(Permission.ORG_DELETE);
        });
    }

    /** Reconciliation must not resurrect the roles V16 demoted, nor re-freeze them as system roles. */
    @Test
    void seederLeavesFormerSystemRolesAloneAsCustomRoles() {
        runInOrg(() -> {
            Role legacyAdmin = roles.save(Role.create(orgId, "ADMIN", "Administrator", false, null,
                    MANAGER_PERMISSIONS));

            roleSeeder.seedSystemRoles(orgId);

            Role after = roles.findByOrgIdAndCode(orgId, "ADMIN").orElseThrow();
            assertThat(after.getId()).isEqualTo(legacyAdmin.getId());
            assertThat(after.isSystemRole()).isFalse();
            assertThat(after.getPermissions()).isEqualTo(MANAGER_PERMISSIONS);
        });
    }

    /**
     * Soft delete must REVOKE, not merely hide: the membership row survives with {@code deleted_at}
     * set, and the resolver — which reads through {@code @SQLRestriction} — must resolve the person
     * to nothing. A soft delete that only hid the row from listings while authorization kept passing
     * would be the worst possible outcome of this change.
     */
    @Test
    void aSoftDeletedMembershipResolvesToZeroPermissions() {
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_READ.code())).isTrue();
        UUID membershipId = callInOrg(() ->
                memberships.findByOrgIdAndPersonId(orgId, viewer).orElseThrow().getId());

        removeMemberAsProductionDoes(viewer);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(awaitedPermissions(viewer)).isEmpty());
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_READ.code())).isFalse();
        assertThat(callInOrg(() -> memberships.findByOrgIdAndPersonId(orgId, viewer))).isEmpty();
        // The row is still there: access ended because the restriction hides it, not because it was lost.
        assertThat(callInOrg(() -> jdbc.queryForObject(
                "select count(*) from membership where id = ? and deleted_at is not null",
                Integer.class, membershipId))).isEqualTo(1);
    }

    /**
     * The narrow window {@code RoleService.delete} documents: soft delete means the FK no longer
     * backstops the "role still assigned" check, so a membership can outlive the role it points at.
     * The resolver must FAIL CLOSED — the holder loses access rather than keeping a role that nobody
     * can see, list or edit.
     */
    @Test
    void aMemberPointingAtASoftDeletedRoleResolvesToZeroPermissions() {
        UUID viewerRoleId = callInOrg(() -> {
            Role viewerRole = roles.findByOrgIdAndCode(orgId, "VIEWER").orElseThrow();
            roles.delete(viewerRole);
            return viewerRole.getId();
        });

        assertThat(authorization.permissions(viewer, orgId)).isEmpty();
        assertThat(callInOrg(() -> jdbc.queryForObject(
                "select count(*) from membership where org_id = ? and role_id = ?",
                Integer.class, orgId, viewerRoleId))).isEqualTo(1); // still points at it
    }

    /**
     * The membership half of the partial-index story: {@code uq_membership_org_user_live} exists so a
     * removal is reversible. Without it, removing someone would permanently forbid ever re-inviting
     * them to that organization — a support ticket nobody could resolve.
     */
    @Test
    void aRemovedMemberCanBeInvitedBackIntoTheSameOrganization() {
        UUID originalId = callInOrg(() ->
                memberships.findByOrgIdAndPersonId(orgId, viewer).orElseThrow().getId());
        removeMemberAsProductionDoes(viewer);

        UUID reinvitedId = callInOrg(() -> {
            UUID ownerRoleId = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getId();
            return memberships.save(Membership.create(orgId, viewer, ownerRoleId, Role.OWNER_CODE)).getId();
        });

        assertThat(reinvitedId).isNotEqualTo(originalId);
        assertThat(callInOrg(() -> memberships.findByOrgIdAndPersonId(orgId, viewer).orElseThrow().getId()))
                .isEqualTo(reinvitedId);
        assertThat(callInOrg(() -> jdbc.queryForObject(
                "select count(*) from membership where org_id = ? and person_id = ?",
                Integer.class, orgId, viewer))).isEqualTo(2); // one dead, one live
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(awaitedHasPermission(viewer, Permission.ORG_DELETE)).isTrue());
    }

    /**
     * {@code MemberService.remove} in miniature. The explicit publish is the point: a repository delete
     * does NOT fire {@code @DomainEvents}, so without it a removed member keeps their cached
     * permissions until the entry ages out of L2.
     *
     * <p>The pin is OUTSIDE the transaction and has to be: the schema is chosen when the connection is
     * borrowed, so declaring the axis inside would be a no-op at best and {@code TenantContext} refuses
     * it outright (ADR 0010 §3.2). Same ordering {@code MemberService.remove} follows.
     */
    private void removeMemberAsProductionDoes(UUID personId) {
        runInOrg(() -> transactions.executeWithoutResult(tx -> {
            memberships.delete(memberships.findByOrgIdAndPersonId(orgId, personId).orElseThrow());
            events.publishEvent(new MemberRemoved(orgId, personId, Instant.now()));
        }));
    }

    @Test
    void permissionCacheIsEvictedAfterAMembershipRoleChange() {
        // Prime the cache: on a read-only role, the person must NOT have org:delete.
        assertThat(authorization.hasPermission(viewer, orgId, Permission.ORG_DELETE.code())).isFalse();

        // Promote to OWNER — assignRole registers MembershipRoleChanged, save() publishes it, and the
        // OrgPermissionCacheEvictor clears 'org-permissions' after commit (async).
        runInOrg(() -> {
            Membership membership = memberships.findByOrgIdAndPersonId(orgId, viewer).orElseThrow();
            UUID ownerRoleId = roles.findByOrgIdAndCode(orgId, Role.OWNER_CODE).orElseThrow().getId();
            membership.assignRole(ownerRoleId);
            memberships.save(membership);
        });

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(awaitedHasPermission(viewer, Permission.ORG_DELETE)).isTrue());
    }
}
