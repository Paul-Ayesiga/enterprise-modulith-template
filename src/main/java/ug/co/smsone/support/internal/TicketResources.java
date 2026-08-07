package ug.co.smsone.support.internal;

import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.web.ResourceObject;

final class TicketResources {

    static final String TICKET_TYPE = "ticket";
    static final String MESSAGE_TYPE = "ticket-message";

    /**
     * {@code openerPersonId} / {@code assigneePersonId} / {@code authorPersonId} say what they hold.
     * The old {@code opener} and {@code assignee} carried a Keycloak subject and a client had no way
     * to tell which of the two identifier spaces a value came from; these are {@code person.id}s, the
     * same ids {@code /api/v1/admin/users/{id}} answers to, so a client can follow them.
     *
     * <p>{@code subject} stays the ticket's title.
     */
    record TicketAttributes(String orgId, String openerPersonId, String subject, String category,
            String priority, String status, String assigneePersonId, boolean escalated,
            Instant firstResponseAt, Instant resolutionDueAt, Instant createdAt) {
    }

    record MessageAttributes(String authorPersonId, String body, boolean internal, Instant createdAt) {
    }

    private TicketResources() {
    }

    static ResourceObject toResource(Ticket ticket) {
        return new ResourceObject(ticket.getId().toString(), TICKET_TYPE,
                new TicketAttributes(ticket.getOrgId().toString(), id(ticket.getOpenerPersonId()),
                        ticket.getSubject(), ticket.getCategory(), ticket.getPriority(), ticket.getStatus(),
                        id(ticket.getAssigneePersonId()), ticket.isEscalated(), ticket.getFirstResponseAt(),
                        ticket.getResolutionDueAt(), ticket.getCreatedAt()));
    }

    static ResourceObject toResource(TicketMessage message) {
        return new ResourceObject(message.getId().toString(), MESSAGE_TYPE,
                new MessageAttributes(id(message.getAuthorPersonId()), message.getBody(),
                        message.isInternal(), message.getCreatedAt()));
    }

    /** An unassigned ticket renders {@code null}, not the string "null". */
    private static String id(UUID personId) {
        return personId == null ? null : personId.toString();
    }
}
