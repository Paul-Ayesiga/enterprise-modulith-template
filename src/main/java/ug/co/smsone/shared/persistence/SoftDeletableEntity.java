package ug.co.smsone.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import ug.co.smsone.shared.error.ConflictException;

/**
 * An aggregate whose deletion is <em>recorded</em> rather than executed: the row survives with
 * {@code deleted_at} set, and every ordinary query stops seeing it.
 *
 * <p>Each concrete entity must declare its own pair — Hibernate does not inherit either from a mapped
 * superclass, so a missing one is a silent hard delete or a silent leak of deleted rows:
 * <pre>
 * &#64;SQLDelete(sql = "update &lt;table&gt; set deleted_at = now(), version = version + 1
 *                   where id = ? and version = ?")
 * &#64;SQLRestriction("deleted_at is null")
 * </pre>
 * The {@code version} predicate keeps optimistic locking honest: deleting a stale instance affects
 * zero rows and Hibernate raises the usual concurrency failure instead of silently winning.
 *
 * <p>Bumping {@code version} makes that symmetric, and it is the half that is easy to miss. A hard
 * DELETE removes the row, so any instance loaded before it is doomed to fail on its own flush; a soft
 * delete leaves the row there. Without the bump, a concurrent flush still matches {@code version = ?},
 * and because that instance's in-memory {@code deletedAt} is null the UPDATE writes {@code deleted_at =
 * null} back — the deletion is silently undone and the caller sees a 200.
 *
 * <p><b>Who deleted it</b> is deliberately not a column here. {@code @SQLDelete} runs as raw SQL and
 * cannot see the security context, so a {@code deleted_by} column would be reliably populated only on
 * the paths that bypass it — the worst of both worlds. The actor already lives in the {@code audit_log}
 * row every deleting service writes, which is the record that is queried anyway.
 *
 * <p><b>Uniqueness.</b> A soft-deleted row still occupies its unique key, so every unique constraint on
 * a soft-deletable table is a PARTIAL index ({@code where deleted_at is null}). Without that, deleting
 * a role named {@code AUDITOR} would permanently forbid creating another one.
 */
@MappedSuperclass
public abstract class SoftDeletableEntity extends AggregateRoot {

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * Records the deletion in-model. The usual path is {@code repository.delete(entity)}, which
     * {@code @SQLDelete} turns into the same update; this exists for callers that need the transition
     * to be part of an aggregate's own behaviour (and for restoring).
     */
    public void markDeleted(Instant when) {
        if (isDeleted()) {
            throw new ConflictException("This record is already deleted.");
        }
        this.deletedAt = when;
    }

    /**
     * Brings a deleted row back. Callers must re-check uniqueness first: while it was deleted, its key
     * was free, so a live row may now hold it and restoring would violate the partial unique index.
     *
     * <p>Reaching a deleted instance to call this requires {@link SoftDeleteRecovery} — no repository
     * can load one, because {@code @SQLRestriction} hides it from every JPA query.
     */
    public void restore() {
        if (!isDeleted()) {
            throw new ConflictException("This record is not deleted.");
        }
        this.deletedAt = null;
    }
}
