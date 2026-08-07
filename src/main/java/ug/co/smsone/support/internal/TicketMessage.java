package ug.co.smsone.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An append-only message on a ticket. {@code internal} messages are platform-only notes.
 *
 * <p>{@code authorPersonId} is a tenant member or a platform operator — one column resolving through
 * one table, where two subject strings once pointed into two different tenancy worlds. {@code
 * internal} stays a VISIBILITY flag and not a discriminator on who wrote the message.
 */
@Entity
@Table(name = "ticket_message")
class TicketMessage {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "author_person_id", nullable = false)
    private UUID authorPersonId;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean internal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketMessage() {
        // JPA
    }

    static TicketMessage of(UUID ticketId, UUID authorPersonId, String body, boolean internal, Instant when) {
        TicketMessage message = new TicketMessage();
        message.id = UUID.randomUUID();
        message.ticketId = ticketId;
        message.authorPersonId = authorPersonId;
        message.body = body;
        message.internal = internal;
        message.createdAt = when;
        return message;
    }

    UUID getId() {
        return id;
    }

    UUID getAuthorPersonId() {
        return authorPersonId;
    }

    String getBody() {
        return body;
    }

    boolean isInternal() {
        return internal;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
