package ug.co.smsone.billing.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;
import ug.co.smsone.subscription.Subscriptions;

/**
 * Dunning's endgame. The warnings are event-driven ({@link BillingStandingNotifier} mails the owners
 * the moment a payment fails); this job is the deadline: a subscription still PAST_DUE after the
 * grace window pauses — read-only until money lands or a plan is assigned. Daily + ShedLock, like
 * the trial-expiry job it mirrors; the state change itself runs through the subscription module's
 * audited port, so the pause looks identical to every other pause.
 */
@Component
class DunningJob {

    private static final Logger log = LoggerFactory.getLogger(DunningJob.class);

    /**
     * How long one run may take, against the {@code PT10M} lease — the same two-minute margin
     * {@code TrialExpiryJob} takes, and here for the same reason: Phase 5 turned one pass into
     * (silos + 1) of them, and (silos + 1) ordinary passes must not be able to add up to an overrun that
     * lets a second replica pause the same subscriptions concurrently.
     *
     * <p>It does not make {@link Subscriptions#pauseLapsedPastDue} bounded — that query is still
     * unbounded within a home, which is the honest gap the paragraph on {@link #run()} names — so this is
     * a bound on the FAN-OUT and says so.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(8);

    /** Which home this pass is on and where the rotation resumes — see {@link TenantHomeSweep}. */
    private final TenantHomeSweep homes = new TenantHomeSweep("Dunning");

    private final Subscriptions subscriptions;
    private final TenantFanOut fanOut;
    private final Clock clock;
    private final int graceDays;

    DunningJob(Subscriptions subscriptions, TenantFanOut fanOut, Clock clock,
            @Value("${app.billing.dunning.grace-days:7}") int graceDays) {
        this.subscriptions = subscriptions;
        this.fanOut = fanOut;
        this.clock = clock;
        this.graceDays = Math.max(1, graceDays);
    }

    /**
     * <b>AXIS: TENANT</b> — see the comment in the body; {@code org_subscription} is tenant-tier and the
     * platform pin this used to carry could not see the table at all after Phase 2 split them.
     *
     * <p><b>CURSOR: the state transition, held in the row.</b>
     * {@link Subscriptions#pauseLapsedPastDue} selects {@code status = PAST_DUE} and writes
     * {@code PAUSED}, so every subscription this pass handles leaves the candidate set permanently and a
     * run that dies half way leaves the remainder as the next run's whole input. No position to keep,
     * in memory or otherwise — which is the right answer here and would be the wrong one for a job that
     * merely READS what it visits.
     *
     * <p><b>LEASE: PT10M, and it is a bound on an unbounded query — say so plainly.</b>
     * {@code pauseLapsedPastDue} loads every lapsed subscription in one shot and pauses them in one
     * transaction, publishing an event and writing an audit row per org. There is no batch cap and no
     * deadline, so what actually ends the pass is the size of the lapsed set. Ten minutes is sized for
     * the ordinary case — a handful of orgs cross the grace boundary on any given night, because
     * {@code grace-days} staggers them — and NOT for the pathological one, which is a payment-provider
     * outage putting a large fraction of the fleet into {@code PAST_DUE} on the same day. That case
     * would want batching and a deadline before it wants a longer lease; it has not been built because
     * it has not happened, and this sentence is here so the next person to see it overrun knows the
     * shape of the fix rather than reaching for the lease.
     *
     * <p>Phase 5 multiplied the pass by (silos + 1) homes. The lease was NOT multiplied with it, and
     * that is deliberate: it is sized by the lapsed COUNT rather than by schema count, so the honest
     * response to a fan-out is per-home batching rather than PT10M × homes. What did arrive is
     * {@link #RUN_DEADLINE}, which bounds the number of homes one run reaches and hands the rest to the
     * next run through {@link #homes} — a delay of one day for the tail, against an overrun that would
     * have two replicas pausing the same subscriptions.
     */
    @Scheduled(cron = "${app.scheduler.dunning-cron:0 40 3 * * *}")
    @SchedulerLock(name = "billing-dunning", lockAtMostFor = "PT10M")
    @JobAxis(TENANT)
    public void run() {
        // Declares a TENANT axis, not the platform one: no request runs this, so nothing else declares
        // anything, and `org_subscription` is tenant-tier (ADR 0010 §2) — the platform pin this used to
        // take could not see the table at all once Phase 2 split them.
        //
        // One statement per HOME since Phase 5. "One statement sweeps every tenant" was true exactly
        // while every tenant was in one schema; after the first promotion it swept everyone EXCEPT the
        // promoted tenant, whose lapsed subscription then kept full access indefinitely with no error
        // anywhere to say the dunning deadline had stopped applying to it.
        int[] paused = {0};
        TenantHomeSweep.Swept swept = homes.over(fanOut.fleet().homes(), clock,
                clock.instant().plus(RUN_DEADLINE),
                (home, deadline) -> paused[0] += subscriptions.pauseLapsedPastDue(Duration.ofDays(graceDays)));
        if (paused[0] > 0) {
            log.info("Dunning: paused {} subscription(s) PAST_DUE beyond {} day(s) across {} tenant home(s)",
                    paused[0], graceDays, swept.visited());
        }
        swept.rethrowFirstFailure();
    }
}
