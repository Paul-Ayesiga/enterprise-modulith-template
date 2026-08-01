package ug.co.smsone.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import ug.co.smsone.shared.persistence.BaseEntity;

/**
 * One authorized episode of an operator ({@code actorSubject}) acting as another user
 * ({@code targetSubject}), with the reason they gave, the mode they were granted, and the deadline the
 * server set.
 *
 * <p><b>Extends {@link BaseEntity} — deliberately not {@code SoftDeletableEntity}, and not an
 * {@code AggregateRoot}.</b> Making it soft-deletable would hand the operator a delete on the one row
 * that records their own reach into someone else's account: an oversight tool able to erase its own
 * oversight is not an oversight tool. So a session <em>ends</em> ({@link #end}) and the row stays
 * forever — the same reasoning that keeps {@code audit_log} append-only. It is not an aggregate root
 * because nothing outside this module reacts to a session; the durable trail is the {@code audit_log}
 * row written beside it, not a domain event.
 *
 * <p>A session is live only while it is un-ended AND before {@code expiresAt} — {@link #isActive}
 * decides that on read, which is why expiry needs no sweep job.
 */
@Entity
@Table(name = "impersonation_session")
class ImpersonationSession extends BaseEntity {

    @Column(name = "actor_subject", nullable = false, updatable = false, length = 64)
    private String actorSubject;

    @Column(name = "target_subject", nullable = false, updatable = false, length = 64)
    private String targetSubject;

    @Column(name = "org_id", updatable = false)
    private UUID orgId; // null for a session not scoped to one tenant

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ImpersonationMode mode;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_by", length = 64)
    private String endedBy;

    protected ImpersonationSession() {
        // JPA
    }

    static ImpersonationSession open(String actorSubject, String targetSubject, UUID orgId, String reason,
            ImpersonationMode mode, Instant startedAt, Instant expiresAt) {
        ImpersonationSession session = new ImpersonationSession();
        session.actorSubject = actorSubject;
        session.targetSubject = targetSubject;
        session.orgId = orgId;
        session.reason = reason;
        session.mode = mode;
        session.startedAt = startedAt;
        session.expiresAt = expiresAt;
        return session;
    }

    /**
     * Closes the session. Idempotent and one-way: a second call keeps the FIRST ending, because
     * {@code endedAt}/{@code endedBy} answer "when did the reach stop, and who stopped it" and a
     * re-write would move that answer. The caller uses the return value to decide whether anything
     * actually happened worth auditing.
     *
     * @return true when this call is what ended it
     */
    boolean end(String bySubject, Instant when) {
        if (endedAt != null) {
            return false;
        }
        this.endedAt = when;
        this.endedBy = bySubject;
        return true;
    }

    /** Live: neither ended nor past its deadline. Evaluated per request — no sweep job ends a session. */
    boolean isActive(Instant now) {
        return endedAt == null && expiresAt.isAfter(now);
    }

    String getActorSubject() {
        return actorSubject;
    }

    String getTargetSubject() {
        return targetSubject;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getReason() {
        return reason;
    }

    ImpersonationMode getMode() {
        return mode;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getEndedAt() {
        return endedAt;
    }

    String getEndedBy() {
        return endedBy;
    }
}
