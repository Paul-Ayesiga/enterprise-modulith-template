package ug.co.smsone.organization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.organization.OrganizationRegistered;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.placement.PlacementState;
import ug.co.smsone.shared.tenancy.placement.TenantPlacement;
import ug.co.smsone.shared.tenancy.placement.TenantPlacements;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Creating a tenant under the POOLED policy (ADR 0010 §4.3), which is the case that has to cost
 * nothing: {@code tenant_pool} already exists, so there is no DDL, no {@code PROVISIONING} state and no
 * second transaction — the registration is exactly the single atomic write it was before Phase 4, with
 * one more row in it.
 *
 * <p><b>The policy is pinned rather than inherited, and that is a correctness fix rather than a
 * style.</b> This class asserts {@code schemaName() == "tenant_pool"} on a tenant it creates, which is
 * a statement about ONE policy and not about whichever one happens to be default. It previously relied
 * on the default being pooled and broke the moment that moved — silently in the sense that the failure
 * says "expected tenant_pool but was t_9f3a…", which reads as a routing bug rather than as a test
 * asserting the wrong policy. Both policies now have a class that names the one it is about.
 *
 * <p>It drives {@link OrgProjectionWriter} rather than {@code OrganizationService.create} because
 * under this policy those two differ only by the Keycloak and identity calls that create a provider
 * organization and an owner account. Everything Phase 4 added — the placement row, and the decision to
 * publish {@code OrganizationRegistered} — lives in the local write, and driving that directly is what
 * lets this class run without a single mock against the shared container.
 *
 * <p>{@link SiloTenantProvisioningTest} is the other half: the policy where the ordering is not free.
 */
@TestPropertySource(properties = "app.tenancy.placement.policy=pooled")
class TenantPlacementOnCreateTest extends AbstractIntegrationTest {

    private static final String REGISTERED = OrganizationRegistered.class.getName();

    @Autowired
    private OrgProjectionWriter projectionWriter;

    @Autowired
    private TenantPlacements placements;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    /**
     * The row that says where this tenant lives exists as soon as the tenant does, and says
     * {@code tenant_pool}.
     *
     * <p>Partial-fleet state is only a queryable fact if the registry knows about every tenant. A
     * placement written by anything later — a listener, a nightly reconciler — would leave a window in
     * which a tenant exists and its home is unrecorded, and a crash inside that window would leave a
     * tenant nothing could place.
     */
    @Test
    void aNewOrganizationGetsAnActivePooledPlacementAndIsAnnouncedExactlyOnce() {
        String alias = "placed-" + UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createTenant(alias);

        TenantPlacement placement = placements.find(orgId).orElseThrow(
                () -> new AssertionError("a tenant with no placement row is a tenant nothing can route"));
        assertThat(placement.schemaName()).isEqualTo("tenant_pool");
        assertThat(placement.dataSourceName()).isEqualTo(TenantPlacement.PRIMARY_DATASOURCE);
        assertThat(placement.state()).isEqualTo(PlacementState.ACTIVE);
        assertThat(announcements(orgId)).isEqualTo(1);
    }

    /**
     * Re-adopting a tenant that already exists — the path a Keycloak organization takes when it
     * survives a local database reset — must not announce it a second time.
     *
     * <p>This is the case that used to be answered by "did Hibernate just insert the row?", and it is
     * the case that answer got right. What it could not get right is the opposite one: a tenant whose
     * row was written and whose announcement never happened. The registry answers both, because ACTIVE
     * is a durable record of the announcement rather than a fact about one call stack — and a duplicate
     * would mean a second trial, a second billing account and a second search document.
     */
    @Test
    void reAdoptingAnExistingTenantAnnouncesNothing() {
        String alias = "readopt-" + UUID.randomUUID().toString().substring(0, 8);
        UUID orgId = createTenant(alias);
        assertThat(announcements(orgId)).isEqualTo(1);

        UUID again = createTenant(alias);

        assertThat(again).as("the same tenant, found by its provider link").isEqualTo(orgId);
        assertThat(announcements(orgId))
                .as("OrganizationRegistered fans out to a trial, a billing account and a search"
                        + " document — a second one is a second of each")
                .isEqualTo(1);
    }

    /**
     * The pooled path's promise, and the only way to actually prove it: <strong>roll the transaction
     * back and watch the placement go with it.</strong>
     *
     * <p>A test that merely finds both rows afterwards cannot tell one transaction from two that both
     * happened to succeed — which is exactly the difference that matters, because the failure this
     * guards against is a crash between them. Visible inside the transaction and gone after the
     * rollback is the pair of observations only a single atomic write produces, and the announcement
     * unwinding with it is the third: {@code OrganizationRegistered} is published into the same
     * transaction, so a tenant that never existed was never announced either.
     */
    @Test
    void thePlacementAndTheAnnouncementBelongToTheTenantsOwnTransaction() {
        String alias = "atomic-" + UUID.randomUUID().toString().substring(0, 8);
        String externalOrgId = "kc-" + alias;
        UUID[] orgId = new UUID[1];
        boolean[] placedInside = new boolean[1];

        TenantContext.runAs(projectionWriter.tenantAxisOf(externalOrgId, alias), () ->
                transactions.executeWithoutResult(tx -> {
                    Organization organization = projectionWriter.projectWithOwner(
                            externalOrgId, alias, "Org " + alias, UUID.randomUUID());
                    orgId[0] = organization.getId();
                    placedInside[0] = placements.find(organization.getId()).isPresent();
                    tx.setRollbackOnly();
                }));

        assertThat(placedInside[0]).as("the placement is written by the tenant's own write").isTrue();
        assertThat(placements.find(orgId[0]))
                .as("and unwinds with it — a placement that outlived a rolled-back tenant would place"
                        + " an organization that does not exist")
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from platform.organization where id = ?", Integer.class, orgId[0]))
                .isZero();
        assertThat(announcements(orgId[0]))
                .as("a tenant that never existed was never announced")
                .isZero();
    }

    /**
     * The local half of what {@code OrganizationService} does, minus the two provider calls: pin the
     * axis outside the transaction (the schema is chosen when the connection is borrowed —
     * {@code TenantContext} throws if this is done inside), then write. The owner's person id is a
     * soft ref with no foreign key, so a random one is a real one here.
     */
    private UUID createTenant(String alias) {
        String externalOrgId = "kc-" + alias;
        return TenantContext.callAs(projectionWriter.tenantAxisOf(externalOrgId, alias),
                () -> projectionWriter.projectWithOwner(externalOrgId, alias, "Org " + alias,
                        UUID.randomUUID()).getId());
    }

    /**
     * How many times this tenant was announced, read from the durable outbox rather than from a
     * listener the test installed: {@code SearchEventListeners} takes {@code OrganizationRegistered}
     * with {@code @ApplicationModuleListener}, so every publication leaves a row in
     * {@code platform.event_publication}. Counting those counts announcements that actually happened,
     * including the ones a listener would have missed by not being registered yet.
     *
     * <p><strong>Grouped by {@code listener_id}, because a publication leaves one row per listener and
     * not one row.</strong> Modulith stores one for every AFTER_COMMIT transactional listener of the
     * event, so {@code count(*)} answers "listeners x announcements" and reads 2 the day a second
     * listener takes {@code OrganizationRegistered} — a green test turning red for a change that broke
     * nothing. Each listener sees every publication, so the largest group IS the number of
     * publications. Kept term for term with {@code SiloTenantProvisioningTest.announcements}, whose
     * javadoc carries the full trap: that class has a second listener today, and reads 2 without this.
     */
    private int announcements(UUID orgId) {
        Integer count = jdbc.queryForObject("""
                select coalesce(max(seen), 0) from (
                       select count(*) as seen from platform.event_publication
                        where event_type = ? and serialized_event like ?
                        group by listener_id) per_listener
                """, Integer.class, REGISTERED, "%" + orgId + "%");
        return count == null ? 0 : count;
    }
}
