package ug.co.smsone.subscription.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantRoutes;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <strong>ADR 0010 §6 item 4, in the form it actually takes on a restored deployment — and the line
 * between what a boot check may refuse and what it may only say.</strong>
 *
 * <p>The document describes the missing-catalogue failure as two opposite symptoms with no error: from
 * an empty entitlement map {@code limitOf} returns null and {@code requireWithinLimit} does nothing, so
 * every quota is unlimited, while {@code hasFeature} is {@code containsKey} and is false for every key,
 * so every {@code requireFeature} 403s. Both are real and both are in this package.
 *
 * <p>But neither is what an extracted deployment usually gets, because it does not boot with an empty
 * {@code plan} table: {@code PlanSeeder} is an {@code ApplicationRunner} and creates FREE / PRO /
 * ENTERPRISE on every start — with <em>new UUIDs</em>. A tenant restored without the catalogue snapshot
 * therefore carries an {@code org_subscription.plan_id} that matches nothing,
 * {@code EntitlementResolver} falls through to {@code findByCode("FREE")}, and an ENTERPRISE tenant is
 * served the free entitlements. Not unlimited, not 403 — <em>downgraded</em>, with no error, no log line
 * and no failing request to investigate.
 *
 * <p>{@code org_subscription.plan_id → plan.id} is one of the five foreign keys ADR 0010 §6 had to cut,
 * so the database will not catch it. {@code PlanCatalogGuard} is what the constraint used to be, and
 * this is the test that it still is.
 *
 * <h2>Half of these tests are about what the guard must NOT do</h2>
 *
 * <p>The refusal runs on {@code ApplicationReadyEvent}, and {@code SpringApplication.run} rethrows what
 * that listener throws — so every throw here is <em>every pod refuses to start</em>. That is correct for
 * a deployment which cannot serve anybody and catastrophic for a fact about one tenant out of five
 * thousand, which is why three of the tests below assert an absence of a throw as carefully as the first
 * one asserts its presence.
 */
class PlanCatalogGuardTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private PlanCatalogGuard guard;

    @Autowired
    private JdbcTemplate jdbc;

    /** A healthy installation passes, which is what makes the failing case mean something. */
    @Test
    void anInstallationWhoseSubscriptionsAllResolveStartsCleanly() {
        assertThatCode(() -> guard.everySubscriptionNamesAPlanThisInstallationHas())
                .doesNotThrowAnyException();
    }

    /**
     * One subscription naming a plan the catalogue does not hold fails STARTUP, naming the plan. A
     * deployment serving a silently downgraded tenant is worse than one that will not start, because the
     * second one gets fixed.
     *
     * <p>{@code tenant_pool} is the schema every unpromoted tenant shares, so a dangling plan there is
     * about this binary's own shared home rather than about one tenant's silo — the same scope
     * {@code MappedSchemaValidator}'s tenant pass takes, and the reason this arm refuses unconditionally
     * where {@link #aDanglingPlanInOneSiloOfAFleetIsReportedRatherThanFailingEveryPodsBoot()} does not.
     *
     * <p>The row is removed in a {@code finally} and that is not tidiness: this check runs on
     * {@code ApplicationReadyEvent}, so a dangling row left behind would fail the boot of every Spring
     * context the rest of the suite builds, in a file that names neither this test nor the row.
     */
    @Test
    void aSubscriptionNamingAPlanTheCatalogueDoesNotHoldRefusesToStart() {
        UUID orgId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into org_subscription (id, org_id, plan_id, status, version, created_at)
                values (?, ?, ?, 'ACTIVE', 0, now())
                """, UUID.randomUUID(), orgId, missing));
        try {
            assertThatThrownBy(() -> guard.everySubscriptionNamesAPlanThisInstallationHas())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(missing.toString())
                    .hasMessageContaining("catalogue snapshot (ADR 0010 §6 item 4)");
        } finally {
            TenantContext.runAs(orgId,
                    () -> jdbc.update("delete from org_subscription where org_id = ?", orgId));
        }
    }

    /**
     * <strong>The proportionality rule, asserted rather than argued.</strong> One organization out of a
     * fleet with a dangling {@code plan_id} is one organization silently downgraded. Converted into a
     * boot failure it is every other tenant on the installation down as well — for a condition ADR 0010
     * Phase 4 deliberately made a {@code select}, which is the rule {@code MappedSchemaValidator} states
     * and this class used to state and then break.
     *
     * <p>Two silos are placed on purpose. The refusal is scoped to an installation whose registry holds
     * exactly ONE tenant — the extracted single-tenant deployment §6 item 4 is written about — so a test
     * that placed one silo would be asserting the opposite property by accident on a database no other
     * test happened to have written a placement into.
     *
     * <p>And a warning that says nothing is not a downgrade of a refusal, it is a deletion of it: the
     * fact has to still be there afterwards, so {@code inspect()} is asserted too.
     */
    @Test
    void aDanglingPlanInOneSiloOfAFleetIsReportedRatherThanFailingEveryPodsBoot() {
        UUID broken = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        UUID healthy = EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "ext-" + UUID.randomUUID());
        silos.place(broken);
        silos.place(healthy);
        UUID missing = UUID.randomUUID();
        TenantContext.runAs(broken, () -> jdbc.update("""
                insert into org_subscription (id, org_id, plan_id, status, version, created_at)
                values (?, ?, ?, 'ACTIVE', 0, now())
                """, UUID.randomUUID(), broken, missing));
        try {
            assertThatCode(() -> guard.everySubscriptionNamesAPlanThisInstallationHas())
                    .describedAs("a dangling plan in ONE silo of a multi-tenant installation must not"
                            + " fail the boot of every pod — the defect is one tenant's downgrade and the"
                            + " refusal would be the whole fleet's outage (ADR 0010 Phase 4)")
                    .doesNotThrowAnyException();

            PlanCatalogGuard.Findings findings = guard.inspect();
            assertThat(findings.siloed())
                    .describedAs("not throwing must not mean not noticing: the tenant and the plan it"
                            + " names are what an operator repairs from")
                    .containsEntry(broken, List.of(missing));
            assertThat(findings.servesExactlyOneTenant())
                    .describedAs("two placed silos is not the extracted single-tenant deployment, which"
                            + " is the only installation whose silo may refuse the boot")
                    .isFalse();
            assertThat(findings.siloed()).doesNotContainKey(healthy);
        } finally {
            TenantContext.runAs(broken,
                    () -> jdbc.update("delete from org_subscription where org_id = ?", broken));
        }
    }

    /**
     * <strong>An ACTIVE placement row is allowed to outlive the schema it names, and that must be a
     * report rather than a fleet-wide outage.</strong> V57's header says the row must be able to survive
     * its organization, and {@code TenantMigrationRunner.discoverFleet} names "a registry row with no
     * schema" as a state it reports rather than crashes on.
     *
     * <p>{@code TenantRoutes.readHome} hands the recorded name straight to {@code search_path} with no
     * existence check and Postgres silently ignores a path element that is not there, so the unqualified
     * {@code org_subscription} the guard reads raises {@code relation "org_subscription" does not exist}.
     * Thrown out of the {@code ApplicationReadyEvent} listener that would be every pod refusing to start
     * over one stale row.
     */
    @Test
    void anActivePlacementNamingASchemaThatIsNotThereIsReportedRatherThanThrown() {
        UUID ghost = UUID.randomUUID();
        jdbc.update("""
                insert into platform.tenant_placement (org_id, schema_name, datasource_name, state,
                                                       updated_at)
                values (?, ?, 'primary', 'ACTIVE', now())
                """, ghost, TenantSchemas.siloSchema(ghost));
        try {
            assertThatCode(() -> guard.everySubscriptionNamesAPlanThisInstallationHas())
                    .describedAs("the schema this row names was never created; a boot check must report"
                            + " that, not refuse to start over it")
                    .doesNotThrowAnyException();
            assertThat(guard.inspect().unreadable())
                    .describedAs("and the tenant whose answer is UNKNOWN has to be named, or 'did not"
                            + " throw' is indistinguishable from 'did not look'")
                    .containsKey(ghost);
        } finally {
            jdbc.update("delete from platform.tenant_placement where org_id = ?", ghost);
            // The route memo outlives the row by TenantRoutes.routeTtl(); a later test pinning this
            // (impossible) axis would otherwise get the silo name from memory and fail somewhere else.
            TenantRoutes.forget(ghost);
        }
    }

    /**
     * The half of "fails startup" that the direct calls above cannot show: that the refusal is wired to
     * {@code ApplicationReadyEvent} at all. {@code SpringApplication.run} rethrows what that listener
     * throws, so this annotation is the difference between an exception that stops a deployment and one
     * that stops nothing.
     *
     * <p>Asserted by reflection rather than by publishing the event into the live context on purpose:
     * an {@code ApplicationReadyEvent} published here would also run {@code BootstrapTenantAxis}'s
     * listener, which calls {@code TenantContext.clear()} and would pull the axis out from under the
     * rest of this test class, and {@code OutboxResubmissionJob}'s, which starts an {@code @Async} pass
     * over the outbox. A test that had to break two unrelated subsystems to observe one annotation would
     * be proving less than it cost.
     */
    @Test
    void theRefusalIsWiredToApplicationReadyEventAndThereforeFailsStartup() throws Exception {
        EventListener listener = PlanCatalogGuard.class
                .getDeclaredMethod("everySubscriptionNamesAPlanThisInstallationHas")
                .getAnnotation(EventListener.class);
        assertThat(listener)
                .describedAs("without @EventListener nothing invokes this at boot and the refusal is"
                        + " dead code that no deployment ever runs")
                .isNotNull();
        assertThat(listener.value())
                .describedAs("ApplicationReadyEvent specifically: it fires after PlanSeeder, which is an"
                        + " ApplicationRunner, so the catalogue it asks about is the one this"
                        + " installation will serve from")
                .containsExactly(ApplicationReadyEvent.class);
    }
}
