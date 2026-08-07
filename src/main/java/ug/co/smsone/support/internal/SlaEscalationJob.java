package ug.co.smsone.support.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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

    private final TicketRepository tickets;
    private final SupportNotifier notifier;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final Clock clock;

    SlaEscalationJob(TicketRepository tickets, SupportNotifier notifier, ApplicationEventPublisher events,
            MeterRegistry meters, Clock clock) {
        this.tickets = tickets;
        this.notifier = notifier;
        this.events = events;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.scheduler.support-escalation-cron:15 * * * * *}")
    @SchedulerLock(name = "support-sla-escalation", lockAtMostFor = "PT5M")
    @Transactional
    public void escalateBreaches() {
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
