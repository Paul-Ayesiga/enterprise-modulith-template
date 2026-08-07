package ug.co.smsone.shared.events;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retries publications the outbox never completed — the recovery half of at-least-once delivery, and
 * the counterpart to {@link EventInbox}'s dedup on the consuming side.
 *
 * <p>Spring Modulith ships this behaviour as
 * {@code spring.modulith.events.republish-outstanding-events-on-restart}, and it runs inside
 * {@code afterSingletonsInstantiated()}: every incomplete publication, no limit, every matching
 * listener invoked before the context finishes refreshing. One bad night's backlog therefore lands
 * whole inside the next startup, serially, ahead of the readiness probe — the seeded registry's 8000
 * incomplete rows would be 8000 listener invocations between the JVM starting and the pod going
 * ready. That property is off (see {@code application.yaml}); this job does the same work in capped
 * passes, off the boot path.
 *
 * <p>Lives in {@code shared} rather than {@code scheduler} because the outbox/inbox seam is shared's
 * own — the sanctioned exception in AGENTS §7, which gives {@code scheduler} the ShedLock
 * infrastructure rather than a monopoly on {@code @Scheduled}.
 *
 * <p><b>What is deliberately still unbounded, and why.</b> The read behind
 * {@code resubmitIncompletePublications} materialises every incomplete row; its
 * {@code completion_date is null or status != 'COMPLETED'} predicate is the only one that also
 * covers publications a crash left mid-flight (those sit at {@code PUBLISHED}/{@code PROCESSING},
 * never {@code FAILED}). Modulith's LIMITed alternative, {@code ResubmissionOptions}, selects
 * {@code status = 'FAILED'} only, so adopting it for the LIMIT would quietly stop retrying exactly
 * the publications this job exists for — unless the staleness monitor is enabled too, and that
 * monitor re-reads the table every minute, trading one scan per boot for 1440 a day. Measured on the
 * seeded 200k-row registry (8000 incomplete) that read is 18ms and the nightly
 * {@code EventPublicationPurgeJob} keeps the table from growing without bound. The 8000 listener
 * invocations behind the read are the cost worth capping, and capping them is what this job does.
 */
@Component
class OutboxResubmissionJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxResubmissionJob.class);

    private final IncompleteEventPublications incomplete;
    private final OutboxResubmissionProperties properties;
    private final Clock clock;

    OutboxResubmissionJob(IncompleteEventPublications incomplete, OutboxResubmissionProperties properties,
            Clock clock) {
        this.incomplete = incomplete;
        this.properties = properties;
        this.clock = clock;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxResubmissionProperties.class)
    static class PropertiesRegistrar {
    }

    /**
     * The crash-recovery pass, and the reason a five-minute cron alone is not enough: a restart is
     * exactly when in-flight publications are known to be stranded, and making them wait for the
     * next cron tick would be a regression against the property this replaces.
     *
     * <p>{@code @Async} on purpose — {@code ApplicationReadyEvent} listeners run before the
     * readiness state flips to ACCEPTING_TRAFFIC, so doing the work inline would hold the pod out of
     * the load balancer for as long as the pass takes. Deliberately NOT ShedLocked either: every
     * instance recovers its own restart, as republish-on-restart did. Overlap during a rolling
     * deploy is not a double delivery — Modulith's {@code markResubmitted} update carries
     * {@code and status != 'RESUBMITTED'}, so a row a faster instance already claimed is a no-op
     * here.
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    void resubmitAfterStartup() {
        if (!properties.enabled()) {
            return;
        }
        resubmitOnce("startup");
    }

    @Scheduled(cron = "${app.scheduler.event-resubmission-cron:0 */5 * * * *}")
    // Ten minutes covers a pass whose listeners are all slow without outliving the next tick by much;
    // a pass that overruns it would let a second instance start one concurrently.
    @SchedulerLock(name = "outbox-resubmission", lockAtMostFor = "PT10M")
    public void scheduledResubmit() {
        if (!properties.enabled()) {
            return;
        }
        resubmitOnce("scheduled");
    }

    /**
     * One capped pass, oldest publication first; returns how many were resubmitted.
     *
     * <p>Split from the scheduled entry the way {@code ExchangeScheduleFiringJob} splits its own, so
     * a test drives the work directly: the entry carries a ShedLock that silently skips a same-name
     * relock, and the trigger is off in the test profile.
     */
    int resubmitOnce(String trigger) {
        Window window = new Window(clock.instant().minus(properties.retryBackoff()));
        incomplete.resubmitIncompletePublications(window);
        if (window.capped) {
            log.info("Outbox {} pass resubmitted {} publications and stopped at its cap. The remainder is "
                            + "not dropped: the next pass skips these (their backoff has not expired) and "
                            + "continues from where this one stopped.",
                    trigger, window.taken);
        } else if (window.taken > 0) {
            log.info("Outbox {} pass resubmitted {} incomplete publications.", trigger, window.taken);
        }
        return window.taken;
    }

    /**
     * Takes the head of the incomplete set — which arrives ordered by publication date — and stops at
     * the cap.
     *
     * <p>The backoff is what makes the cap safe rather than a silent truncation. Without it a head of
     * permanently-failing publications would refill the window on every pass and nothing behind them
     * would ever be reached: the same starvation {@code IdentityReconciliationJob} had to unlearn,
     * one table over. Skipping a recently-resubmitted publication costs no slot, so each pass
     * advances into the tail.
     *
     * <p>THE TRAP is what counts as "never resubmitted". It is not a null
     * {@code last_resubmission_date}: the registry writes that column equal to
     * {@code publication_date} on insert (older rows can still carry null), so the test is whether it
     * has moved PAST the publication date. Reading null-vs-set instead would make every freshly
     * stranded publication look like one already in retry, and the startup pass — the case this job
     * exists for — would skip all of them.
     */
    private final class Window implements Predicate<EventPublication> {

        private final Instant retryBefore;
        private int taken;
        private boolean capped;

        private Window(Instant retryBefore) {
            this.retryBefore = retryBefore;
        }

        @Override
        public boolean test(EventPublication publication) {
            if (taken >= properties.maxPerRun()) {
                capped = true;
                return false;
            }
            Instant last = publication.getLastResubmissionDate();
            if (last != null && last.isAfter(publication.getPublicationDate()) && last.isAfter(retryBefore)) {
                return false;
            }
            taken++;
            return true;
        }
    }
}
