package ug.co.smsone.billing.internal;

import java.time.Duration;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.subscription.Subscriptions;

/**
 * Dunning's endgame. The warnings are event-driven ({@link BillingStandingNotifier} mails the owners
 * the moment a payment fails); this job is the deadline: a subscription still PAST_DUE after the
 * grace window pauses — read-only until money lands or a plan is assigned. Daily + ShedLock, like
 * the trial-expiry job it mirrors; the state change itself runs through the subscription module's
 * audited port, so the pause looks identical to every other pause.
 */
@Component
class DunningJob {

    private static final Logger log = LoggerFactory.getLogger(DunningJob.class);

    private final Subscriptions subscriptions;
    private final int graceDays;

    DunningJob(Subscriptions subscriptions,
            @Value("${app.billing.dunning.grace-days:7}") int graceDays) {
        this.subscriptions = subscriptions;
        this.graceDays = Math.max(1, graceDays);
    }

    @Scheduled(cron = "${app.scheduler.dunning-cron:0 40 3 * * *}")
    @SchedulerLock(name = "billing-dunning", lockAtMostFor = "PT10M")
    public void run() {
        int paused = subscriptions.pauseLapsedPastDue(Duration.ofDays(graceDays));
        if (paused > 0) {
            log.info("Dunning: paused {} subscription(s) PAST_DUE beyond {} day(s)", paused, graceDays);
        }
    }
}
