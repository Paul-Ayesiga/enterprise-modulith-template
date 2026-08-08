package ug.co.smsone.billing.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Duration;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;
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
     * The axis this sweep borrows. It names no organization deliberately: an org that has never been
     * promoted resolves to the shared {@code tenant_pool}, and a UUID in no {@code organization} row
     * can never resolve to anything else — so this IS the pooled schema's axis, spelled with the only
     * vocabulary {@code TenantContext} has. Same constant, same reasoning as
     * {@code MappedSchemaValidator} and {@code WebhookSecretEncryptionMigrator}.
     *
     * <p>When silos exist (ADR 0010 Phase 5) this stops being one pass. The loop belongs here, over
     * {@code platform.tenant_placement}, summing what each home paused.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final Subscriptions subscriptions;
    private final int graceDays;

    DunningJob(Subscriptions subscriptions,
            @Value("${app.billing.dunning.grace-days:7}") int graceDays) {
        this.subscriptions = subscriptions;
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
     * <p>ADR 0010 Phase 5 multiplies the pass by (silos + 1) homes, and since the lease is already
     * sized by the lapsed COUNT rather than by schema count, the honest re-derivation then is per-home
     * batching — not PT10M × homes.
     */
    @Scheduled(cron = "${app.scheduler.dunning-cron:0 40 3 * * *}")
    @SchedulerLock(name = "billing-dunning", lockAtMostFor = "PT10M")
    @JobAxis(TENANT)
    public void run() {
        // Declares a TENANT axis, not the platform one: no request runs this, so nothing else declares
        // anything, and `org_subscription` is tenant-tier (ADR 0010 §2) — the platform pin this used to
        // take could not see the table at all once Phase 2 split them. One statement still sweeps every
        // tenant because every tenant is in the same schema; see POOLED_TENANT for what Phase 5 owes.
        int paused = TenantContext.callAs(POOLED_TENANT,
                () -> subscriptions.pauseLapsedPastDue(Duration.ofDays(graceDays)));
        if (paused > 0) {
            log.info("Dunning: paused {} subscription(s) PAST_DUE beyond {} day(s)", paused, graceDays);
        }
    }
}
