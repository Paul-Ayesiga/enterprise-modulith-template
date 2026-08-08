package ug.co.smsone.webhooks.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.queue.QueueSignals;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Drains the webhook queue: claims a tenant, claims a batch of that tenant's deliveries, sends each on
 * virtual threads (up to batch-size concurrent, each bounded by the send timeout), and marks each
 * DELIVERED / rescheduled (exponential backoff) / dead-lettered. Self-managed background poller per
 * instance; {@code SKIP LOCKED} lets instances share the queue with no concurrent double-claims — and
 * since Phase 3 it also spreads them across tenants rather than piling them onto one. Delivery is still
 * <b>at-least-once</b>: a crash between the POST and its status write leaves the row PROCESSING, and
 * the stale-lock reclaim re-POSTs it — receivers must tolerate a duplicate. In tests the poller is
 * disabled ({@code app.webhooks.worker-auto-start=false}) and {@link #drainOnce()} is driven directly.
 */
@Component
class WebhookDeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private static final int MAX_BACKOFF_SHIFT = 16;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);

    /**
     * How many tenants one drain will look at and find nothing in before giving up for this poll.
     *
     * <p>A signal can outlive the work it announced — a subscription deleted mid-flight cancels its
     * outstanding rows, retention purges the last terminal row, a tenant's whole backlog is paused —
     * and ADR 0010 §2.1 makes that explicitly harmless: the worker that finds nothing is the thing that
     * cleans it up. The bound is what stops "cleans it up" from becoming an unbounded sweep on a poll
     * that was supposed to be cheap. Each probe leaves its tenant either deleted or not due again, so
     * the loop never revisits one, and whatever it does not reach this poll it reaches on the next.
     */
    private static final int MAX_EMPTY_PROBES = 32;

    private final WebhookDeliveryQueue queue;
    private final QueueSignals signals;
    private final WebhookSender sender;
    private final WebhookProperties config;
    private final Clock clock;
    private final MeterRegistry meters;

    private volatile ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private volatile Thread poller;

    WebhookDeliveryWorker(WebhookDeliveryQueue queue, QueueSignals signals, WebhookSender sender,
            WebhookProperties config, Clock clock, MeterRegistry meters) {
        this.queue = queue;
        this.signals = signals;
        this.sender = sender;
        this.config = config;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public void start() {
        running = true;
        if (sendExecutor.isShutdown()) {
            sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
        if (config.workerAutoStart()) {
            poller = Thread.ofVirtual().name("webhook-delivery-poller").start(this::runLoop);
            log.info("Webhook delivery worker started (batchSize={}, poll={})",
                    config.batchSize(), config.pollInterval());
        }
    }

    @Override
    public void stop() {
        running = false;
        Thread p = poller;
        if (p != null) {
            p.interrupt();
        }
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
                    log.warn("Webhook delivery poll failed: {}", ex.toString());
                    sleepQuietly(config.pollInterval().toMillis());
                }
            }
        }
    }

    /**
     * Claim ONE tenant and process one batch of its deliveries; returns how many were processed.
     *
     * <p><b>This is the fairness change, and it is a change of shape rather than of predicate</b>
     * (ADR 0010 §2.1). Before Phase 3 a drain was one {@code FOR UPDATE SKIP LOCKED} sweep over the
     * whole table, so the batch went to whoever held the oldest rows — and a tenant holding more than
     * {@code batchSize} of them held the entire fleet's claim slot for as long as the backlog lasted.
     * Now the tenant is chosen first, from {@code platform.queue_signal}, and the batch is bounded to
     * that one tenant; the release stamps it back with {@code greatest(remaining, now())}, which puts it
     * behind every tenant that has been waiting longer. One tenant, one slot, per drain — a property of
     * how the claim is built, not of a WHERE clause someone has to keep remembering.
     *
     * <p><b>Three pins, and each one is a different thread's problem</b> (ADR 0010 §3.4). The SIGNAL
     * claim takes the PLATFORM axis: {@code queue_signal} is platform-tier, and the poller is a thread
     * this class starts itself, so no filter and no {@code TaskDecorator} has declared anything. The
     * ROW claim and the release take the claimed TENANT's axis, because {@code webhook_delivery} and
     * {@code webhook_subscription} are tenant-tier and unqualified — which is exactly what will send
     * them to a silo instead of the pool the day that tenant is promoted, with nothing here to change.
     * Each SEND re-pins on {@link #sendExecutor}, this class's own virtual-thread executor: a
     * thread-local does not cross a virtual thread's boundary however the caller is pinned, and missing
     * it would leave a webhook POSTed successfully and never marked — which the stale-lock reclaim then
     * re-POSTs.
     *
     * <p>An empty batch is not a failure: the signal outlived its work, so it is released (which deletes
     * it when the tenant has nothing left) and the next tenant is tried, up to {@link #MAX_EMPTY_PROBES}.
     */
    public int drainOnce() throws InterruptedException {
        for (int probe = 0; probe < MAX_EMPTY_PROBES; probe++) {
            Optional<QueueSignals.Leased> due = TenantContext.callAsPlatform(
                    () -> signals.claim(WebhookDeliveryQueue.QUEUE, config.staleLock()));
            if (due.isEmpty()) {
                return 0;
            }
            QueueSignals.Leased tenant = due.get();
            UUID orgId = tenant.scope();
            List<ClaimedWebhookDelivery> batch = TenantContext.callAs(orgId,
                    () -> queue.claim(orgId, config.batchSize(), config.staleLock()));
            if (batch.isEmpty()) {
                release(tenant);
                continue;
            }
            try {
                send(batch, orgId);
            } finally {
                // After the sends, so the recomputed due_at sees their final statuses — a rescheduled
                // row's new next_attempt_at, and anything still PROCESSING through its stale-lock arm.
                // In a finally because a signal left holding this worker's lease is a tenant nobody
                // looks at again until the lease expires.
                release(tenant);
            }
            return batch.size();
        }
        return 0;
    }

    private void release(QueueSignals.Leased tenant) {
        TenantContext.runAs(tenant.scope(),
                () -> queue.releaseSignal(tenant.scope(), tenant.lease(), config.staleLock()));
    }

    private void send(List<ClaimedWebhookDelivery> batch, UUID orgId) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(batch.size());
        ExecutorService executor = this.sendExecutor;
        for (ClaimedWebhookDelivery delivery : batch) {
            try {
                executor.submit(() -> {
                    try {
                        // The same axis the row was claimed on — necessarily, since the status write
                        // has to reach the row the claim found. It comes from the tenant the signal
                        // named, which is why the delivery record carries no org of its own.
                        TenantContext.runAs(orgId, () -> deliver(delivery));
                    } finally {
                        done.countDown();
                    }
                });
            } catch (RejectedExecutionException ex) {
                done.countDown(); // executor shutting down: row stays PROCESSING, reclaimed later
            }
        }
        // Bounded: an unfinished send is reclaimable after staleLock anyway, so waiting longer buys nothing.
        if (!done.await(config.staleLock().toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("{} of {} webhook deliveries did not finish within {}; moving on (rows reclaimable)",
                    done.getCount(), batch.size(), config.staleLock());
        }
    }

    private void deliver(ClaimedWebhookDelivery delivery) {
        int status;
        try {
            status = sender.send(delivery);
        } catch (RuntimeException ex) {
            // Catch ANY runtime failure, not just WebhookDeliveryException — otherwise an unexpected
            // error would leave the row PROCESSING and be reclaimed forever with no dead-letter.
            boolean permanent = ex instanceof WebhookDeliveryException wde && wde.permanent();
            Integer responseStatus = ex instanceof WebhookDeliveryException wde ? wde.responseStatus() : null;
            if (permanent || delivery.attempts() >= delivery.maxAttempts()) {
                queue.deadLetter(delivery.id(), responseStatus, ex.getMessage(), delivery.attempts());
                Counter.builder("smsone.deliveries.dead_lettered")
                        .description("Deliveries given up on, by queue and reason")
                        .tag("queue", "webhook")
                        .tag("channel", "http")
                        .tag("reason", permanent ? "permanent" : "exhausted")
                        .register(meters)
                        .increment();
                log.warn("Webhook delivery {} dead-lettered after {} attempts ({}): {}",
                        delivery.id(), delivery.attempts(), permanent ? "permanent" : "exhausted", ex.getMessage());
            } else {
                queue.reschedule(delivery.id(), clock.instant().plus(backoff(delivery.attempts())),
                        responseStatus, ex.getMessage(), delivery.attempts());
            }
            return;
        }
        // POSTed successfully. Record it separately: if the status write fails, leave the row
        // PROCESSING for the stale-lock reclaim — rescheduling here would re-POST a webhook the
        // receiver already accepted, and dead-lettering would record a delivered webhook as FAILED.
        try {
            queue.markDelivered(delivery.id(), delivery.attempts(), status);
        } catch (RuntimeException ex) {
            log.error("Webhook delivery {} was DELIVERED but markDelivered failed; leaving PROCESSING for reclaim: {}",
                    delivery.id(), ex.toString());
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
