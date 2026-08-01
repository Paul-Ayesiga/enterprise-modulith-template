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

    private final NotificationDeliveryQueue queue;
    private final ChannelRegistry channels;
    private final ChannelRateLimiter channelRateLimiter;
    private final NotificationProperties.Delivery config;
    private final Clock clock;

    private final Semaphore permits;

    private volatile ExecutorService sendExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private volatile Thread poller;

    NotificationDeliveryWorker(NotificationDeliveryQueue queue, ChannelRegistry channels,
            ChannelRateLimiter channelRateLimiter, NotificationProperties properties, Clock clock) {
        this.queue = queue;
        this.channels = channels;
        this.channelRateLimiter = channelRateLimiter;
        this.config = properties.delivery();
        this.clock = clock;
        this.permits = new Semaphore(config.concurrency());
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
        ExecutorService executor = this.sendExecutor;
        for (ClaimedDelivery delivery : batch) {
            try {
                executor.submit(() -> {
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
            } else {
                queue.rescheduleThrottled(delivery.id(), clock.instant().plus(defer.get()), delivery.attempts());
            }
            return;
        }
        try {
            sender.send(new NotificationMessage(delivery.recipient(), delivery.subject(), delivery.body(), Map.of()));
        } catch (RuntimeException ex) {
            // The send may never have reached the provider — refund the channel token so a retry
            // storm doesn't burn provider quota and starve healthy messages.
            channelRateLimiter.refund(delivery.channel());
            boolean permanent = ex instanceof NotificationDeliveryException nde && nde.permanent();
            if (permanent || delivery.attempts() >= delivery.maxAttempts()) {
                queue.deadLetter(delivery.id(), ex.getMessage(), delivery.attempts());
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
