package ug.co.smsone.support.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Window;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.error.ValidationException;
import ug.co.smsone.shared.web.ApiSource;
import ug.co.smsone.shared.web.CursorPageRequest;

/**
 * Ticket lifecycle. A tenant opens and converses on its own org's tickets; platform-support works
 * the queue — assign, public reply (which stamps the first-response clock and notifies the opener),
 * internal note (never shown to the tenant), status transitions. SLA due dates are stamped at open
 * from the seeded per-priority policy.
 */
@Service
class SupportService {

    private static final List<String> PRIORITIES = List.of("P1", "P2", "P3", "P4");
    private static final List<String> STATUSES =
            List.of("OPEN", "IN_PROGRESS", "WAITING_ON_CUSTOMER", "RESOLVED", "CLOSED");

    private final TicketRepository tickets;
    private final TicketMessageRepository messages;
    private final SlaPolicyRepository slaPolicies;
    private final SupportNotifier notifier;
    private final AuditLog auditLog;
    private final Clock clock;

    SupportService(TicketRepository tickets, TicketMessageRepository messages,
            SlaPolicyRepository slaPolicies, SupportNotifier notifier, AuditLog auditLog, Clock clock) {
        this.tickets = tickets;
        this.messages = messages;
        this.slaPolicies = slaPolicies;
        this.notifier = notifier;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    Ticket open(UUID orgId, String opener, String subject, String category, String priority) {
        String normalizedPriority = priority == null ? "P3" : priority.trim().toUpperCase();
        if (!PRIORITIES.contains(normalizedPriority)) {
            throw new ValidationException("priority must be P1, P2, P3 or P4.",
                    ApiSource.pointer("/data/attributes/priority"));
        }
        if (subject == null || subject.isBlank() || subject.length() > 200) {
            throw new ValidationException("subject is required (max 200 characters).",
                    ApiSource.pointer("/data/attributes/subject"));
        }
        SlaPolicy sla = slaPolicies.findByPriority(normalizedPriority)
                .orElseThrow(() -> new NotFoundException("No SLA policy seeded for " + normalizedPriority));
        Instant now = clock.instant();
        Ticket ticket = tickets.save(Ticket.open(orgId, opener, subject.trim(), category, normalizedPriority,
                now.plusSeconds(sla.getFirstResponseMinutes() * 60L),
                now.plusSeconds(sla.getResolutionMinutes() * 60L)));
        auditLog.record("support.ticket_opened", orgId, ticket.getId().toString(), null,
                "priority=" + normalizedPriority);
        notifier.ticketOpened(ticket); // support queue awareness
        return ticket;
    }

    @Transactional(readOnly = true)
    Window<Ticket> listForOrg(UUID orgId, CursorPageRequest page) {
        return tickets.pageByOrg(orgId, page);
    }

    @Transactional(readOnly = true)
    Window<Ticket> queue(String status, CursorPageRequest page) {
        return tickets.pageForQueue(status == null || status.isBlank() ? null : status.trim().toUpperCase(), page);
    }

    @Transactional(readOnly = true)
    Ticket requireInOrg(UUID orgId, UUID id) {
        return tickets.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    @Transactional(readOnly = true)
    Ticket requireAnyOrg(UUID id) {
        return tickets.findById(id).orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    /** Messages a TENANT may see: public only. Platform sees all. */
    @Transactional(readOnly = true)
    List<TicketMessage> messages(UUID ticketId, boolean includeInternal) {
        return messages.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(message -> includeInternal || !message.isInternal())
                .toList();
    }

    /** The tenant's own reply. Moves a WAITING ticket back to IN_PROGRESS. */
    @Transactional
    TicketMessage tenantReply(UUID orgId, UUID id, String opener, String body) {
        Ticket ticket = requireInOrg(orgId, id);
        requireBody(body);
        if ("WAITING_ON_CUSTOMER".equals(ticket.getStatus())) {
            ticket.changeStatus("IN_PROGRESS");
            tickets.save(ticket);
        }
        return messages.save(TicketMessage.of(ticket.getId(), opener, body.trim(), false, clock.instant()));
    }

    /** A platform reply — public (stamps first response, notifies the opener) or an internal note. */
    @Transactional
    TicketMessage platformReply(UUID id, String author, String body, boolean internal) {
        Ticket ticket = requireAnyOrg(id);
        requireBody(body);
        if (!internal) {
            ticket.firstResponded(clock.instant());
            tickets.save(ticket);
            notifier.ticketReplied(ticket);
        }
        return messages.save(TicketMessage.of(ticket.getId(), author, body.trim(), internal, clock.instant()));
    }

    @Transactional
    Ticket assign(UUID id, String assignee) {
        Ticket ticket = requireAnyOrg(id);
        ticket.assign(assignee);
        Ticket saved = tickets.save(ticket);
        auditLog.record("support.ticket_assigned", ticket.getOrgId(), id.toString(), null, "assignee=" + assignee);
        return saved;
    }

    @Transactional
    Ticket changeStatus(UUID id, String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(normalized)) {
            throw new ValidationException("status must be one of " + STATUSES + ".",
                    ApiSource.pointer("/data/attributes/status"));
        }
        Ticket ticket = requireAnyOrg(id);
        String before = ticket.getStatus();
        ticket.changeStatus(normalized);
        Ticket saved = tickets.save(ticket);
        auditLog.record("support.ticket_status_changed", ticket.getOrgId(), id.toString(),
                "status=" + before, "status=" + normalized);
        return saved;
    }

    private static void requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new ValidationException("body must not be blank.", ApiSource.pointer("/data/attributes/body"));
        }
    }
}
