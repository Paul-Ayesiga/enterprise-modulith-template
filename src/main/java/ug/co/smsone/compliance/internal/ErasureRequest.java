package ug.co.smsone.compliance.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A GDPR art. 17 request and its outcome. Kept as a compliance record — not soft-deletable. */
@Entity
@Table(name = "erasure_request")
class ErasureRequest {

    @Id
    private UUID id;

    @Column(nullable = false, length = 64)
    private String subject;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

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

    static ErasureRequest received(String subject, String requestedBy, Instant when) {
        ErasureRequest request = new ErasureRequest();
        request.id = UUID.randomUUID();
        request.subject = subject;
        request.requestedBy = requestedBy;
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

    String getSubject() {
        return subject;
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
