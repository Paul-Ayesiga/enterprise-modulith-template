package ug.co.smsone.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An append-only message on a ticket. {@code internal} messages are platform-only notes. */
@Entity
@Table(name = "ticket_message")
class TicketMessage {

    @Id
    private UUID id;

    @Column(name = "ticket_id", nullable = false)
    private UUID ticketId;

    @Column(name = "author_subject", nullable = false, length = 64)
    private String authorSubject;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private boolean internal;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketMessage() {
        // JPA
    }

    static TicketMessage of(UUID ticketId, String authorSubject, String body, boolean internal, Instant when) {
        TicketMessage message = new TicketMessage();
        message.id = UUID.randomUUID();
        message.ticketId = ticketId;
        message.authorSubject = authorSubject;
        message.body = body;
        message.internal = internal;
        message.createdAt = when;
        return message;
    }

    UUID getId() {
        return id;
    }

    String getAuthorSubject() {
        return authorSubject;
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
