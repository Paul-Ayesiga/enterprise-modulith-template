package ug.co.smsone.billing.internal;

import java.time.Duration;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    @Scheduled(cron = "${app.scheduler.dunning-cron:0 40 3 * * *}")
    @SchedulerLock(name = "billing-dunning", lockAtMostFor = "PT10M")
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
