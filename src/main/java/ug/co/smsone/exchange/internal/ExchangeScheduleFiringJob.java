package ug.co.smsone.exchange.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

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
import ug.co.smsone.shared.tenancy.JobAxis;
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

    /**
     * The tenant axis this pass runs on. Names no organization deliberately: an org in no
     * {@code organization} row can only ever resolve to the shared {@code tenant_pool}, so this IS the
     * pooled schema's axis — the same constant and reasoning as {@code MappedSchemaValidator} and
     * {@code WebhookSecretEncryptionMigrator}.
     *
     * <p>PHASE 5 makes this a loop over {@code platform.tenant_placement}, one transaction per home,
     * and the {@link #BATCH} cap becomes per-home rather than global — which is a fairness change: due
     * schedules are ordered within a home from then on, not across the installation.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

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
     * starvation bug rather than a bound.
     *
     * <p><b>LEASE: PT5M against a 60-second cron, and the honest reading is that it is sized for a
     * SKIPPED TICK, not for a long pass.</b> One sweep is {@link #BATCH} = 50 schedules, each a
     * permission re-resolution plus an {@code exchange_job} insert — sub-second work — so PT5M leaves
     * four minutes of slack whose purpose is to absorb a stalled instance without letting a second one
     * start concurrently. A pass that genuinely needed five minutes would be a different problem, and
     * ShedLock's silent same-name relock skip means a slow tick simply does not fire rather than
     * queueing. Phase 5's per-home loop is where this needs re-deriving: 50 per sweep becomes 50 per
     * home, and the cap stops being global — a fairness change, since due schedules are ordered within
     * a home from then on and not across the installation.
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
        // borrowed and the transaction has already borrowed one. ADR 0010 §3.2/§3.4.
        TenantContext.runAs(POOLED_TENANT, () -> transactions.executeWithoutResult(tx -> doFire()));
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
