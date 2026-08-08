package ug.co.smsone.subscription.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    @Scheduled(cron = "${app.scheduler.trial-expiry-cron:0 10 * * * *}")
    @SchedulerLock(name = "subscription-trial-expiry", lockAtMostFor = "PT10M")
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
