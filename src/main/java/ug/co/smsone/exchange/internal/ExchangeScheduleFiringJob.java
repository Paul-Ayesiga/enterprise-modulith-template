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
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.shared.security.OrgAuthorization;
import ug.co.smsone.shared.tenancy.TenantContext;

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
    private final TransactionTemplate transactions;

    ExchangeScheduleFiringJob(ExchangeScheduleRepository schedules, ExchangeJobStore store,
            HandlerRegistry handlers, OrgAuthorization authorization, Clock clock,
            ExchangeProperties config, TransactionTemplate transactions) {
        this.schedules = schedules;
        this.store = store;
        this.handlers = handlers;
        this.authorization = authorization;
        this.clock = clock;
        this.config = config;
        this.transactions = transactions;
    }

    private final ExchangeProperties config;

    /**
     * The scheduled entry — split from {@link #fireDueSchedules()} so tests drive the logic
     * directly without the ShedLock (which silently skips a same-name relock) and can turn the
     * background trigger off entirely ({@code app.exchange.schedule-fire-enabled=false} in the
     * test profile — a minute-cadence job would otherwise race explicit test invocations from
     * every cached context).
     *
     * <p><b>No {@code @Transactional} here any more, and the {@link TransactionTemplate} below is why.</b>
     * The schema is chosen when the connection is borrowed, so the tenant axis has to be declared
     * BEFORE the transaction opens — and {@link TenantContext#setPlatform()} throws if it is not (ADR
     * 0010 §3.2). An annotation on this method would put the transaction outside anything the body can
     * do, leaving the borrow on {@code no_tenant}. {@link #fireDueSchedules()} lost its annotation for
     * the same reason — it is invoked directly, off any request, so it has to declare its own axis too.
     */
    @Scheduled(cron = "${app.scheduler.exchange-schedule-cron:30 * * * * *}")
    @SchedulerLock(name = "exchange-schedule-fire", lockAtMostFor = "PT5M")
    public void scheduledFire() {
        if (!config.scheduleFireEnabled()) {
            return;
        }
        fireDueSchedules();
    }

    /** Same pin, same boundary — see {@link #scheduledFire()}; this is the entry without the ShedLock. */
    public void fireDueSchedules() {
        // Declares the platform axis, then opens the transaction inside it. ADR 0010 §3.4.
        // PHASE 2: lockDue() selects due schedules across every tenant. When exchange_schedule moves
        // to the tenant tier this becomes a loop — one runAs(orgId) + transaction per tenant — and the
        // BATCH cap becomes per-tenant rather than global.
        TenantContext.runAsPlatform(() -> transactions.executeWithoutResult(tx -> doFire()));
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
        if (!authorization.hasPermission(schedule.getRequesterPersonId(), schedule.getOrgId(),
                handler.exportPermission())) {
            log.warn("Exchange schedule {} requester {} no longer holds {} — disabling",
                    schedule.getId(), schedule.getRequesterPersonId(), handler.exportPermission());
            schedule.disable();
            schedules.save(schedule);
            return;
        }
        UUID jobId = store.submit(schedule.getOrgId(), schedule.getRequesterPersonId(), ExchangeJob.EXPORT,
                handler.id(), handler.templateVersion(), schedule.getFormat(), null);
        schedule.fired(jobId, ExchangeScheduleService.next(schedule.getCron(), clock.instant()));
        schedules.save(schedule);
        log.info("Exchange schedule {} fired job {}", schedule.getId(), jobId);
    }
}
