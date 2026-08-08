package ug.co.smsone.notification.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ug.co.smsone.notification.NotificationChannelSender;
import ug.co.smsone.notification.NotificationMessage;
import ug.co.smsone.shared.queue.QueueSignals;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Drains the delivery queue: claims batches, sends each on a bounded pool of virtual threads
 * (Java 21 makes thousands of concurrent I/O sends cheap), and marks each SENT / rescheduled with
 * exponential backoff / dead-lettered. Runs as a self-managed background poller per instance — no
 * scheduling infrastructure, and SKIP LOCKED means instances share the queue without double-sends.
 *
 * <p>Sends happen OUTSIDE any DB transaction; only the short status update touches the DB. In tests
 * the background poller is disabled ({@code worker-auto-start=false}) and {@link #drainOnce()} is
 * driven explicitly for determinism.
 *
 * <p>Public because tests outside this package drive it: {@code shared.ratelimit}'s egress-throttle
 * IT calls {@link #drainOnce()} directly.
 */
@Component
public class NotificationDeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    private static final int MAX_BACKOFF_SHIFT = 16;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);

    /**
     * How many scopes one drain will look at and find nothing in before giving up for this poll.
     *
     * <p>A signal can outlive the work it announced — retention purges the last terminal row, a whole
     * batch dead-letters — and ADR 0010 §2.1 makes that explicitly harmless: the worker that finds
     * nothing is the thing that cleans it up. The bound stops that cleanup from turning a one-second
     * poll into an unbounded sweep. Each probe leaves its scope either deleted or not due again, so the
     * loop never revisits one and whatever it misses this poll it reaches on the next.
     */
    private static final int MAX_EMPTY_PROBES = 32;

    private final NotificationDeliveryQueue queue;
    private final QueueSignals signals;
    private final ChannelRegistry channels;
    private final ChannelRateLimiter channelRateLimiter;
    private final NotificationProperties.Delivery config;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    private final Semaphore permits;

    private volatile ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private volatile Thread poller;

    NotificationDeliveryWorker(NotificationDeliveryQueue queue, QueueSignals signals,
            ChannelRegistry channels, ChannelRateLimiter channelRateLimiter,
            NotificationProperties properties, Clock clock,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.queue = queue;
        this.signals = signals;
        this.channels = channels;
        this.channelRateLimiter = channelRateLimiter;
        this.config = properties.delivery();
        this.clock = clock;
        this.meters = meters;
        this.permits = new Semaphore(config.concurrency());
    }

    /** One counter, one place: every give-up increments it, tagged by channel and why. */
    private void countDeadLetter(Object channel, String reason) {
        io.micrometer.core.instrument.Counter.builder("smsone.deliveries.dead_lettered")
                .description("Deliveries given up on, by queue and reason")
                .tag("queue", "notification")
                .tag("channel", String.valueOf(channel).toLowerCase())
                .tag("reason", reason)
                .register(meters)
                .increment();
    }

    // ---- SmartLifecycle: own the poller thread's lifecycle ----

    @Override
    public void start() {
        running = true;
        if (sendExecutor.isShutdown()) {
            sendExecutor = Executors.newVirtualThreadPerTaskExecutor(); // survive a stop/start cycle
        }
        if (config.workerAutoStart()) {
            poller = Thread.ofVirtual().name("notif-delivery-poller").start(this::runLoop);
            log.info("Notification delivery worker started (concurrency={}, batchSize={}, poll={})",
                    config.concurrency(), config.batchSize(), config.pollInterval());
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread p = poller;
        if (p != null) {
            p.interrupt();
        }
        // Let in-flight sends finish their status update before forcing shutdown — otherwise a
        // message sent-but-not-marked stays PROCESSING and gets re-sent after the stale-lock window.
        sendExecutor.shutdown();
        try {
            if (!sendExecutor.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                sendExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            sendExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100; // stop early, before the DataSource closes
    }

    // ---- Poll loop ----

    private void runLoop() {
        while (running) {
            try {
                if (drainOnce() == 0) {
                    Thread.sleep(config.pollInterval().toMillis());
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                if (running) {
                    log.warn("Delivery poll failed: {}", ex.toString());
                    sleepQuietly(config.pollInterval().toMillis());
                }
            }
        }
    }

    /**
     * Claim and process up to {@code maxDrainBatches} full batches, <b>each from a different tenant</b>;
     * returns how many were processed.
     *
     * <p>Declares the platform axis for the CLAIM (ADR 0010 §3.4). The poller is its own thread —
     * started in {@link #start()}, not by any executor — so nothing else would, and the axis has to be
     * declared here rather than in {@link #runLoop()} because tests drive this method directly. The
     * sends are pinned separately: see {@link #processBatch}.
     *
     * <p><b>Phase 2 left this pin PLATFORM and Phase 3 left it PLATFORM, and that is the tier decision
     * rather than an oversight.</b> {@code notification_delivery} carries an {@code org_id} but stayed
     * platform-tier (ADR 0010 §2): pure transport, highest rate of the three queues, drained rather
     * than dumped at extraction. So unlike the webhook and exchange queues nothing here is ever pinned
     * to a tenant — {@code platform.queue_signal} and {@code platform.notification_delivery} are both
     * named, and both stay named after Phase 5.
     *
     * <p><b>What Phase 3 DID change is which rows one batch can contain.</b> The claim used to be a
     * single cluster-wide sweep, so {@code order by next_attempt_at limit batchSize} handed every batch
     * to whoever held the oldest rows — one tenant with a five-figure backlog owned the worker until it
     * drained, which is the cost ADR 0010 §2 accepts in writing and then says to mitigate with fairness.
     * Each iteration below now takes ONE scope from the signal, drains one batch of it, and stamps it
     * back with {@code greatest(remaining, now())} — behind everyone who has been waiting longer. The
     * loop is bounded by scopes rather than by consecutive full batches for the same reason: a scope
     * with three messages used to end the drain (a short batch meant an empty queue); now it just means
     * the next scope's turn.
     */
    public int drainOnce() throws InterruptedException {
        int total = 0;
        int batches = 0;
        int emptyProbes = 0;
        while (batches < config.maxDrainBatches() && emptyProbes < MAX_EMPTY_PROBES) {
            Optional<QueueSignals.Leased> due = TenantContext.callAsPlatform(
                    () -> signals.claim(NotificationDeliveryQueue.QUEUE, config.staleLock()));
            if (due.isEmpty()) {
                break;
            }
            QueueSignals.Leased leased = due.get();
            List<ClaimedDelivery> batch = TenantContext.callAsPlatform(
                    () -> queue.claim(leased.scope(), config.batchSize(), config.staleLock()));
            if (batch.isEmpty()) {
                emptyProbes++;
                release(leased);
                continue;
            }
            try {
                processBatch(batch);
                total += batch.size();
                batches++;
            } finally {
                // After the sends, so the recomputed due_at sees their final statuses — including a
                // throttled row's deferred next_attempt_at, which is the whole point of deferring it.
                // In a finally because a signal still holding this worker's lease is a scope nobody
                // looks at again until the lease expires.
                release(leased);
            }
        }
        return total;
    }

    private void release(QueueSignals.Leased leased) {
        TenantContext.runAsPlatform(
                () -> queue.releaseSignal(leased.scope(), leased.lease(), config.staleLock()));
    }

    /**
     * Sends one claimed batch, each on its own virtual thread, and waits.
     *
     * <p><b>Each send task declares its own axis, and that is not redundant with {@link #drainOnce()}.</b>
     * {@link #sendExecutor} is this class's own {@code newVirtualThreadPerTaskExecutor}, not Boot's
     * shared one, so the {@code TaskDecorator} in {@code shared.config.AsyncConfig} never sees these
     * tasks — and a thread-local is not inherited by a virtual thread anyway. Every status write
     * {@link #deliver} makes ({@code markSent}, {@code reschedule}, {@code deadLetter}) borrows a
     * connection on this thread, so without the pin the send would succeed and the row would never be
     * marked.
     *
     * <p><b>PLATFORM, and every table this task reaches is one</b> (ADR 0010 §2):
     * {@code notification_delivery} for the status write and {@code in_app_notification} for the in-app
     * channel. The one org-specific read on the path — {@code Integrations.resolve}, which picks the
     * recipient org's SMS provider over the platform default — NAMES both homes precisely so that it
     * answers the same thing from this thread; that exception is documented at {@code IntegrationsImpl}
     * and this worker is the caller it was written for. Re-pinning to {@code delivery.orgId()} here
     * would buy nothing and cost the status write its own table.
     */
    private void processBatch(List<ClaimedDelivery> batch) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(batch.size());
        ExecutorService executor = this.sendExecutor;
        for (ClaimedDelivery delivery : batch) {
            try {
                executor.submit(() -> {
                    try {
                        // Acquire INSIDE the task (not on the poller) so a hung channel can never
                        // pin the poller and stall claiming for every other channel/recipient.
                        permits.acquire();
                        try {
                            TenantContext.runAsPlatform(() -> deliver(delivery));
                        } finally {
                            permits.release();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            } catch (RejectedExecutionException ex) {
                // Executor shutting down: don't leave the latch hanging (row stays PROCESSING, reclaimed later).
                done.countDown();
            }
        }
        // Bounded: a send that somehow outlives every timeout must not stall claiming forever. After
        // staleLock the unfinished rows are reclaimable anyway, so waiting longer buys nothing.
        if (!done.await(config.staleLock().toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("{} of {} deliveries in the batch did not finish within {}; moving on (rows will be reclaimed)",
                    done.getCount(), batch.size(), config.staleLock());
        }
    }

    private void deliver(ClaimedDelivery delivery) {
        NotificationChannelSender sender = channels.get(delivery.channel());
        if (sender == null) {
            log.warn("No sender registered for channel {} — dead-lettering delivery {} to {}",
                    delivery.channel(), delivery.id(), delivery.recipient());
            queue.deadLetter(delivery.id(), "No sender registered for channel " + delivery.channel(),
                    delivery.attempts());
            countDeadLetter(delivery.channel(), "no_sender");
            return;
        }
        Optional<Duration> defer = channelRateLimiter.check(delivery.channel());
        if (defer.isPresent()) {
            // Channel provider quota exhausted. Defer without burning an attempt — but dead-letter
            // once it has spent throttleMaxAge CONTINUOUSLY throttled (a mis-set rate can't spin
            // forever). Measured from the first deferral of this stretch, never from enqueue time —
            // an old message from a legitimate burst is just waiting its turn.
            Instant throttledSince = delivery.throttledSince();
            if (throttledSince != null
                    && Duration.between(throttledSince, clock.instant()).compareTo(config.throttleMaxAge()) > 0) {
                log.warn("Delivery {} to {} via {} dead-lettered: throttled continuously beyond {}",
                        delivery.id(), delivery.recipient(), delivery.channel(), config.throttleMaxAge());
                queue.deadLetter(delivery.id(), "Throttled continuously beyond " + config.throttleMaxAge(),
                        delivery.attempts());
                countDeadLetter(delivery.channel(), "throttled_too_long");
            } else {
                queue.rescheduleThrottled(delivery.id(), clock.instant().plus(defer.get()), delivery.attempts());
            }
            return;
        }
        try {
            sender.send(new NotificationMessage(delivery.recipient(), delivery.subject(), delivery.body(),
                    delivery.orgId() == null ? Map.of() : Map.of("orgId", delivery.orgId().toString())));
        } catch (RuntimeException ex) {
            // The send may never have reached the provider — refund the channel token so a retry
            // storm doesn't burn provider quota and starve healthy messages.
            channelRateLimiter.refund(delivery.channel());
            boolean permanent = ex instanceof NotificationDeliveryException nde && nde.permanent();
            if (permanent || delivery.attempts() >= delivery.maxAttempts()) {
                queue.deadLetter(delivery.id(), ex.getMessage(), delivery.attempts());
                countDeadLetter(delivery.channel(), permanent ? "permanent" : "exhausted");
                log.warn("Delivery {} to {} via {} dead-lettered after {} attempts ({}): {}",
                        delivery.id(), delivery.recipient(), delivery.channel(), delivery.attempts(),
                        permanent ? "permanent failure" : "attempts exhausted", ex.toString());
            } else {
                queue.reschedule(delivery.id(), clock.instant().plus(backoff(delivery.attempts())),
                        ex.getMessage(), delivery.attempts());
            }
            return;
        }
        // Send succeeded. Record it separately: if this fails, leave the row PROCESSING for the
        // stale-lock reclaim — do NOT reschedule/dead-letter, or we'd re-send an already-sent message.
        try {
            queue.markSent(delivery.id(), delivery.attempts());
        } catch (RuntimeException ex) {
            log.error("Delivery {} to {} via {} was SENT but markSent failed; leaving PROCESSING for reclaim: {}",
                    delivery.id(), delivery.recipient(), delivery.channel(), ex.toString());
        }
    }

    private Duration backoff(int attempts) {
        long base = config.retryBaseBackoff().toMillis();
        long scaled = base << Math.min(attempts - 1, MAX_BACKOFF_SHIFT);
        long capped = Math.min(scaled, config.retryMaxBackoff().toMillis());
        return Duration.ofMillis(Math.max(base, capped));
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
