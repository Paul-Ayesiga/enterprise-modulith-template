package ug.co.smsone.audit.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/** One append-only audit record: a factual note that {@code action} happened to {@code target}. */
@Entity
@Table(name = "audit_log")
class AuditEntry extends BaseEntity {

    private static final int DETAIL_MAX = 500;
    private static final int TARGET_MAX = 320;

    @Column(name = "org_id")
    private UUID orgId; // null for platform-level (non-org) events

    @Column(nullable = false, length = 80)
    private String action;

    @Column(length = 64)
    private String actor;

    @Column(length = TARGET_MAX)
    private String target;

    @Column(length = DETAIL_MAX)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEntry() {
        // JPA
    }

    static AuditEntry of(UUID orgId, String action, String target, String detail, Instant occurredAt) {
        AuditEntry entry = new AuditEntry();
        entry.orgId = orgId;
        entry.action = action;
        entry.target = truncate(target, TARGET_MAX);
        entry.detail = truncate(detail, DETAIL_MAX);
        entry.occurredAt = occurredAt;
        return entry;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getAction() {
        return action;
    }

    String getActor() {
        return actor;
    }

    String getTarget() {
        return target;
    }

    String getDetail() {
        return detail;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
