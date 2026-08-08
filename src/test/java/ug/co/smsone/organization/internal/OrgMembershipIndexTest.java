package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * {@code platform.org_membership_index} against real Postgres: the routing row and the membership row
 * are written by one transaction and cannot disagree, and when something outside that transaction
 * makes them disagree anyway, the reconciler is what puts it back.
 *
 * <p>The two directions of drift are tested separately because they fail differently. An index row
 * with no membership is <em>ugly</em> — the switcher offers an organization that then denies
 * everything, because authorization reads the tenant and never this table. A membership with no index
 * row is <em>invisible</em> — the member simply cannot find their organization, and there is no other
 * route to it. Only the second one is a real outage, and it is the one the reconciler mostly repairs.
 */
class OrgMembershipIndexTest extends AbstractIntegrationTest {

    @Autowired
    private OrgMembershipIndex index;

    @Autowired
    private OrgMembershipIndexReconciler reconciler;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private MemberService members;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The guarantee the design rests on, proved the only way that actually proves it: roll the
     * transaction back and require BOTH rows to be gone. An index write that had been moved after the
     * commit — the shape this is defending against — would leave the routing row behind and pass any
     * assertion that only checked the happy path.
     */
    @Test
    void theRoutingRowAndTheMembershipCommitAndRollBackTogether() {
        UUID orgId = seedOrg();
        UUID personId = UUID.randomUUID();
        seedRole(orgId, "MEMBER");

        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(tx -> {
            members.saveMembership(orgId, personId, "MEMBER");
            // Both rows exist inside the transaction — so the rollback below is testing the rollback,
            // not testing that nothing was ever written.
            assertThat(indexRows(personId, orgId)).isOne();
            tx.setRollbackOnly();
        }));

        assertThat(indexRows(personId, orgId)).isZero();
        assertThat(TenantContext.callAs(orgId, () -> memberships.findByOrgIdAndPersonId(orgId, personId)))
                .isEmpty();
    }

    /** The ordinary path: a seat created, then ended, leaves nothing behind for the switcher to offer. */
    @Test
    void aSeatIsIndexedWhenItIsCreatedAndForgottenWhenItEnds() {
        UUID orgId = seedOrg();
        UUID personId = UUID.randomUUID();
        seedRole(orgId, "MEMBER");

        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(
                tx -> members.saveMembership(orgId, personId, "MEMBER")));
        assertThat(index.seatsOf(personId, MembershipStatus.ACTIVE))
                .extracting(OrgMembershipIndex.Seat::orgId)
                .containsExactly(orgId);

        TenantContext.runAs(orgId, () -> members.remove(orgId, personId));
        assertThat(index.seatsOf(personId, MembershipStatus.ACTIVE)).isEmpty();
    }

    /**
     * The residue direction. {@code SoftDeletePurgeJob} hard-deletes aged-out memberships in raw SQL —
     * it has to, because {@code @SQLRestriction} hides those rows from JPA entirely — and raw SQL
     * knows nothing about this projection. Simulated exactly that way, because that is exactly what
     * happens at 04:00.
     */
    @Test
    void theReconcilerDropsARoutingRowWhoseMembershipWasHardDeletedUnderIt() {
        UUID orgId = seedOrg();
        UUID personId = UUID.randomUUID();
        seedRole(orgId, "MEMBER");
        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(
                tx -> members.saveMembership(orgId, personId, "MEMBER")));

        TenantContext.runAs(orgId, () -> jdbc.update(
                "delete from membership where org_id = ? and person_id = ?", orgId, personId));
        assertThat(indexRows(personId, orgId)).as("the raw delete leaves the projection behind").isOne();

        assertThat(reconciler.reconcileOrg(orgId)[1]).isOne();
        assertThat(indexRows(personId, orgId)).isZero();
    }

    /**
     * The harmful direction, and the one that has to work on the very first night: every membership
     * that existed before this table did has no routing row, and no migration could have given it one
     * — the platform migration that creates the table runs before {@code tenant_pool} exists, and
     * after Phase 5 a backfill would have to reach into every silo. The insert arm IS the backfill.
     */
    @Test
    void theReconcilerBackfillsAMembershipTheIndexNeverLearnedAbout() {
        UUID orgId = seedOrg();
        UUID personId = UUID.randomUUID();
        UUID roleId = seedRole(orgId, "MEMBER");
        // Written the way a pre-index membership was written: the tenant row alone, nothing else.
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into membership (id, org_id, person_id, role_id, status, version, created_at)
                values (?, ?, ?, ?, 'ACTIVE', 0, now())
                """, UUID.randomUUID(), orgId, personId, roleId));
        assertThat(indexRows(personId, orgId)).isZero();

        assertThat(reconciler.reconcileOrg(orgId)[0]).isOne();
        assertThat(index.seatsOf(personId, MembershipStatus.ACTIVE))
                .extracting(OrgMembershipIndex.Seat::orgId).containsExactly(orgId);

        // Idempotent, and it costs nothing the second time: the `is distinct from` guard means an
        // already-correct row is not rewritten, which is what keeps a nightly no-op from churning the
        // whole table into dead tuples.
        assertThat(reconciler.reconcileOrg(orgId)).containsExactly(0, 0);
    }

    /**
     * A soft-deleted membership is not a seat. The reconciler keys on liveness rather than existence
     * for the tenant side — the row is still there, waiting for retention or for
     * {@code SoftDeleteRecovery} — and the switcher must not offer an organization the person was
     * removed from just because the row has not aged out yet.
     */
    @Test
    void aSoftDeletedMembershipIsNotASeat() {
        UUID orgId = seedOrg();
        UUID personId = UUID.randomUUID();
        UUID roleId = seedRole(orgId, "MEMBER");
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into membership (id, org_id, person_id, role_id, status, version, created_at,
                                        deleted_at)
                values (?, ?, ?, ?, 'ACTIVE', 0, now(), now())
                """, UUID.randomUUID(), orgId, personId, roleId));

        assertThat(reconciler.reconcileOrg(orgId)).containsExactly(0, 0);
        assertThat(index.seatsOf(personId, MembershipStatus.ACTIVE)).isEmpty();
    }

    /**
     * The rows a per-organization loop can never reach, because the organization it would loop over is
     * gone. Existence, not liveness: a SOFT-deleted organization keeps its routing rows, since its
     * memberships are still live underneath it and an un-delete restores a working org — the switcher
     * filters it out at render time instead, through {@code @SQLRestriction} on the organization
     * itself. One authority per fact.
     */
    @Test
    void routingRowsForAnOrganizationThatNoLongerExistsAreSweptSeparately() {
        UUID vanished = UUID.randomUUID(); // never inserted into platform.organization at all
        UUID softDeleted = seedOrg();
        UUID personId = UUID.randomUUID();
        jdbc.update("update platform.organization set deleted_at = now() where id = ?", softDeleted);
        index.record(vanished, personId, MembershipStatus.ACTIVE);
        index.record(softDeleted, personId, MembershipStatus.ACTIVE);

        // reconcile() is @SchedulerLock'd and the advice fires on a direct call too, so a sibling test
        // that already ran it in this context would still hold the 30-minute lease and this call would
        // silently do nothing — and every assertion below would pass against an untouched database.
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' "
                + "where name = ?", "org-membership-index-reconcile");
        // The scheduled entry point: platform axis, orphan sweep, then the per-org pass.
        reconciler.reconcile();

        assertThat(indexRows(personId, vanished)).isZero();
        assertThat(indexRows(personId, softDeleted))
                .as("a soft-deleted organization is restorable and keeps its routing rows").isOne();
    }

    /**
     * The index and the membership are now two separate facts, and this is the state that proves it:
     * the routing row gone, the membership perfectly alive. It is also the exact state
     * {@code SoftDeleteRecovery.restore} produces, and the one a person notices — they cannot see an
     * organization they really are in, and {@code GET /me/organizations} is the only route to it.
     *
     * <p>What the endpoint does with that state is asserted where the endpoint is: {@code ProfileApiTest
     * .aDualMemberSeesBothOrganizationsWithTheirRoleInEach} drives the real HTTP surface for a person
     * seated in two organizations. This one owns the repair.
     */
    @Test
    void forgettingTheRoutingRowHidesTheSeatWithoutTouchingTheMembership() {
        UUID orgId = seedOrg();
        UUID personId = UUID.randomUUID();
        seedRole(orgId, "MEMBER");
        TenantContext.runAs(orgId, () -> transactions.executeWithoutResult(
                tx -> members.saveMembership(orgId, personId, "MEMBER")));

        index.forget(orgId, personId);

        assertThat(index.seatsOf(personId, MembershipStatus.ACTIVE)).isEmpty();
        assertThat(TenantContext.callAs(orgId, () -> memberships.findByOrgIdAndPersonId(orgId, personId)))
                .as("the membership itself is untouched — only the router forgot").isPresent();
        // And the reconciler is what closes the gap the forget() opened.
        assertThat(reconciler.reconcileOrg(orgId)[0]).isOne();
    }

    private int indexRows(UUID personId, UUID orgId) {
        Integer rows = jdbc.queryForObject(
                "select count(*) from platform.org_membership_index where person_id = ? and org_id = ?",
                Integer.class, personId, orgId);
        return rows == null ? 0 : rows;
    }

    /** An organization row only — no provider link, because nothing here resolves a token. */
    private UUID seedOrg() {
        UUID orgId = UUID.randomUUID();
        jdbc.update("""
                insert into platform.organization (id, alias, name, status, version, created_at)
                values (?, ?, 'Index Co', 'ACTIVE', 0, now())
                """, orgId, "idx-" + orgId.toString().substring(0, 8));
        return orgId;
    }

    /** A role in the tenant's own schema, which is why it is written on the tenant's axis. */
    private UUID seedRole(UUID orgId, String code) {
        UUID roleId = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into org_role (id, org_id, code, name, system_role, version, created_at)
                values (?, ?, ?, ?, false, 0, now())
                """, roleId, orgId, code, code));
        return roleId;
    }
}
