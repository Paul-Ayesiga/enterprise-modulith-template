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

    /**
     * The same resource, rendered from {@code platform.ticket_index} rather than from the tenant's own
     * row — the operator queue's page since V61.
     *
     * <p><b>It maps into the SAME {@link TicketAttributes} record, and that is the point of the
     * projection's column set.</b> The queue is a wire contract: a caller cannot see which side of the
     * tier boundary a page came from, and would have no way to react if it could. Any attribute this
     * method cannot fill is an attribute V61 is missing, which is why {@code opener_person_id},
     * {@code category} and {@code first_response_at} are columns of that table despite not appearing in
     * ADR 0010 §5.1's original sketch of it.
     */
    static ResourceObject toResource(TicketIndex.Row row) {
        return new ResourceObject(row.ticketId().toString(), TICKET_TYPE,
                new TicketAttributes(row.orgId().toString(), id(row.openerPersonId()), row.subject(),
                        row.category(), row.priority(), row.status(), id(row.assigneePersonId()),
                        row.escalated(), row.firstResponseAt(), row.resolutionDueAt(), row.createdAt()));
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
