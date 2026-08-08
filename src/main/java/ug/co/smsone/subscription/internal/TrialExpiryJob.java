package ug.co.smsone.subscription.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

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
     * The axis this sweep borrows. It names no organization deliberately: an org that has never been
     * promoted resolves to the shared {@code tenant_pool}, and a UUID in no {@code organization} row
     * can never resolve to anything else — so this IS the pooled schema's axis, spelled with the only
     * vocabulary {@code TenantContext} has. Same constant, same reasoning as
     * {@code MappedSchemaValidator} and {@code WebhookSecretEncryptionMigrator}.
     *
     * <p>When silos exist (ADR 0010 Phase 5) this stops being one pass. The loop belongs here, over
     * {@code platform.tenant_placement}, summing what each home paused.
     */
    private static final java.util.UUID POOLED_TENANT = new java.util.UUID(0L, 0L);

    private final SubscriptionService subscriptions;

    TrialExpiryJob(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
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
     * <p><b>LEASE: PT10M for an hourly job over an unbounded query — the same honest gap as
     * {@code DunningJob}, one cadence faster.</b> {@code expireTrials} loads every lapsed trial at once
     * and pauses them in one transaction with an event and an audit row each; there is no batch cap and
     * no deadline, so the pass is bounded by how many trials lapse in an hour. Ten minutes is sized for
     * that ordinary trickle and NOT for the one-off spike a bulk signup cohort produces when its trials
     * all end on the same day — that case wants batching before it wants a longer lease. Nothing here
     * is sized by schema count today; ADR 0010 Phase 5 multiplies the pass by (silos + 1) homes and is
     * where both this number and {@code DunningJob}'s want re-deriving together, since they are the same
     * sweep over the same table an hour apart.
     */
    @Scheduled(cron = "${app.scheduler.trial-expiry-cron:0 10 * * * *}")
    @SchedulerLock(name = "subscription-trial-expiry", lockAtMostFor = "PT10M")
    @JobAxis(TENANT)
    public void run() {
        // Declares a TENANT axis, not the platform one: no request runs this, so nothing else declares
        // anything, and `org_subscription` is tenant-tier (ADR 0010 §2) — the platform pin this used to
        // take could not see the table at all once Phase 2 split them. The `plan` rows expireTrials
        // reads alongside it are platform-tier and name their schema, so one pin covers the pass; see
        // POOLED_TENANT for what Phase 5 owes.
        int paused = TenantContext.callAs(POOLED_TENANT, subscriptions::expireTrials);
        if (paused > 0) {
            log.info("Paused {} lapsed trial subscription(s)", paused);
        }
    }
}
