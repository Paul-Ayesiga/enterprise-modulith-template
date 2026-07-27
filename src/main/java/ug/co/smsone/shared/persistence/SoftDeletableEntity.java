package ug.co.smsone.shared.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/**
 * Adds the soft-delete column and state transitions. Concrete entities pair this with
 * {@code @SQLDelete(sql = "update <table> set deleted_at = now() where id = ? and version = ?")}
 * and {@code @SQLRestriction("deleted_at is null")} — Hibernate does not inherit those from a
 * mapped superclass, so each entity declares its own.
 */
@MappedSuperclass
public abstract class SoftDeletableEntity extends AggregateRoot {

    @Column
    private Instant deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void markDeleted(Instant when) {
        this.deletedAt = when;
    }

    public void restore() {
        this.deletedAt = null;
    }
}
