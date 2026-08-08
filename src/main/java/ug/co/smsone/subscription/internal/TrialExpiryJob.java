package ug.co.smsone.subscription.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;

/**
 * Hourly, pauses every paid-plan trial that has lapsed — the org goes read-only until it subscribes
 * or pays. ShedLock so one instance runs it; the work itself is idempotent (a paused row is no
 * longer TRIALING). The logic lives on {@link SubscriptionService#expireTrials()} so it shares the
 * exact publish/audit path the rest of the module uses.
 */
@Component
class TrialExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(TrialExpiryJob.class);

    /**
     * How long one hourly pass may take, against the {@code PT10M} lease.
     *
     * <p>Eight minutes is the same two-minute margin {@code DunningJob} takes, and the deadline is here
     * because Phase 5 turned one pass into (silos + 1) of them. It does NOT make
     * {@link SubscriptionService#expireTrials} bounded — that method still loads every lapsed trial in
     * one shot, which is the honest gap this job has always had — but it stops the FAN-OUT from being a
     * second, independent way to overrun the lease, and an overrun is what lets a second replica pause
     * the same subscriptions while the first is still publishing events for them.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(8);

    /** Which home this pass is on and where the rotation resumes — see {@link TenantHomeSweep}. */
    private final TenantHomeSweep homes = new TenantHomeSweep("Trial expiry");

    private final SubscriptionService subscriptions;
    private final TenantFanOut fanOut;
    private final Clock clock;

    TrialExpiryJob(SubscriptionService subscriptions, TenantFanOut fanOut, Clock clock) {
        this.subscriptions = subscriptions;
        this.fanOut = fanOut;
        this.clock = clock;
    }

    /**
     * <b>AXIS: TENANT</b> — see the comment in the body; {@code org_subscription} is tenant-tier and the
     * platform pin this used to carry could not see the table at all after Phase 2 split them.
     *
     * <p><b>CURSOR: the state transition, held in the row.</b> {@link SubscriptionService#expireTrials}
     * selects {@code status = TRIALING} with a lapsed {@code trial_ends_at} and writes {@code PAUSED},
     * so a subscription this pass handles never appears in a later one. A run cut off half way leaves
     * the rest for the next HOUR rather than the next night, which is the second reason no in-memory
     * position is worth keeping here: the cadence is short enough that "the remainder waits for the
     * next tick" is a sixty-minute delay, not a starved tail.
     *
     * <p><b>LEASE: PT10M against {@link #RUN_DEADLINE}, over a query that is still unbounded — the same
     * honest gap as {@code DunningJob}, one cadence faster.</b> {@code expireTrials} loads every lapsed
     * trial at once and pauses them in one transaction with an event and an audit row each; there is no
     * batch cap, so ONE HOME's pass is still bounded only by how many of its trials lapsed. Eight
     * minutes is sized for the ordinary trickle and NOT for the one-off spike a bulk signup cohort
     * produces when its trials all end on the same day — that case wants batching before it wants a
     * longer lease, and the deadline cannot help with it because it is checked between homes rather than
     * inside one. What the deadline does fix is the fan-out: (silos + 1) ordinary passes cannot add up
     * to an overrun, and a run that is cut resumes at the next home an hour later rather than
     * re-visiting the pool forever.
     */
    @Scheduled(cron = "${app.scheduler.trial-expiry-cron:0 10 * * * *}")
    @SchedulerLock(name = "subscription-trial-expiry", lockAtMostFor = "PT10M")
    @JobAxis(TENANT)
    public void run() {
        // Declares a TENANT axis, not the platform one: no request runs this, so nothing else declares
        // anything, and `org_subscription` is tenant-tier (ADR 0010 §2) — the platform pin this used to
        // take could not see the table at all once Phase 2 split them. The `plan` rows expireTrials
        // reads alongside it are platform-tier and name their schema, so one pin covers a home.
        //
        // One pin per HOME since Phase 5. A promoted tenant's subscription is in its own schema, so a
        // pooled-only sweep left its lapsed trial TRIALING forever — the org kept full access past its
        // trial, indefinitely, and the job logged nothing because it had nothing to log.
        int[] paused = {0};
        TenantHomeSweep.Swept swept = homes.over(fanOut.fleet().homes(), clock,
                clock.instant().plus(RUN_DEADLINE),
                (home, deadline) -> paused[0] += subscriptions.expireTrials());
        if (paused[0] > 0) {
            log.info("Paused {} lapsed trial subscription(s) across {} tenant home(s)",
                    paused[0], swept.visited());
        }
        swept.rethrowFirstFailure();
    }
}
