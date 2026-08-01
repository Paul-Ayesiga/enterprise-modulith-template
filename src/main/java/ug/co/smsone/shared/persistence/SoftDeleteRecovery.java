package ug.co.smsone.shared.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ug.co.smsone.shared.error.ConflictException;

/**
 * The only way back from a soft delete.
 *
 * <p>{@code @SQLRestriction("deleted_at is null")} is applied to every HQL and criteria query, which
 * means a deleted row cannot be loaded through <em>any</em> repository — including the repository that
 * would restore it. Without this seam {@link SoftDeletableEntity#restore()} is unreachable code and
 * "soft" delete is indistinguishable from a hard one outside the database.
 *
 * <p>Native SQL is the escape hatch: Hibernate passes it through untouched, so the restriction does not
 * apply. That is deliberately confined to this one class rather than spread across repositories, so
 * every path that can see deleted data is in a single reviewable place.
 */
@Component
public class SoftDeleteRecovery {

    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;

    public SoftDeleteRecovery(EntityManager entityManager, JdbcTemplate jdbc) {
        this.entityManager = entityManager;
        this.jdbc = jdbc;
    }

    /**
     * Loads a soft-deleted aggregate by id. Empty when the id is unknown <em>or</em> the row is live —
     * callers restoring something want the deleted one specifically, and silently returning a live row
     * would turn a no-op restore into a success.
     */
    @SuppressWarnings("unchecked") // createNativeQuery(sql, Class) is the raw JPA API; the Class arg types the rows
    public <T extends SoftDeletableEntity> Optional<T> findDeleted(Class<T> type, UUID id) {
        // The table name comes from the entity's own @Table, never from caller input, so it cannot be
        // an injection vector; the id stays a bound parameter regardless.
        String sql = "select * from " + tableOf(type) + " where id = ? and deleted_at is not null";
        List<T> rows = entityManager.createNativeQuery(sql, type).setParameter(1, id).getResultList();
        return rows.stream().findFirst();
    }

    /**
     * Clears the deletion stamp.
     *
     * <p>{@code entity.restore()} runs first purely for its invariant check — restoring something that
     * is not deleted is a caller bug worth surfacing. The write itself is native SQL, not
     * {@code merge()}: merge re-reads the row by id to compute the diff, that read goes through
     * {@code @SQLRestriction}, finds nothing, and the flush fails as a stale-state conflict. The
     * restriction hides the row from the very statement meant to un-hide it.
     *
     * <p>The version is bumped so optimistic locking stays coherent for anyone holding a stale copy.
     *
     * <p>Uniqueness is the caller's problem: while the row was deleted its key was free, so a live row
     * may now hold it. Which key matters is domain knowledge, so it belongs in the owning module — a
     * {@link org.springframework.dao.DataIntegrityViolationException} out of here is exactly that
     * collision.
     */
    @Transactional
    public <T extends SoftDeletableEntity> void restore(T entity) {
        entity.restore();
        int updated = jdbc.update("update " + tableOf(entity.getClass())
                + " set deleted_at = null, version = version + 1 where id = ? and deleted_at is not null",
                entity.getId());
        if (updated == 0) {
            throw new ConflictException("This record was restored or purged by someone else.");
        }
    }

    private static String tableOf(Class<?> type) {
        Table table = type.getAnnotation(Table.class);
        if (table == null || table.name().isBlank()) {
            // Every soft-deletable entity in this codebase names its table explicitly; falling back to
            // a derived name would guess, and guessing wrong here reads rows from the wrong table.
            throw new IllegalStateException(
                    type.getSimpleName() + " must declare @Table(name = ...) to be recoverable.");
        }
        return table.name();
    }
}
