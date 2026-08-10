package ug.co.smsone.shared.tenancy.cutover;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.TenantSchemas;
import ug.co.smsone.shared.tenancy.placement.TenantSchemaMigrator;
import ug.co.smsone.shared.tenancy.promotion.TenantRows;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.Plan;

/**
 * Builds the database a tenant is cutting over TO: ADR 0011 §7.2 step 1, plus the one preparation
 * step the measured trap transcript added (see {@link #makeSyncable}).
 *
 * <p>A remote destination gets {@code t_<hex>} at head, {@code ext} (+ {@code pg_trgm}),
 * {@code no_tenant}, and a {@code platform} schema containing <strong>exactly
 * {@code event_publication}</strong> — §5.1's tripwire: after the hop, any platform-qualified
 * statement running on a connection borrowed under this tenant's axis is a statement on the wrong
 * database, and against a minimal schema it fails as {@code relation "platform.x" does not exist} — a
 * 500 with a request id — where a full empty copy would swallow it silently, forever. That is also
 * why {@link #build} REFUSES a destination whose {@code platform} schema already holds more: a
 * populated platform schema means this "destination" is somebody's platform database, and cutting a
 * tenant into it would both collide with its owner and disarm the tripwire.
 *
 * <p>The tenant schema itself comes from {@link TenantSchemaMigrator} — the same code path as a silo
 * signup, a promotion and the fleet runner, per the no-second-migrator rule this package inherits
 * from {@code TenantProvisioner}'s javadoc. A reverse cutover's destination is the primary itself, and
 * then only the migrator runs: the real {@code platform} is there and must not be touched.
 */
@Component
class CutoverDestinations {

    private static final Logger log = LoggerFactory.getLogger(CutoverDestinations.class);

    /** §5.1: the one platform-tier table whose row must COMMIT WITH the tenant transaction. */
    private static final String OUTBOX = "event_publication";

    private final TenantSchemaMigrator migrator;
    private final JdbcTemplate primaryCatalog;
    private final TenantTierTables tables;
    private final TenantRows rows;

    CutoverDestinations(TenantSchemaMigrator migrator, JdbcTemplate jdbc, TenantTierTables tables,
            TenantRows rows) {
        this.migrator = migrator;
        this.primaryCatalog = jdbc;
        this.tables = tables;
        this.rows = rows;
    }

    /**
     * Everything up to (not including) the subscription: schemas, extension, minimal platform, the
     * tenant tier at head, and the emptiness proof. Idempotent — a re-run after a failed build finds
     * its own half-work and finishes it, which is what makes §7.2 step 1's failure story ("delete and
     * retry") also simply "retry".
     *
     * @return the tenant-sequence version the destination schema is now at
     */
    String build(DataSource target, String schema, boolean targetIsPrimary) {
        String silo = TenantSchemas.requireSiloSchema(schema);
        JdbcTemplate destination = new JdbcTemplate(target);
        if (!targetIsPrimary) {
            bootstrapRemote(destination);
        }
        resetALeftoverBuild(destination, silo);
        String version = migrator.migrate(target, silo);

        // The promoter's refuseIfTheDestinationIsOccupied, one database over and stricter: ANY row in
        // the freshly built tier is somebody's data this cutover did not put there, and tablesync into
        // a populated table is a duplicate-key storm (measured). The plan is read from the DESTINATION
        // catalogue so the check cannot be blinded by a source that is a migration behind.
        Plan plan = tables.planFor(destination, silo);
        Map<String, Long> occupied = rows.countEverything(destination, plan);
        if (!occupied.isEmpty()) {
            throw new TenantCutoverException("destination schema '" + silo + "' already holds rows "
                    + occupied + ", so it is not a fresh build and the initial copy would collide on"
                    + " every primary key it hits. Nothing has been frozen or flipped; drop that schema"
                    + " on the destination (one `drop schema … cascade` per transaction) and retry.");
        }
        makeSyncable(destination, silo);
        return version;
    }

    /**
     * <strong>The measured trap this class exists to disarm:</strong> {@code FOR TABLES IN SCHEMA}
     * publishes {@code flyway_schema_history} too, and the destination the migrator just built holds
     * its own history rows — identical {@code installed_rank}s to the source's, because both sides ran
     * the same sequence. Tablesync's COPY then collides ({@code duplicate key value violates unique
     * constraint "flyway_schema_history_pkey"}), and the relation wedges in state {@code d}
     * <em>retrying every ~5 s forever</em>, its sync slot pinning WAL on the publisher the whole time.
     * Observed end to end on 18.4; all 27 tier tables reached {@code r} while the history table alone
     * wedged the subscription.
     *
     * <p>So the destination's history is truncated before the subscription exists, and the SOURCE's
     * history arrives with the data — which is also the more truthful record: the rows now in this
     * schema were produced under the source's migration history, timestamps and all. The runner-side
     * refusal ({@code platform.tenant_cutover}) guarantees nobody appends to either history while the
     * window is open, so the two histories cannot diverge between this truncate and the sync.
     */
    private void makeSyncable(JdbcTemplate destination, String silo) {
        destination.execute("truncate table " + quote(silo) + ".flyway_schema_history");
    }

    /**
     * The re-run of a build that died AFTER {@link #makeSyncable} and before its subscription:
     * tables present, history empty — a state {@code migrate} cannot enter (Flyway reads the empty
     * history as "apply everything" and V1's first CREATE TABLE collides). Provably that leftover
     * — every table empty too, or the occupied refusal in {@link #build} is about to fire anyway —
     * so it is dropped whole and rebuilt fresh, which is what keeps "retry freely" true for every
     * failure point in the build, not just the ones before the truncate.
     */
    private void resetALeftoverBuild(JdbcTemplate destination, String silo) {
        Boolean truncatedSkeleton = destination.queryForObject("""
                select to_regclass(?) is not null
                """, Boolean.class, silo + ".flyway_schema_history");
        if (!Boolean.TRUE.equals(truncatedSkeleton)) {
            return;
        }
        Long applied = destination.queryForObject(
                "select count(*) from " + quote(silo) + ".flyway_schema_history", Long.class);
        if (applied != null && applied > 0) {
            return; // a real, migrated schema — the normal idempotent path
        }
        Map<String, Long> occupied = rows.countEverything(destination, tables.planFor(destination, silo));
        if (!occupied.isEmpty()) {
            throw new TenantCutoverException("destination schema '" + silo + "' has an EMPTY Flyway"
                    + " history but holds rows " + occupied + " — that is not a leftover build, and"
                    + " dropping it would destroy data of unknown provenance. Read it, then drop it by"
                    + " hand if it is truly abandoned.");
        }
        log.info("destination schema {} is a leftover from a build that died before its subscription"
                + " (tables present, history truncated, zero rows) — rebuilding it fresh", silo);
        destination.execute("drop schema " + quote(silo) + " cascade");
    }

    /**
     * {@code ext}, {@code no_tenant} and the minimal {@code platform} — remote destinations only.
     *
     * <p>{@code event_publication}'s DDL is derived from the PRIMARY's live catalogue rather than
     * duplicated here: a hand copy of V2's statements is a second list that drifts the day a later
     * migration alters the outbox, and drift here is invisible until the first remote tenant's event
     * fails to serialize. Reading {@code pg_attribute}/{@code pg_constraint}/{@code pg_indexes} off
     * the running platform makes the remote copy definitionally the same shape.
     */
    private void bootstrapRemote(JdbcTemplate destination) {
        destination.execute("create schema if not exists " + TenantSchemas.EXTENSIONS);
        // pg_trgm is trusted since PG 13, so the database owner may install it without superuser.
        destination.execute("create extension if not exists pg_trgm with schema "
                + TenantSchemas.EXTENSIONS);
        destination.execute("create schema if not exists " + TenantSchemas.NO_TENANT);
        destination.execute("create schema if not exists " + TenantSchemas.PLATFORM);

        List<String> platformTables = destination.query("""
                select table_name from information_schema.tables
                 where table_schema = ? and table_type = 'BASE TABLE'
                """, (rs, row) -> rs.getString(1), TenantSchemas.PLATFORM);
        List<String> foreign = platformTables.stream()
                .filter(table -> !OUTBOX.equals(table))
                .toList();
        if (!foreign.isEmpty()) {
            throw new TenantCutoverException("the destination's platform schema already holds "
                    + foreign + ". A cutover destination's platform schema must contain exactly"
                    + " event_publication (ADR 0011 §5.1) — anything more means this is somebody's"
                    + " platform database, and it also disarms the tripwire that makes a misdirected"
                    + " platform write fail loudly instead of landing where nobody reads. Nothing has"
                    + " been frozen or flipped; point the cutover at a dedicated database.");
        }
        if (platformTables.isEmpty()) {
            for (String statement : outboxDdlFromPrimary()) {
                destination.execute(statement);
            }
            log.info("destination platform schema created with exactly {} — §5.1's minimal shape",
                    OUTBOX);
        }
    }

    /** The outbox's CREATE TABLE + PK + secondary indexes, read off the primary's own catalogue. */
    private List<String> outboxDdlFromPrimary() {
        String columns = String.join(",\n  ", primaryCatalog.query("""
                select a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
                       || case when a.attnotnull then ' not null' else '' end
                       || coalesce(' default ' || pg_get_expr(d.adbin, d.adrelid), '')
                  from pg_attribute a
                  left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                 where a.attrelid = 'platform.event_publication'::regclass
                   and a.attnum > 0 and not a.attisdropped
                 order by a.attnum
                """, (rs, row) -> rs.getString(1)));
        List<String> primaryKey = primaryCatalog.query("""
                select 'alter table platform.event_publication add constraint ' || conname || ' '
                       || pg_get_constraintdef(oid)
                  from pg_constraint
                 where conrelid = 'platform.event_publication'::regclass and contype = 'p'
                """, (rs, row) -> rs.getString(1));
        // pg_indexes.indexdef arrives schema-qualified and executable as-is; indexes that merely back
        // a constraint are excluded because the constraint statement above recreates them itself.
        List<String> indexes = primaryCatalog.query("""
                select i.indexdef from pg_indexes i
                 where i.schemaname = 'platform' and i.tablename = 'event_publication'
                   and not exists (select 1 from pg_constraint c
                                     join pg_class ic on ic.oid = c.conindid
                                    where ic.relname = i.indexname)
                """, (rs, row) -> rs.getString(1));

        java.util.ArrayList<String> ddl = new java.util.ArrayList<>();
        ddl.add("create table platform.event_publication (\n  " + columns + "\n)");
        ddl.addAll(primaryKey);
        ddl.addAll(indexes);
        return List.copyOf(ddl);
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
