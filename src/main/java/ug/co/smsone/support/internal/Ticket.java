package ug.co.smsone.support.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * A support ticket. Status and priority drive the SLA clock and the platform queue.
 *
 * <p>The two people on it live in different tenancy worlds and resolve through one table now: the
 * opener is a member of THIS org, the assignee is a platform operator who is a member of no tenant
 * at all (V36). Both are {@code person.id} soft refs — support is not the identity module.
 *
 * <p>{@code subject} is the ticket's TITLE and keeps its name: it was never a principal, and
 * renaming it to match a sweep over identity columns would make the field lie.
 */
@Entity
@Table(name = "ticket")
@SQLDelete(sql = "update ticket set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class Ticket extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "opener_person_id", nullable = false, updatable = false)
    private UUID openerPersonId;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(length = 40)
    private String category;

    @Column(nullable = false, length = 2)
    private String priority;

    @Column(nullable = false, length = 25)
    private String status;

    @Column(name = "assignee_person_id")
    private UUID assigneePersonId;

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

    static Ticket open(UUID orgId, UUID openerPersonId, String subject, String category,
            String priority, Instant firstResponseDue, Instant resolutionDue) {
        Ticket ticket = new Ticket();
        ticket.orgId = orgId;
        ticket.openerPersonId = openerPersonId;
        ticket.subject = subject;
        ticket.category = category;
        ticket.priority = priority;
        ticket.status = "OPEN";
        ticket.firstResponseDueAt = firstResponseDue;
        ticket.resolutionDueAt = resolutionDue;
        return ticket;
    }

    void assign(UUID assigneePersonId) {
        this.assigneePersonId = assigneePersonId;
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

    UUID getOrgId() {
        return orgId;
    }

    UUID getOpenerPersonId() {
        return openerPersonId;
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

    UUID getAssigneePersonId() {
        return assigneePersonId;
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
