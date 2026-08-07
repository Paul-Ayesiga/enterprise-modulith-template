package ug.co.smsone.compliance.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A GDPR art. 17 request and its outcome. Kept as a compliance record — not soft-deletable.
 *
 * <p>The two person ids stay separate on purpose (V34): on a self-service request they are equal, on an
 * admin-initiated one they are not, and which of the two it was is the question an auditor asks first.
 * {@code requestedByPersonId} is NOT NULL, so a machine cannot file one — an erasure with no accountable
 * human is not a record worth keeping.
 */
@Entity
@Table(name = "erasure_request")
class ErasureRequest {

    @Id
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "requested_by_person_id", nullable = false)
    private UUID requestedByPersonId;

    @Column(nullable = false, length = 20)
    private String status; // RECEIVED | EXECUTED | REFUSED

    @Column(length = 300)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ErasureRequest() {
        // JPA
    }

    static ErasureRequest received(UUID personId, UUID requestedByPersonId, Instant when) {
        ErasureRequest request = new ErasureRequest();
        request.id = UUID.randomUUID();
        request.personId = personId;
        request.requestedByPersonId = requestedByPersonId;
        request.status = "RECEIVED";
        request.createdAt = when;
        return request;
    }

    void executed(Instant when) {
        this.status = "EXECUTED";
        this.detail = "Data soft-deleted; hard erasure follows at the retention window.";
        this.updatedAt = when;
    }

    void refused(String reason, Instant when) {
        this.status = "REFUSED";
        this.detail = reason;
        this.updatedAt = when;
    }

    UUID getId() {
        return id;
    }

    UUID getPersonId() {
        return personId;
    }

    String getStatus() {
        return status;
    }

    String getDetail() {
        return detail;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
