package ug.co.smsone.scheduler.internal;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.persistence.SoftDeleteProperties;

/**
 * Retention for soft-deleted aggregates: once {@code deleted_at} falls outside the window the row is
 * hard deleted, which is what turns "deleted" from a hidden state into an erasure guarantee.
 *
 * <p>Native SQL is not a shortcut here, it is the only option: {@code @SQLRestriction("deleted_at is
 * null")} makes these rows invisible to every HQL/criteria query, so JPA cannot see the very rows this
 * job exists to remove.
 *
 * <p>A failure on one table is logged and the run continues, then the run still fails. Unlike the other
 * purge jobs, aborting here is not free: the tables are ordered, so a table that fails every night
 * starves every table after it — and a foreign-key violation on {@code org_role} IS permanent, because
 * the offending row does not go away on its own. Erasure would quietly stop platform-wide while the
 * only symptom is one stack trace at 04:00. Loud AND complete, not loud instead of complete.
 */
@Component
class SoftDeletePurgeJob {

    private static final Logger log = LoggerFactory.getLogger(SoftDeletePurgeJob.class);

    /**
     * Purge order — CHILDREN BEFORE PARENTS, and the order below is load-bearing, not cosmetic.
     *
     * <p>The only foreign key between soft-deletable tables is {@code membership.role_id -> org_role(id)}
     * (V11), and it has no {@code on delete cascade}. That FK is blind to {@code deleted_at}: a
     * soft-deleted membership still pins its role. Purging {@code org_role} first would therefore fail
     * with a constraint violation the moment a role and one of its memberships age out together — the
     * normal case, since removing the last member is usually what precedes deleting the role.
     *
     * <p>{@code role_permission} (V11) and {@code webhook_delivery} (V15) are deliberately ABSENT rather
     * than forgotten: both FKs are {@code on delete cascade}, so Postgres removes them with their parent
     * row. Adding explicit steps would be dead code.
     *
     * <p>The remaining tables are unordered on purpose. {@code organization}, {@code app_user},
     * {@code membership} and {@code webhook_subscription} are linked by Keycloak identifiers
     * ({@code org_id}, {@code user_subject}) held as plain columns, never as foreign keys — cross-module
     * references in this codebase are by id, so the database imposes no order on them.
     *
     * <p>Package-private so the integration test can check this list against every
     * {@code SoftDeletableEntity} in the metamodel: a table added to the mapping but not to this list
     * would leak deleted rows forever, and silently.
     */
    static final List<String> PURGE_ORDER = List.of(
            "membership",
            "org_role",
            "organization",
            "app_user",
            "webhook_subscription",
            "setting",
            "feature_flag",
            "translation",
            "document");

    /**
     * Bounded batch: the inner select is what the {@code idx_<table>_deleted} partial indexes (V17) were
     * created for, and oldest-first keeps successive batches deterministic. The table name and the
     * guard are interpolated from constants in this class — never from input.
     */
    private static final String DELETE_BATCH = """
            delete from %1$s where id in (
                select id from %1$s where deleted_at < ?%2$s order by deleted_at limit ?)
            """;

    /**
     * Extra predicates that skip rows a purge could not delete anyway. The order above resolves the
     * <em>expected</em> collision (a role and its memberships aging out together); this one covers the
     * pathological state the order cannot help with — a LIVE membership pinning a deleted role, which
     * {@code SoftDeleteRecovery.restore} can produce by restoring a membership whose role is still
     * deleted. There is no ordering that clears it and the row does not age out, so without this the
     * same FK violation fails the same table every single night.
     */
    private static final Map<String, String> GUARDS = Map.of(
            "org_role", " and not exists (select 1 from membership m where m.role_id = org_role.id)");

    private final JdbcTemplate jdbc;
    private final SoftDeleteProperties properties;
    private final Clock clock;

    SoftDeletePurgeJob(JdbcTemplate jdbc, SoftDeleteProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.scheduler.soft-delete-purge-cron:0 0 4 * * *}")
    @SchedulerLock(name = "soft-delete-purge", lockAtMostFor = "PT30M")
    public void purgeExpiredSoftDeletes() {
        if (!properties.purgeEnabled()) {
            log.debug("Soft-delete purge is disabled (app.persistence.soft-delete.purge-enabled)");
            return;
        }
        Instant cutoff = clock.instant().minus(properties.retention());

        // Not @Transactional on purpose: every batch commits on its own connection, so a long backlog
        // never becomes one giant transaction holding row locks against live traffic.
        Map<String, Integer> purged = new LinkedHashMap<>();
        int total = 0;
        RuntimeException firstFailure = null;
        for (String table : PURGE_ORDER) {
            try {
                int rows = purgeTable(table, cutoff);
                total += rows;
                if (rows > 0) {
                    purged.put(table, rows);
                }
            } catch (RuntimeException ex) {
                // Isolated per table: the tables are independent, so one poisoned FK must not cost
                // every table after it its retention. Kept and rethrown below so the run still fails.
                log.error("Soft-delete purge failed on {} (continuing with the remaining tables)", table, ex);
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
        }

        if (total > 0) {
            log.info("Soft-delete purge removed {} rows deleted before {} (retention {}): {}",
                    total, cutoff, properties.retention(), purged);
        } else if (firstFailure == null) {
            log.debug("Soft-delete purge found nothing deleted before {}", cutoff);
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private int purgeTable(String table, Instant cutoff) {
        String sql = DELETE_BATCH.formatted(table, GUARDS.getOrDefault(table, ""));
        int total = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            int rows = jdbc.update(sql, Timestamp.from(cutoff), properties.batchSize());
            total += rows;
            if (rows < properties.batchSize()) {
                return total;
            }
        }
        log.warn("Soft-delete purge stopped at the {}-batch cap on {} with a backlog remaining; "
                + "raise app.persistence.soft-delete.max-batches or batch-size", properties.maxBatches(), table);
        return total;
    }
}
