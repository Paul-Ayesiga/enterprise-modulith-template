package ug.co.smsone.exchange.internal;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.shared.security.OrgAuthorization;

/**
 * Fires due schedules: submits the export job AS THE SCHEDULE'S REQUESTER — after re-resolving
 * that requester's export permission NOW, so a role revoked after the schedule was created stops
 * it (disabled loudly, with a log line, never silently skipped forever). Lives here rather than in
 * {@code scheduler} because it needs this module's store and registry — the same sanctioned
 * exception as the two retention jobs (AGENTS §7).
 */
@Component
class ExchangeScheduleFiringJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeScheduleFiringJob.class);
    private static final int BATCH = 50;

    private final ExchangeScheduleRepository schedules;
    private final ExchangeJobStore store;
    private final HandlerRegistry handlers;
    private final OrgAuthorization authorization;
    private final Clock clock;

    ExchangeScheduleFiringJob(ExchangeScheduleRepository schedules, ExchangeJobStore store,
            HandlerRegistry handlers, OrgAuthorization authorization, Clock clock,
            ExchangeProperties config) {
        this.schedules = schedules;
        this.store = store;
        this.handlers = handlers;
        this.authorization = authorization;
        this.clock = clock;
        this.config = config;
    }

    private final ExchangeProperties config;

    /**
     * The scheduled entry — split from {@link #fireDueSchedules()} so tests drive the logic
     * directly without the ShedLock (which silently skips a same-name relock) and can turn the
     * background trigger off entirely ({@code app.exchange.schedule-fire-enabled=false} in the
     * test profile — a minute-cadence job would otherwise race explicit test invocations from
     * every cached context).
     */
    @Scheduled(cron = "${app.scheduler.exchange-schedule-cron:30 * * * * *}")
    @SchedulerLock(name = "exchange-schedule-fire", lockAtMostFor = "PT5M")
    @Transactional
    public void scheduledFire() {
        if (!config.scheduleFireEnabled()) {
            return;
        }
        doFire();
    }

    @Transactional
    public void fireDueSchedules() {
        doFire();
    }

    private void doFire() {
        List<ExchangeSchedule> due = schedules.lockDue(clock.instant(), Limit.of(BATCH));
        for (ExchangeSchedule schedule : due) {
            try {
                fire(schedule);
            } catch (RuntimeException ex) {
                // Isolated per schedule; nextRunAt advances regardless, or a poisoned schedule
                // would re-fail every minute forever.
                log.error("Exchange schedule {} failed to fire", schedule.getId(), ex);
                schedule.fired(schedule.getLastJobId(),
                        ExchangeScheduleService.next(schedule.getCron(), clock.instant()));
                schedules.save(schedule);
            }
        }
    }

    private void fire(ExchangeSchedule schedule) {
        ExchangeHandler handler = handlers.find(schedule.getHandler()).orElse(null);
        if (handler == null) {
            log.warn("Exchange schedule {} references unknown handler '{}' — disabling",
                    schedule.getId(), schedule.getHandler());
            schedule.disable();
            schedules.save(schedule);
            return;
        }
        if (!authorization.hasPermission(schedule.getRequester(), schedule.getOrgId(),
                handler.exportPermission())) {
            log.warn("Exchange schedule {} requester {} no longer holds {} — disabling",
                    schedule.getId(), schedule.getRequester(), handler.exportPermission());
            schedule.disable();
            schedules.save(schedule);
            return;
        }
        UUID jobId = store.submit(schedule.getOrgId(), schedule.getRequester(), ExchangeJob.EXPORT,
                handler.id(), handler.templateVersion(), schedule.getFormat(), null);
        schedule.fired(jobId, ExchangeScheduleService.next(schedule.getCron(), clock.instant()));
        schedules.save(schedule);
        log.info("Exchange schedule {} fired job {}", schedule.getId(), jobId);
    }
}
