package ug.co.smsone.exchange.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import java.time.Clock;
import java.time.Duration;
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
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;

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

    /**
     * How long one sweep may take, against the {@code PT5M} lease and a 60-second cron.
     *
     * <p>It exists because the sweep stopped being one transaction. One home is {@link #BATCH} = 50
     * schedules, each a permission re-resolution plus an {@code exchange_job} insert — sub-second — so
     * four minutes covers a fan-out well past the silo ceiling. What it prevents is the case where it
     * does not: a pass outliving PT5M lets ShedLock hand the lock to a second replica while the first is
     * still firing, and two sweeps then submit the same schedule twice. A sweep that takes four minutes
     * skips three ticks, which delays a schedule by minutes; the alternative duplicates an export.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    /** Which home this sweep is on and where the rotation resumes — see {@link TenantHomeSweep}. */
    private final TenantHomeSweep homes = new TenantHomeSweep("Exchange-schedule firing");

    private final ExchangeScheduleRepository schedules;
    private final ExchangeJobStore store;
    private final HandlerRegistry handlers;
    private final OrgAuthorization authorization;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TenantFanOut fanOut;

    ExchangeScheduleFiringJob(ExchangeScheduleRepository schedules, ExchangeJobStore store,
            HandlerRegistry handlers, OrgAuthorization authorization, Clock clock,
            ExchangeProperties config, TransactionTemplate transactions, TenantFanOut fanOut) {
        this.schedules = schedules;
        this.store = store;
        this.handlers = handlers;
        this.authorization = authorization;
        this.clock = clock;
        this.config = config;
        this.transactions = transactions;
        this.fanOut = fanOut;
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
     * BEFORE the transaction opens — and {@code TenantContext.set} throws if it is not (ADR 0010 §3.2).
     * An annotation on this method would put the transaction outside anything the body can do, leaving
     * the borrow on {@code no_tenant}. {@link #fireDueSchedules()} lost its annotation for the same
     * reason — it is invoked directly, off any request, so it has to declare its own axis too.
     *
     * <p><b>AXIS: TENANT</b>, argued on {@link #fireDueSchedules()}.
     *
     * <p><b>CURSOR: {@code exchange_schedule.next_run_at}, in the row, and it advances on EVERY
     * outcome.</b> {@link #fire} moves it on a successful submission, the {@code catch} in
     * {@link #doFire} moves it after a failure, and an unauthorised or unknown-handler schedule is
     * disabled outright — so no schedule can be selected twice by consecutive sweeps and none can sit
     * at the head of {@code lockDue}'s ordering blocking the ones behind it. That is a resumable cursor
     * in the strongest available form: shared between replicas, durable across restarts, and advanced
     * by the failure path as well as the success path. The {@link #BATCH} cap is safe precisely because
     * of that last property; a cap over an ordering the failure path did not advance would be a
     * starvation bug rather than a bound. Since Phase 5 there is a second cursor above it —
     * {@link #homes} — so a sweep cut mid-fleet continues at the next home rather than restarting at
     * the pool, and {@link #BATCH} is per home rather than global. That is a fairness change worth
     * naming: due schedules are ordered within a home from here on and not across the installation,
     * which is better than the alternative it replaces, where the pool would fill a global fifty every
     * minute and a promoted tenant's schedules would queue behind five thousand pooled ones.
     *
     * <p><b>LEASE: PT5M against {@link #RUN_DEADLINE}, and the honest reading is that it is sized for a
     * SKIPPED TICK, not for a long pass.</b> One home is {@link #BATCH} = 50 schedules, each a
     * permission re-resolution plus an {@code exchange_job} insert — sub-second work — so four minutes
     * covers a fan-out an order of magnitude past the silo ceiling, and the remaining minute of slack
     * absorbs a stalled instance without letting a second one start concurrently. ShedLock's silent
     * same-name relock skip means a slow tick simply does not fire rather than queueing.
     */
    @Scheduled(cron = "${app.scheduler.exchange-schedule-cron:30 * * * * *}")
    @SchedulerLock(name = "exchange-schedule-fire", lockAtMostFor = "PT5M")
    @JobAxis(TENANT)
    public void scheduledFire() {
        if (!config.scheduleFireEnabled()) {
            return;
        }
        fireDueSchedules();
    }

    /**
     * Same pin, same boundary — see {@link #scheduledFire()}; this is the entry without the ShedLock.
     *
     * <p><b>A TENANT axis since Phase 2, not the platform one it used to take.</b> Everything this pass
     * reads is the tenant's: {@code exchange_schedule} is tenant-tier (ADR 0010 §2), and so are the
     * {@code membership}, {@code org_role} and {@code role_permission} rows behind
     * {@code authorization.hasPermission} — which is the check that decides whether a schedule is still
     * allowed to fire. On the platform axis none of them resolve, so every schedule in the installation
     * silently stopped firing. The one write that leaves this tier, {@code store.submit}, names its own
     * home and is correct from here for exactly that reason.
     */
    public void fireDueSchedules() {
        // The axis first, then the transaction inside it: the schema is chosen when the connection is
        // borrowed and the transaction has already borrowed one. ADR 0010 §3.2/§3.4. One of each per
        // HOME — a promoted tenant's schedules are in its own schema, and a pooled-only sweep would have
        // stopped firing them with nothing anywhere saying so.
        homes.over(fanOut.fleet().homes(), clock, clock.instant().plus(RUN_DEADLINE),
                        (home, deadline) -> transactions.executeWithoutResult(tx -> doFire()))
                .rethrowFirstFailure();
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
