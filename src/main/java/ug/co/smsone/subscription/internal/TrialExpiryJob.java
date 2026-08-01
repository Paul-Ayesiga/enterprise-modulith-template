package ug.co.smsone.subscription.internal;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly, pauses every paid-plan trial that has lapsed — the org goes read-only until it subscribes
 * or pays. ShedLock so one instance runs it; the work itself is idempotent (a paused row is no
 * longer TRIALING). The logic lives on {@link SubscriptionService#expireTrials()} so it shares the
 * exact publish/audit path the rest of the module uses.
 */
@Component
class TrialExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(TrialExpiryJob.class);

    private final SubscriptionService subscriptions;

    TrialExpiryJob(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Scheduled(cron = "${app.scheduler.trial-expiry-cron:0 10 * * * *}")
    @SchedulerLock(name = "subscription-trial-expiry", lockAtMostFor = "PT10M")
    public void run() {
        int paused = subscriptions.expireTrials();
        if (paused > 0) {
            log.info("Paused {} lapsed trial subscription(s)", paused);
        }
    }
}
