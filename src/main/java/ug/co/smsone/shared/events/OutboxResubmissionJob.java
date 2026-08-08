package ug.co.smsone.shared.events;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;

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
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;

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

    /**
     * <b>AXIS: PLATFORM.</b> {@code event_publication} is the registry this deployment's outbox writes
     * to, configured onto {@code platform} — see {@link #resubmitOnce} for the harder half of that
     * argument, which is that the RETRY and the first delivery must go through the same axis or the
     * same publication replays against a different schema than it was published on.
     *
     * <p><b>CURSOR: persisted in the data, in {@code platform.event_publication}, and it is the one
     * this job could least afford to keep in memory.</b> A pass takes the head of the incomplete set
     * and stamps {@code last_resubmission_date} on every publication it takes; the {@link Window}
     * predicate then skips anything stamped inside {@code retryBackoff}. So the position is written to
     * the same rows the pass is working on, in the same transaction, and it is shared by every replica
     * — a pass on instance B genuinely continues where instance A's stopped. That property is what
     * makes {@code maxPerRun} a cap rather than a truncation, and the {@link Window} javadoc explains
     * the trap in reading it (a null {@code last_resubmission_date} does NOT mean never-retried).
     *
     * <p><b>LEASE: PT10M against a five-minute cron, and this one is a genuine guess.</b> What it has
     * to cover is {@code maxPerRun} publications × the slowest listener chain behind them, and neither
     * term is measured: listener cost is application code that varies per event type, and a pass is
     * synchronous through {@code resubmitIncompletePublications}. Ten minutes is two ticks, chosen so a
     * pass that runs long simply skips a tick instead of overlapping — the failure at the far end is a
     * pass exceeding PT10M, at which point a second instance starts one concurrently, and the only
     * thing standing between that and double delivery is Modulith's {@code and status != 'RESUBMITTED'}
     * guard on {@code markResubmitted}. That guard is real (it is why the startup pass is deliberately
     * unlocked), so an overrun is survivable — but it is survivable by accident of another component's
     * predicate, not by this number, and the honest fix if it ever happens is to lower
     * {@code maxPerRun} rather than raise the lease. Nothing here scales with tenant count.
     */
    @Scheduled(cron = "${app.scheduler.event-resubmission-cron:0 */5 * * * *}")
    @SchedulerLock(name = "outbox-resubmission", lockAtMostFor = "PT10M")
    @JobAxis(PLATFORM)
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
     *
     * <p><b>The axis is declared HERE, at the one method all three entries share</b> — the startup
     * pass, the cron pass and a test's direct call — because that is what makes them identical. ADR
     * 0010 §3.4 forbids a {@code ThreadLocalAccessor} for exactly this job: it has no thread context to
     * inherit, so if the immediate delivery of an event took an inherited axis and this retry took a
     * declared one, the same publication would be replayed against a different schema than it was
     * first published on. Both go through PLATFORM instead, and the registry rows this reads
     * ({@code event_publication}) are platform-tier anyway.
     *
     * <p>Note what this pin does NOT reach: the listeners the resubmission invokes. Each is
     * {@code @Async}, so it hops to the shared executor and takes its axis from the {@code TaskDecorator}
     * in {@code shared.config.AsyncConfig} — the same one the first delivery went through.
     */
    int resubmitOnce(String trigger) {
        return TenantContext.callAsPlatform(() -> resubmit(trigger));
    }

    private int resubmit(String trigger) {
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
