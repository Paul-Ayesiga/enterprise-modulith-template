package ug.co.smsone.support.internal;

import java.time.Instant;
import ug.co.smsone.shared.web.ResourceObject;

final class TicketResources {

    static final String TICKET_TYPE = "ticket";
    static final String MESSAGE_TYPE = "ticket-message";

    record TicketAttributes(String orgId, String opener, String subject, String category,
            String priority, String status, String assignee, boolean escalated,
            Instant firstResponseAt, Instant resolutionDueAt, Instant createdAt) {
    }

    record MessageAttributes(String author, String body, boolean internal, Instant createdAt) {
    }

    private TicketResources() {
    }

    static ResourceObject toResource(Ticket ticket) {
        return new ResourceObject(ticket.getId().toString(), TICKET_TYPE,
                new TicketAttributes(ticket.getOrgId().toString(), ticket.getOpenerSubject(),
                        ticket.getSubject(), ticket.getCategory(), ticket.getPriority(), ticket.getStatus(),
                        ticket.getAssigneeSubject(), ticket.isEscalated(), ticket.getFirstResponseAt(),
                        ticket.getResolutionDueAt(), ticket.getCreatedAt()));
    }

    static ResourceObject toResource(TicketMessage message) {
        return new ResourceObject(message.getId().toString(), MESSAGE_TYPE,
                new MessageAttributes(message.getAuthorSubject(), message.getBody(),
                        message.isInternal(), message.getCreatedAt()));
    }
}
