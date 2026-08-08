package ug.co.smsone.support.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
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
     * The tenant axis this cross-tenant sweep runs on. It names no organization deliberately: an org in
     * no {@code organization} row can only ever resolve to the shared {@code tenant_pool}, so this IS
     * the pooled schema's axis — the same constant and reasoning as {@code ExchangeScheduleFiringJob}
     * and {@code WebhookRetentionJob}.
     *
     * <p>PHASE 5 makes this a loop over {@code platform.tenant_placement}, one transaction per home,
     * and {@link #BATCH} becomes per-home rather than global — a fairness change, since breaches are
     * ordered within a home from then on and not across the installation.
     * {@link SupportNotifier#ticketsEscalated} stays one digest per sweep by collecting across the loop
     * rather than inside it.
     */
    private static final UUID POOLED_TENANT = new UUID(0L, 0L);

    private final TicketRepository tickets;
    private final SupportNotifier notifier;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate transactions;

    SlaEscalationJob(TicketRepository tickets, SupportNotifier notifier, ApplicationEventPublisher events,
            MeterRegistry meters, Clock clock, TransactionTemplate transactions) {
        this.tickets = tickets;
        this.notifier = notifier;
        this.events = events;
        this.meters = meters;
        this.clock = clock;
        this.transactions = transactions;
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
     * <p><b>A TENANT axis since Phase 2, not the platform one it used to take.</b> {@code ticket} is
     * tenant-tier (ADR 0010 §2), so on the platform axis {@code lockBreached} does not sweep zero rows —
     * it fails outright with {@code relation "ticket" does not exist}, inside a scheduled job whose
     * exception nobody reads at 04:00, and every SLA breach on the installation stops escalating. The
     * pin is what makes the statement resolve at all.
     */
    @Scheduled(cron = "${app.scheduler.support-escalation-cron:15 * * * * *}")
    @SchedulerLock(name = "support-sla-escalation", lockAtMostFor = "PT5M")
    public void escalateBreaches() {
        // Declares the tenant axis, then opens the transaction inside it. ADR 0010 §3.4.
        TenantContext.runAs(POOLED_TENANT, () -> transactions.executeWithoutResult(tx -> escalate()));
    }

    private void escalate() {
        Instant now = clock.instant();
        List<Ticket> breached = tickets.lockBreached(now, Limit.of(BATCH));
        for (Ticket ticket : breached) {
            String bumped = bump(ticket.getPriority());
            ticket.escalate(bumped);
            tickets.save(ticket);
            Counter.builder("smsone.support.breached")
                    .description("Tickets escalated on an SLA breach, by new priority")
                    .tag("priority", bumped)
                    .register(meters)
                    .increment();
            events.publishEvent(new TicketEscalated(ticket.getId(), ticket.getOrgId(), bumped, now));
            log.info("Ticket {} breached SLA — escalated to {}", ticket.getId(), bumped);
        }
        // Outside the loop on purpose. The per-ticket work above is per-ticket by nature — its own
        // row, its own counter sample, its own event for the webhook fan-out. Notifying the admins is
        // NOT: every notifyAdmins call re-resolves the roster, and the roster is the same for all
        // BATCH tickets of one sweep, so one digest replaces up to BATCH identical resolutions.
        notifier.ticketsEscalated(breached);
    }

    private static String bump(String priority) {
        int index = BUMP.indexOf(priority);
        return index < 0 || index + 1 >= BUMP.size() ? "P1" : BUMP.get(index + 1);
    }
}
