package ug.co.smsone.notification.internal;

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

/**
 * Drains the delivery queue: claims batches, sends each on a bounded pool of virtual threads
 * (Java 21 makes thousands of concurrent I/O sends cheap), and marks each SENT / rescheduled with
 * exponential backoff / dead-lettered. Runs as a self-managed background poller per instance — no
 * scheduling infrastructure, and SKIP LOCKED means instances share the queue without double-sends.
 *
 * <p>Sends happen OUTSIDE any DB transaction; only the short status update touches the DB. In tests
 * the background poller is disabled ({@code worker-auto-start=false}) and {@link #drainOnce()} is
 * driven explicitly for determinism.
 */
@Component
public class NotificationDeliveryWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
    private static final int MAX_BACKOFF_SHIFT = 16;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(20);

    private final NotificationDeliveryQueue queue;
    private final ChannelRegistry channels;
    private final ChannelRateLimiter channelRateLimiter;
    private final NotificationProperties.Delivery config;

    private final ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;

    private volatile boolean running;
    private volatile Thread poller;
    private volatile Instant lastPurge = Instant.EPOCH;

    NotificationDeliveryWorker(NotificationDeliveryQueue queue, ChannelRegistry channels,
            ChannelRateLimiter channelRateLimiter, NotificationProperties properties) {
        this.queue = queue;
        this.channels = channels;
        this.channelRateLimiter = channelRateLimiter;
        this.config = properties.delivery();
        this.permits = new Semaphore(config.concurrency());
    }

    // ---- SmartLifecycle: own the poller thread's lifecycle ----

    @Override
    public void start() {
        running = true;
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
                int processed = drainOnce();
                maybePurge();
                if (processed == 0) {
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

    /** Claim and process up to {@code maxDrainBatches} full batches; returns how many were processed. */
    public int drainOnce() throws InterruptedException {
        int total = 0;
        for (int i = 0; i < config.maxDrainBatches(); i++) {
            List<ClaimedDelivery> batch = queue.claim(config.batchSize(), config.staleLock());
            if (batch.isEmpty()) {
                break;
            }
            processBatch(batch);
            total += batch.size();
            if (batch.size() < config.batchSize()) {
                break;
            }
        }
        return total;
    }

    private void processBatch(List<ClaimedDelivery> batch) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(batch.size());
        for (ClaimedDelivery delivery : batch) {
            try {
                sendExecutor.submit(() -> {
                    try {
                        // Acquire INSIDE the task (not on the poller) so a hung channel can never
                        // pin the poller and stall claiming for every other channel/recipient.
                        permits.acquire();
                        try {
                            deliver(delivery);
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
        done.await();
    }

    private void deliver(ClaimedDelivery delivery) {
        NotificationChannelSender sender = channels.get(delivery.channel());
        if (sender == null) {
            log.warn("No sender registered for channel {} — dead-lettering delivery {} to {}",
                    delivery.channel(), delivery.id(), delivery.recipient());
            queue.deadLetter(delivery.id(), "No sender registered for channel " + delivery.channel());
            return;
        }
        Optional<Duration> defer = channelRateLimiter.check(delivery.channel());
        if (defer.isPresent()) {
            // Channel provider quota exhausted. Defer without burning an attempt — but dead-letter if
            // it has been throttled longer than throttleMaxAge, so a mis-set rate can't spin forever.
            if (Duration.between(delivery.createdAt(), Instant.now()).compareTo(config.throttleMaxAge()) > 0) {
                log.warn("Delivery {} to {} via {} dead-lettered: throttled beyond {}",
                        delivery.id(), delivery.recipient(), delivery.channel(), config.throttleMaxAge());
                queue.deadLetter(delivery.id(), "Throttled beyond max age " + config.throttleMaxAge());
            } else {
                queue.rescheduleThrottled(delivery.id(), Instant.now().plus(defer.get()));
            }
            return;
        }
        try {
            sender.send(new NotificationMessage(delivery.recipient(), delivery.subject(), delivery.body(), Map.of()));
        } catch (RuntimeException ex) {
            // The send may never have reached the provider — refund the channel token so a retry
            // storm doesn't burn provider quota and starve healthy messages.
            channelRateLimiter.refund(delivery.channel());
            if (delivery.attempts() >= delivery.maxAttempts()) {
                queue.deadLetter(delivery.id(), ex.getMessage());
                log.warn("Delivery {} to {} via {} dead-lettered after {} attempts: {}",
                        delivery.id(), delivery.recipient(), delivery.channel(), delivery.attempts(), ex.toString());
            } else {
                queue.reschedule(delivery.id(), Instant.now().plus(backoff(delivery.attempts())), ex.getMessage());
            }
            return;
        }
        // Send succeeded. Record it separately: if this fails, leave the row PROCESSING for the
        // stale-lock reclaim — do NOT reschedule/dead-letter, or we'd re-send an already-sent message.
        try {
            queue.markSent(delivery.id());
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

    private void maybePurge() {
        if (Duration.between(lastPurge, Instant.now()).compareTo(config.purgeInterval()) < 0) {
            return;
        }
        lastPurge = Instant.now();
        try {
            int deleted = queue.purgeSentBefore(Instant.now().minus(config.retention()));
            if (deleted > 0) {
                log.info("Purged {} delivered notifications older than {}", deleted, config.retention());
            }
        } catch (RuntimeException ex) {
            log.warn("Delivery purge failed: {}", ex.toString());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
