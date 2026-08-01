package ug.co.smsone.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** A support ticket. Status and priority drive the SLA clock and the platform queue. */
@Entity
@Table(name = "ticket")
@SQLDelete(sql = "update ticket set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Ticket extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "opener_subject", nullable = false, updatable = false, length = 64)
    private String openerSubject;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(length = 40)
    private String category;

    @Column(nullable = false, length = 2)
    private String priority;

    @Column(nullable = false, length = 25)
    private String status;

    @Column(name = "assignee_subject", length = 64)
    private String assigneeSubject;

    @Column(name = "first_response_at")
    private Instant firstResponseAt;

    @Column(name = "first_response_due_at", nullable = false)
    private Instant firstResponseDueAt;

    @Column(name = "resolution_due_at", nullable = false)
    private Instant resolutionDueAt;

    @Column(nullable = false)
    private boolean escalated;

    protected Ticket() {
        // JPA
    }

    static Ticket open(UUID orgId, String openerSubject, String subject, String category,
            String priority, Instant firstResponseDue, Instant resolutionDue) {
        Ticket ticket = new Ticket();
        ticket.orgId = orgId;
        ticket.openerSubject = openerSubject;
        ticket.subject = subject;
        ticket.category = category;
        ticket.priority = priority;
        ticket.status = "OPEN";
        ticket.firstResponseDueAt = firstResponseDue;
        ticket.resolutionDueAt = resolutionDue;
        return ticket;
    }

    void assign(String assignee) {
        this.assigneeSubject = assignee;
        if ("OPEN".equals(status)) {
            status = "IN_PROGRESS";
        }
    }

    void changeStatus(String newStatus) {
        this.status = newStatus;
    }

    /** First platform reply stamps the first-response clock (only once). */
    void firstResponded(Instant when) {
        if (firstResponseAt == null) {
            firstResponseAt = when;
        }
        if ("OPEN".equals(status)) {
            status = "IN_PROGRESS";
        }
    }

    void escalate(String bumpedPriority) {
        this.priority = bumpedPriority;
        this.escalated = true;
    }

    boolean isTerminal() {
        return "RESOLVED".equals(status) || "CLOSED".equals(status);
    }

    UUID getOrgId() {
        return orgId;
    }

    String getOpenerSubject() {
        return openerSubject;
    }

    String getSubject() {
        return subject;
    }

    String getCategory() {
        return category;
    }

    String getPriority() {
        return priority;
    }

    String getStatus() {
        return status;
    }

    String getAssigneeSubject() {
        return assigneeSubject;
    }

    Instant getFirstResponseAt() {
        return firstResponseAt;
    }

    Instant getResolutionDueAt() {
        return resolutionDueAt;
    }

    boolean isEscalated() {
        return escalated;
    }
}
