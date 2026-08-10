package ug.co.smsone.support.internal;

import static ug.co.smsone.shared.tenancy.JobAxis.Axis.PLATFORM;
import static ug.co.smsone.shared.tenancy.JobAxis.Axis.TENANT;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.JobAxis;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.shared.tenancy.TenantFanOut;
import ug.co.smsone.shared.tenancy.TenantHomeSweep;
import ug.co.smsone.support.TicketEscalated;

/**
 * Flags SLA breaches every minute: a ticket past its resolution due, not yet escalated and not
 * terminal, is bumped one priority, counted (`smsone.support.breached`), and {@code TicketEscalated}
 * published (fanned out as a webhook). The support queue is told once per sweep, as a digest —
 * see {@link SupportNotifier#ticketsEscalated}. ShedLock so one instance runs it; rows are row-locked
 * with SKIP LOCKED so a manual re-run never double-escalates.
 */
@Component
class SlaEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(SlaEscalationJob.class);
    private static final int BATCH = 100;
    private static final List<String> BUMP = List.of("P4", "P3", "P2", "P1");

    /**
     * How long one sweep may take before it stops itself, against the {@code PT5M} lease.
     *
     * <p><b>It exists because the sweep stopped being one statement.</b> A pooled-only sweep was a
     * single locking select over at most {@link #BATCH} rows and could not plausibly outlast five
     * minutes, so it had no deadline and needed none. Fanning out multiplies it by (silos + 1)
     * transactions, and a pass that outlives its lease is not a slow job: ShedLock hands the lock to a
     * second replica while the first is still escalating, and two sweeps then walk the same rows.
     * {@code SKIP LOCKED} keeps that from double-escalating a single ticket, which is exactly why this
     * deadline is a bound on the RUN and not a correctness patch — the correctness is in the row lock,
     * and the deadline is what stops the run from needing it.
     *
     * <p>Four minutes against five is the same one-minute margin the other minute-cadence job
     * ({@code ExchangeScheduleFiringJob}) takes, and it is a judgement rather than a measurement: one
     * home's work is a locking select plus up to {@link #BATCH} row updates, sub-second, so 201 homes
     * should be seconds and not minutes. If it is ever hit, the answer is NOT a longer lease — it is
     * ADR 0010 §5.2's {@code platform.ticket_index}, which turns the fan-out's N from the tenant count
     * into the breach count.
     *
     * <p><b>That table exists now (V61) and this job still fans out, deliberately.</b> The queue
     * ({@code TicketFanOut}) reads it because a LISTING can be served from a projection; a breach sweep
     * cannot, and the difference is the row lock. {@code lockBreached} is a
     * {@code PESSIMISTIC_WRITE}/{@code SKIP LOCKED} select on the tenant's own table, which is what stops
     * two instances escalating one ticket twice — a projection cannot lock a row in another schema, let
     * alone in another database (ADR 0011). Reading candidates from the index would mean re-reading and
     * re-locking each one in its own home anyway, so it only pays once the breach count is far below the
     * home count. Until it is measured to be, this stays a fan-out and the deadline above is what bounds
     * it.
     *
     * <p>The visible cost of hitting it is that a sweep taking four minutes skips three ticks. That is
     * the right trade: a skipped tick delays an escalation by a minute, an overrun runs two of them.
     */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(4);

    /**
     * Which home this sweep is on and where the rotation resumes — see {@link TenantHomeSweep}. Held as
     * a field because the position has to outlive a run.
     */
    private final TenantHomeSweep homes = new TenantHomeSweep("SLA escalation");

    private final TicketRepository tickets;
    private final SupportNotifier notifier;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final TicketIndex index;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final TenantFanOut fanOut;

    SlaEscalationJob(TicketRepository tickets, SupportNotifier notifier, ApplicationEventPublisher events,
            MeterRegistry meters, TicketIndex index, Clock clock, TransactionTemplate transactions,
            TenantFanOut fanOut) {
        this.tickets = tickets;
        this.notifier = notifier;
        this.events = events;
        this.meters = meters;
        this.index = index;
        this.clock = clock;
        this.transactions = transactions;
        this.fanOut = fanOut;
    }

    /**
     * <b>The transaction moved from an annotation to a {@link TransactionTemplate}, and that is a
     * tenancy fix, not a style change.</b> The schema is chosen when the connection is borrowed, so the
     * axis must be declared before the transaction opens; {@link TenantContext#setPlatform()} throws
     * inside an active one precisely so this cannot be got wrong silently (ADR 0010 §3.2). With
     * {@code @Transactional} on this method there was no point in the body early enough to pin — the
     * borrow had already happened on {@code no_tenant}. The advice order made it worse than that: the
     * ShedLock advisor and the transaction advisor both default to {@code LOWEST_PRECEDENCE}, so which
     * one wrapped the other was unspecified, and with the transaction outside, ShedLock's own
     * {@code shedlock} borrow would have been axis-less too.
     *
     * <p><b>AXIS: TENANT and PLATFORM since Phase 5, and the second one is the digest.</b> The sweep
     * itself is tenant work — one span per home — but {@link SupportNotifier#ticketsEscalated} resolves
     * the admin roster from {@code platform.person_contact} and enqueues through
     * {@code platform.notification_delivery}, and it is sent once for the whole run rather than once per
     * home. Leaving it on whichever home happened to be last would be an accident that works.
     *
     * <p><b>A TENANT axis since Phase 2, not the platform one it used to take.</b> {@code ticket} is
     * tenant-tier (ADR 0010 §2), so on the platform axis {@code lockBreached} does not sweep zero rows —
     * it fails outright with {@code relation "ticket" does not exist}, inside a scheduled job whose
     * exception nobody reads at 04:00, and every SLA breach on the installation stops escalating. The
     * pin is what makes the statement resolve at all.
     *
     * <p><b>One transaction per HOME since Phase 5, and it is the difference between escalating a
     * promoted tenant's breaches and silently never escalating them again.</b> {@code lockBreached} is
     * an unqualified query, so it reads whichever schema the connection's {@code search_path} names; a
     * sweep that only ever pinned the pool would keep returning the pool's breaches, report a healthy
     * run, and leave a silo's SLA clock running forever with nothing anywhere saying so. See
     * {@link ug.co.smsone.shared.tenancy.TenantFanOut} for why the fan-out is one statement
     * per home rather than one union over the homes.
     *
     * <p><b>CURSOR: two, at two granularities.</b> Within a home it is {@code ticket.escalated}, in the
     * row — {@code lockBreached} selects tickets past their resolution due that are not yet escalated
     * and not terminal, and {@code Ticket.escalate} sets the flag inside the same transaction, so an
     * escalated ticket leaves the candidate set permanently and the next sweep sees the remainder. The
     * {@link #BATCH} cap is therefore a bound and not a truncation: at 100 a home and one sweep a
     * minute, a backlog of 6,000 breaches drains in an hour with nothing re-examined. {@code SKIP
     * LOCKED} covers the other direction — a manual re-run, or a replica the lease let through, steps
     * over rows the first is holding rather than blocking on them or double-escalating them. Across
     * homes it is {@link #homes}, so a sweep cut at silo 40 of 200 continues at 41 next minute instead
     * of never reaching the tail.
     *
     * <p><b>LEASE: PT5M against {@link #RUN_DEADLINE}, and it is still, at the far end, the wrong
     * knob.</b> Sized: one home is a single transaction over at most 100 rows, well under a second, so
     * four minutes covers a fan-out an order of magnitude past the silo ceiling — the same "absorb a
     * stalled instance without letting a second one start" reasoning as
     * {@code ExchangeScheduleFiringJob}. Wrong knob: ADR 0010 §5.2 names this job as the one the split
     * hurts most, because {@code lockBreached} is now a {@code PESSIMISTIC_WRITE}/{@code SKIP LOCKED}
     * round trip per schema, and at the 200-silo ceiling that is 201 of them every sixty seconds. PT5M
     * covers it and the job is still wrong at that size — the answer there is
     * {@code platform.ticket_index}, so a sweep dives per-tenant only to WRITE the escalation and N
     * becomes the breach count rather than the tenant count. §5.1 puts the trigger for building it at
     * 50 silos, which is where {@code TenantFanOut} starts warning.
     */
    @Scheduled(cron = "${app.scheduler.support-escalation-cron:15 * * * * *}")
    @SchedulerLock(name = "support-sla-escalation", lockAtMostFor = "PT5M")
    @JobAxis({TENANT, PLATFORM})
    public void escalateBreaches() {
        // One transaction per HOME, each on that home's axis, declared before the transaction opens —
        // the schema is chosen at connection borrow, so the order is not negotiable (ADR 0010 §3.2).
        List<Ticket> escalated = new ArrayList<>();
        TenantHomeSweep.Swept swept = homes.over(fanOut.fleet().homes(), clock,
                clock.instant().plus(RUN_DEADLINE),
                (home, deadline) -> transactions.executeWithoutResult(tx -> escalated.addAll(escalate())));
        // ONE digest for the whole sweep, still — see SupportNotifier.ticketsEscalated. Collected across
        // the loop rather than sent inside it: per home it would be one mail per silo per minute, and
        // the roster it resolves is the platform's and identical for all of them.
        //
        // On the PLATFORM axis, not on the last home's. Everything notifyAdmins touches is platform-tier
        // (person_contact to resolve the roster, notification_delivery and in_app_notification to send),
        // and the tickets in hand are detached entities whose fields were loaded inside their own home's
        // transaction. Leaving it pinned to whichever home happened to be last would be an accident that
        // works, not a decision.
        TenantContext.runAsPlatform(() -> notifier.ticketsEscalated(escalated));
        // Loud AND complete: one unreachable silo must not cost every other home its escalation, and it
        // must still fail the run. SoftDeletePurgeJob's doctrine, applied to a fan-out.
        swept.rethrowFirstFailure();
    }

    /**
     * One home's breaches, on the axis the sweep pinned and inside the transaction it opened.
     *
     * <p><b>{@link #BATCH} is per home now, not per installation, and that is a fairness change worth
     * stating.</b> Breaches are ordered within a home from here on and not across the fleet, so a silo
     * with three breaches and the pool with ten thousand each get their own hundred. That is better than
     * the alternative it replaces — a global hundred that the pool would fill every minute, leaving a
     * promoted tenant's breaches permanently behind five thousand pooled ones — and it is the reason the
     * cap can stay a cap rather than needing a cross-home merge.
     */
    private List<Ticket> escalate() {
        Instant now = clock.instant();
        List<Ticket> breached = tickets.lockBreached(now, Limit.of(BATCH));
        for (Ticket ticket : breached) {
            String bumped = bump(ticket.getPriority());
            ticket.escalate(bumped);
            tickets.save(ticket);
            // Both columns this touches — priority and escalated — are rendered by the operator queue,
            // and an escalation the queue does not show is the one drift an operator would act on. Per
            // ticket rather than per home because the row is what changed; the upsert rides this home's
            // own transaction while the tenant is co-located with primary (TicketIndex).
            index.record(ticket);
            Counter.builder("smsone.support.breached")
                    .description("Tickets escalated on an SLA breach, by new priority")
                    .tag("priority", bumped)
                    .register(meters)
                    .increment();
            events.publishEvent(new TicketEscalated(ticket.getId(), ticket.getOrgId(), bumped, now));
            log.info("Ticket {} breached SLA — escalated to {}", ticket.getId(), bumped);
        }
        // Returned rather than notified here. The per-ticket work above is per-ticket by nature — its
        // own row, its own counter sample, its own event for the webhook fan-out. Notifying the admins
        // is NOT: every notifyAdmins call re-resolves the roster, and the roster is the same for every
        // ticket of one sweep and for every HOME of it, so one digest replaces up to (homes × BATCH)
        // identical resolutions.
        return breached;
    }

    private static String bump(String priority) {
        int index = BUMP.indexOf(priority);
        return index < 0 || index + 1 >= BUMP.size() ? "P1" : BUMP.get(index + 1);
    }
}
