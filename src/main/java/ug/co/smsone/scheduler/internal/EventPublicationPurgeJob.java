package ug.co.smsone.scheduler.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Housekeeping for the DB-backed event registry: completed publications are kept for a retention
 * window (audit/debugging), then purged. Locked so only one instance runs it.
 */
@Component
class EventPublicationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationPurgeJob.class);

    private final CompletedEventPublications completedPublications;
    private final Duration retention;

    EventPublicationPurgeJob(CompletedEventPublications completedPublications,
            SchedulerRetentionProperties properties) {
        this.completedPublications = completedPublications;
        this.retention = properties.eventRetention();
    }

    /**
     * <b>AXIS: PLATFORM, and it stays one span however many tenants exist.</b> The Modulith registry is
     * configured onto {@code platform} ({@code spring.modulith.events.jdbc.schema}), so
     * {@code event_publication} is one table for the whole installation — a publication is a fact about
     * this deployment's outbox, not about any tenant, and Phase 5 does not give it a second home.
     *
     * <p><b>CURSOR: none, and none is needed — the deletion is the cursor.</b> Rows this pass removes
     * cannot come back, so a run cut off part-way leaves the remainder as the next run's whole input.
     * There is no head to re-examine and therefore no tail to starve, which is what separates this from
     * the scanning jobs ({@code IdentityReconciliationJob}, {@code OrgMembershipIndexReconciler}) that
     * DO need one.
     *
     * <p><b>LEASE: PT30M, and it is honestly a bound on the wrong thing.</b>
     * {@code deletePublicationsOlderThan} issues ONE unbatched DELETE — nothing here can stop half way,
     * so the lease does not bound the pass at all. What it decides is whether a SECOND replica may
     * start an identical DELETE while the first is still running. That is wasteful rather than wrong
     * (both statements target the same rows and Postgres' row locks serialise them), but a pass that
     * needs longer than PT30M is a signal, not a slow night: the next run inherits the same backlog plus
     * a day's growth and will not finish either. Sized for one schema whose row count is fleet-wide
     * traffic and NOT tenant count — the number to watch is {@code select count(*) from
     * platform.event_publication}, and if it approaches the point where a delete takes half an hour the
     * answer is a batched delete here, not a longer lease.
     */
    @Scheduled(cron = "${app.scheduler.event-purge-cron:0 0 3 * * *}")
    @SchedulerLock(name = "event-publication-purge", lockAtMostFor = "PT30M")
    @JobAxis(PLATFORM)
    public void purgeCompletedPublications() {
        // Declares the platform axis: the Modulith event registry is platform-tier infrastructure and
        // this runs with no request behind it. ADR 0010 §3.4.
        TenantContext.runAsPlatform(() -> {
            completedPublications.deletePublicationsOlderThan(retention);
            log.info("Purged completed event publications older than {}", retention);
        });
    }
}
