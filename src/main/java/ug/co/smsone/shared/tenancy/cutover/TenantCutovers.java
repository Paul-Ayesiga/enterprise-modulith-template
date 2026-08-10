package ug.co.smsone.shared.tenancy.cutover;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Every statement there is against {@code platform.tenant_cutover} (V60) — the same one-file rule as
 * {@code TenantPlacements} and {@code TenantFreezes}, and for the same reason: "which writes can
 * change whether a tenant is mid-move between databases, and under what condition" must be a question
 * with one file for an answer.
 *
 * <p><strong>Every statement names {@code platform.}</strong> — this table is read by the migration
 * runner (no axis at all) and written by {@code TenantCutover} on the platform axis, so it must
 * resolve identically from anywhere. A finished cutover's row is deleted, never marked done: the
 * table is the set of moves in flight, and a row's mere existence is what two refusals key on
 * ({@code TenantMigrationRunner}'s and {@code TenantPlacements.beginRelocation}'s).
 *
 * <p>The transitions are compare-and-swap on the state they expect, like {@code TenantPlacements}'
 * three relocation writes: a re-run or a race updates nothing and is told so by the row count.
 */
@Component
public class TenantCutovers {

    private static final String COLUMNS =
            "org_id, schema_name, source_datasource, target_datasource, state, started_at, cut_at,"
                    + " updated_at";

    private final JdbcTemplate jdbc;

    TenantCutovers(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records a cutover as begun, and answers whether this call was the one that recorded it — the
     * {@code announce} shape: the primary key makes two concurrent cutovers of one tenant impossible,
     * and the loser hears it from the row count rather than from an exception it has to classify.
     */
    public boolean begin(UUID orgId, String schemaName, String sourceDatasource, String targetDatasource) {
        return jdbc.update("""
                insert into platform.tenant_cutover (%s)
                values (?, ?, ?, ?, 'SYNCING', now(), null, now())
                on conflict (org_id) do nothing
                """.formatted(COLUMNS),
                orgId, schemaName, sourceDatasource, targetDatasource) == 1;
    }

    /** The flip's companion write: SYNCING → CUT, stamping {@code cut_at}. Call it in the flip's transaction. */
    public boolean markCut(UUID orgId) {
        return jdbc.update("""
                update platform.tenant_cutover
                   set state = 'CUT', cut_at = now(), updated_at = now()
                 where org_id = ? and state = 'SYNCING'
                """, orgId) == 1;
    }

    /** CUT → WATCHING, once the reverse stream is confirmed live — the moment rollback becomes cheap. */
    public boolean markWatching(UUID orgId) {
        return jdbc.update("""
                update platform.tenant_cutover
                   set state = 'WATCHING', updated_at = now()
                 where org_id = ? and state = 'CUT'
                """, orgId) == 1;
    }

    /** Removes the row — decommission, rollback or abort. Idempotent; the caller says which it was. */
    public boolean end(UUID orgId) {
        return jdbc.update("delete from platform.tenant_cutover where org_id = ?", orgId) > 0;
    }

    public Optional<Cutover> find(UUID orgId) {
        return jdbc.query("select " + COLUMNS + " from platform.tenant_cutover where org_id = ?",
                mapper(), orgId).stream().findFirst();
    }

    /** Every move in flight, oldest first — the operator's read and the abandoned-slot detector's input. */
    public List<Cutover> inFlight() {
        return jdbc.query("select " + COLUMNS + " from platform.tenant_cutover order by started_at",
                mapper());
    }

    private static RowMapper<Cutover> mapper() {
        return TenantCutovers::map;
    }

    private static Cutover map(ResultSet rs, int row) throws SQLException {
        Timestamp cutAt = rs.getTimestamp("cut_at");
        return new Cutover(
                rs.getObject("org_id", UUID.class),
                rs.getString("schema_name"),
                rs.getString("source_datasource"),
                rs.getString("target_datasource"),
                State.valueOf(rs.getString("state")),
                rs.getTimestamp("started_at").toInstant(),
                cutAt == null ? null : cutAt.toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** V60's three states. See the migration header for what each one licenses. */
    public enum State {
        SYNCING, CUT, WATCHING
    }

    /**
     * One row of {@code platform.tenant_cutover}.
     *
     * @param cutAt null while SYNCING — the flip has not happened, so there is no instant to record
     */
    public record Cutover(UUID orgId, String schemaName, String sourceDatasource,
            String targetDatasource, State state, Instant startedAt, Instant cutAt, Instant updatedAt) {
    }
}
