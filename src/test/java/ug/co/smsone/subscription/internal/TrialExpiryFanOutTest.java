package ug.co.smsone.subscription.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;
import ug.co.smsone.testsupport.TenantSilos;

/**
 * <b>Trial expiry with one organization in its own schema and the rest pooled</b> (ADR 0010 §7 Phase 5).
 *
 * <p>{@code org_subscription} is tenant-tier and the sweep's query is unqualified, so before the fan-out
 * a promoted tenant's lapsed trial was never found: the org kept full access past its trial,
 * indefinitely, and the job logged nothing because it had nothing to log. That is the shape of the whole
 * class of defect Phase 5 removes — not an error, an absence.
 *
 * <p>{@code DunningJob} is the same sweep over the same table one cadence apart, built the same way with
 * the same {@code TenantHomeSweep}; what is proved here is the mechanism both of them use.
 */
class TrialExpiryFanOutTest extends AbstractIntegrationTest {

    @RegisterExtension
    final TenantSilos silos = new TenantSilos();

    @Autowired
    private TrialExpiryJob job;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pausesALapsedTrialInASiloAndOneInThePoolInTheSameRun() {
        UUID pooled = organization();
        UUID siloed = organization();
        silos.place(siloed);

        UUID planId = seedPlan();
        insertTrial(pooled, planId, Instant.now().minus(Duration.ofDays(1)));
        UUID siloedTrial = insertTrial(siloed, planId, Instant.now().minus(Duration.ofDays(1)));
        // Schema-qualified, before the job runs: the siloed subscription is really in t_<hex> and
        // really not in the pool. Seeding and reading both go through TenantContext, so without this
        // the two assertions below would pass identically with no silo in play at all.
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "org_subscription", "id", siloedTrial);

        runExpiry();

        assertThat(statusOf(pooled)).as("the pooled tenant's lapsed trial pauses, as it always did")
                .isEqualTo("PAUSED");
        assertThat(statusOf(siloed))
                .as("and so does the siloed tenant's. Before the fan-out it stayed TRIALING forever — "
                        + "full access past the trial, with no error and no log line")
                .isEqualTo("PAUSED");
    }

    /** A trial still inside its window must be untouched in a silo, exactly as in the pool. */
    @Test
    void aTrialThatHasNotLapsedIsLeftAloneInASilo() {
        UUID siloed = organization();
        silos.place(siloed);
        UUID trial = insertTrial(siloed, seedPlan(), Instant.now().plus(Duration.ofDays(7)));
        TenantSilos.assertRowIsPhysicallyInTheSilo(jdbc, siloed, "org_subscription", "id", trial);

        runExpiry();

        assertThat(statusOf(siloed))
                .as("the sweep must reach the silo AND still respect the deadline in the row")
                .isEqualTo("TRIALING");
    }

    /**
     * ShedLock intercepts a direct call too and holds the lock afterwards, so the second test in this
     * class would be skipped in silence and pass for the wrong reason.
     */
    private void runExpiry() {
        jdbc.update("update platform.shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "subscription-trial-expiry");
        job.run();
    }

    private UUID organization() {
        return EdgeSeed.organization(jdbc, "kc-" + UUID.randomUUID(), "trial-fanout-" + UUID.randomUUID());
    }

    /** {@code plan} is platform-tier and named, so it is seeded on the harness's PLATFORM axis. */
    private UUID seedPlan() {
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                insert into platform.plan (id, code, name, rank, version, created_at)
                values (?, ?, 'Fan-out plan', 9, 0, now())
                """, planId, "FANOUT_" + planId.toString().substring(0, 8).toUpperCase());
        return planId;
    }

    /**
     * Written on the org's OWN axis — for a placed org that is {@code t_<hex>}. The whole test is that
     * the sweep can follow it there; if it cannot, the row is simply still TRIALING at the end.
     */
    private UUID insertTrial(UUID orgId, UUID planId, Instant trialEndsAt) {
        UUID id = UUID.randomUUID();
        TenantContext.runAs(orgId, () -> jdbc.update("""
                insert into org_subscription (id, org_id, plan_id, status, trial_ends_at, version, created_at)
                values (?, ?, ?, 'TRIALING', ?, 0, now())
                """, id, orgId, planId, Timestamp.from(trialEndsAt)));
        return id;
    }

    private String statusOf(UUID orgId) {
        return TenantContext.callAs(orgId, () -> jdbc.queryForObject(
                "select status from org_subscription where org_id = ? and deleted_at is null",
                String.class, orgId));
    }
}
