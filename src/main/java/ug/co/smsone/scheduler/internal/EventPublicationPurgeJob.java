package ug.co.smsone.scheduler.internal;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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

    @Scheduled(cron = "${app.scheduler.event-purge-cron:0 0 3 * * *}")
    @SchedulerLock(name = "event-publication-purge", lockAtMostFor = "PT30M")
    public void purgeCompletedPublications() {
        // Declares the platform axis: the Modulith event registry is platform-tier infrastructure and
        // this runs with no request behind it. ADR 0010 §3.4.
        TenantContext.runAsPlatform(() -> {
            completedPublications.deletePublicationsOlderThan(retention);
            log.info("Purged completed event publications older than {}", retention);
        });
    }
}
