package ug.co.smsone.webhooks.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * Drains the webhook queue: claims a batch, sends each on virtual threads (up to batch-size concurrent,
 * each bounded by the send timeout), and marks each DELIVERED / rescheduled (exponential backoff) /
 * dead-lettered. Self-managed background poller per instance; {@code SKIP LOCKED} lets instances share
 * the queue with no concurrent double-claims. Delivery is still <b>at-least-once</b>: a crash between
 * the POST and its status write leaves the row PROCESSING, and the stale-lock reclaim re-POSTs it —
 * receivers must tolerate a duplicate. In tests the poller is disabled
 * ({@code app.webhooks.worker-auto-start=false}) and {@link #drainOnce()} is driven directly.
 */
@Component
class WebhookDeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private static final int MAX_BACKOFF_SHIFT = 16;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);

    private final WebhookDeliveryQueue queue;
    private final WebhookSender sender;
    private final WebhookProperties config;
    private final Clock clock;
    private final MeterRegistry meters;

    private volatile ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private volatile Thread poller;

    WebhookDeliveryWorker(WebhookDeliveryQueue queue, WebhookSender sender, WebhookProperties config,
            Clock clock, MeterRegistry meters) {
        this.queue = queue;
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
     * Claim and process one batch; returns how many were processed.
     *
     * <p>Two pins, and both are needed (ADR 0010 §3.4). The CLAIM runs on the poller — a thread this
     * class starts itself, which no executor and therefore no {@code TaskDecorator} ever touches — and
     * each SEND runs on {@link #sendExecutor}, this class's own virtual-thread executor, where the
     * status write ({@code markDelivered}, {@code reschedule}, {@code deadLetter}) borrows its own
     * connection. Miss the second and a webhook POSTs successfully and is never marked, which the
     * stale-lock reclaim then re-POSTs — the duplicate this design already warns receivers about, but
     * for every delivery rather than after a crash.
     *
     * <p>PHASE 2: {@code webhook_delivery} is per-tenant, so the single claim becomes a claim per
     * tenant and each send pins {@code runAs(delivery.orgId())}.
     */
    public int drainOnce() throws InterruptedException {
        List<ClaimedWebhookDelivery> batch = TenantContext.callAsPlatform(
                () -> queue.claim(config.batchSize(), config.staleLock()));
        if (batch.isEmpty()) {
            return 0;
        }
        CountDownLatch done = new CountDownLatch(batch.size());
        ExecutorService executor = this.sendExecutor;
        for (ClaimedWebhookDelivery delivery : batch) {
            try {
                executor.submit(() -> {
                    try {
                        TenantContext.runAsPlatform(() -> deliver(delivery));
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
        return batch.size();
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
