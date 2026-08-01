package ug.co.smsone.compliance.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An active-until-released legal hold. Never soft-deleted; a released row is the trail. */
@Entity
@Table(name = "legal_hold")
class LegalHold {

    @Id
    private UUID id;

    @Column(nullable = false, length = 10)
    private String scope; // SUBJECT | ORG

    @Column(length = 64)
    private String subject;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 300)
    private String reason;

    @Column(name = "placed_by", nullable = false, length = 100)
    private String placedBy;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "released_by", length = 100)
    private String releasedBy;

    protected LegalHold() {
        // JPA
    }

    static LegalHold onSubject(String subject, String reason, String placedBy, Instant when) {
        LegalHold hold = new LegalHold();
        hold.id = UUID.randomUUID();
        hold.scope = "SUBJECT";
        hold.subject = subject;
        hold.reason = reason;
        hold.placedBy = placedBy;
        hold.placedAt = when;
        return hold;
    }

    static LegalHold onOrg(UUID orgId, String reason, String placedBy, Instant when) {
        LegalHold hold = new LegalHold();
        hold.id = UUID.randomUUID();
        hold.scope = "ORG";
        hold.orgId = orgId;
        hold.reason = reason;
        hold.placedBy = placedBy;
        hold.placedAt = when;
        return hold;
    }

    void release(String by, Instant when) {
        this.releasedAt = when;
        this.releasedBy = by;
    }

    boolean isActive() {
        return releasedAt == null;
    }

    UUID getId() {
        return id;
    }

    String getScope() {
        return scope;
    }

    String getSubject() {
        return subject;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getReason() {
        return reason;
    }

    Instant getPlacedAt() {
        return placedAt;
    }

    Instant getReleasedAt() {
        return releasedAt;
    }
}
