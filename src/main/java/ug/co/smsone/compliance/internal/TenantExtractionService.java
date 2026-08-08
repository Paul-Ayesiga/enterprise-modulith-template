package ug.co.smsone.compliance.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import ug.co.smsone.shared.audit.AuditLog;
import ug.co.smsone.shared.tenancy.Tenant;
import ug.co.smsone.shared.tenancy.TenantContext;

/**
 * The <b>tenant extraction</b>: everything one organization owns, complete, unredacted, in an order a
 * restore can replay. The other half of the old {@code OrgExportService}, and the opposite artifact to
 * {@link GdprBundleService} — see that class for why the two can never be one call. In one line: the
 * disclosure bundle must NOT contain {@code webhook_subscription.secret} or
 * {@code api_key.secret_hash}, and an extraction that omits them produces a tenant that does not boot.
 *
 * <p><b>The improvement the schema split buys, taken here.</b> The old service hand-maintained 21
 * {@code Extract} rows and that list was the source of truth for "what the tenant owns". A table added
 * in V60 and forgotten here under-exported in silence — nothing failed, nothing warned, and the loss
 * only surfaced on the far side of a migration nobody could redo. Once the tenant is a schema, the
 * inventory is a FACT ABOUT THE DATABASE, so this class asks the database instead of a list: every
 * table in the tenant's own schema is in the extraction, and there is no place to forget one. That is
 * worth stating plainly, because it is measurable — today's hand list misses ten tenant-tier tables
 * ({@code geo_stamp}, {@code geo_capture_policy}, {@code user_device_trust},
 * {@code feature_flag_org_override}, {@code org_group_member}, {@code role_permission},
 * {@code ticket_message}, {@code exchange_job_error}, {@code integration_setting},
 * {@code maintenance_window}). None of them was a decision; they were simply never added.
 *
 * <p><b>Why it is not literally {@code pg_dump -n} yet, and what it becomes.</b> ADR 0010 §6 defines
 * the extraction as {@code pg_dump -n t_<hex>} — no predicate to get wrong, no table to forget. That
 * is exact only once the tenant has a schema of its own, which is Phase 5. While the tenant is POOLED
 * it shares {@code tenant_pool} with every other tenant, so {@code pg_dump -n tenant_pool} would dump
 * the whole fleet and the {@code org_id} predicate is still the only separator (ADR 0010 §1, which is
 * why that column stays non-negotiable). So this class is the pooled form of the same idea: the schema
 * supplies the table list, the predicate supplies the rows, and at promotion the predicate is the only
 * part that goes away.
 *
 * <p><b>Never silently short — it refuses instead.</b> A tenant table with no {@code org_id} is routed
 * through its foreign key to a parent that has one ({@code ticket_message} → {@code ticket},
 * {@code role_permission} → {@code org_role}, and three more), and that chain is read from
 * {@code pg_constraint}, not declared here. A table this class cannot route — no {@code org_id}, no
 * single-column FK to something routable — <b>fails the extraction and names itself</b>. That is the
 * property the hand list never had: the failure mode moved from "the copy is quietly missing a table"
 * to "the copy refused to be made until someone answered a question". Loud beats short, and it is the
 * same doctrine {@code SoftDeletePurgeJob} states as "loud AND complete".
 *
 * <p><b>Rows, not a bundle.</b> This streams to a {@link Sink} one row at a time and never builds the
 * whole tenant in heap: an extraction is uncapped by definition, and {@code ticket_message} alone is
 * 600k rows / 110 MB in the measured database. The caller decides what a row becomes — a line in a
 * dump file, an insert into a silo, an assertion in a test. {@link GdprBundleService} does build one
 * JSON document, which it is allowed to do precisely because it is capped.
 *
 * <p><b>Scope, deliberately.</b> The extraction is the tenant's SCHEMA. It is not the whole extraction
 * BUNDLE, which ADR 0010 §6 lists in eleven parts — the platform's {@code organization} and
 * {@code external_organization} rows, the person projection, the catalog snapshot, re-minted API keys,
 * a rebuilt search index, fresh-and-empty {@code shedlock}/{@code event_publication}, the object
 * storage under {@code doc/o/<orgId>/}. Those are platform-side and per-subsystem, and each is its own
 * phase's work. This class owns exactly part 1, and owning it completely is what makes the rest
 * checkable.
 *
 * <p><b>No HTTP route reaches this, on purpose.</b> The output carries live credentials and is
 * unbounded; both disqualify it from being a JSON response (ADR 0002), and neither is fixable by
 * adding a cap or a redaction — that would just be the disclosure bundle again. It is an operator
 * runbook step: Phase 5's promotion copy and Phase 6's rehearsal are its callers, and
 * {@code TenantExtractionTest} is what keeps it honest until they exist.
 */
@Service
class TenantExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TenantExtractionService.class);

    /**
     * Flyway's own bookkeeping, once Phase 4 gives each tenant schema a history table. It describes
     * the schema rather than belonging to the tenant, and a restored copy gets its own — copying one
     * would import another schema's applied-version history as if it were this tenant's.
     */
    private static final String SCHEMA_HISTORY = "flyway_schema_history";

    /**
     * How far the foreign-key walk chases a parent before giving up. Every routed table in the schema
     * today is one hop from an {@code org_id}, so this is a cycle guard rather than a limit: a
     * self-referencing or mutually-referencing pair would otherwise recurse until the stack ran out,
     * and an extraction that dies with a {@code StackOverflowError} names nothing.
     */
    private static final int MAX_FK_HOPS = 4;

    /** The alias the outer query gives the table being extracted; every predicate is written against it. */
    private static final String ROW_ALIAS = "t";

    /** One row of the tenant, handed over as it is read. See the class note on why this is not a Map. */
    @FunctionalInterface
    interface Sink {
        void row(String table, Map<String, Object> row);
    }

    /**
     * One table of the tenant and how its rows are found: the SQL {@code where} that selects them and
     * the {@code order by} that makes the extraction reproducible. Both are derived from the catalog,
     * so this record is also the reviewable answer to "how do you know that is all of it".
     */
    record RoutedTable(String table, String predicate, String order) {

        String sql() {
            return "select " + ROW_ALIAS + ".* from " + table + " " + ROW_ALIAS
                    + " where " + predicate + " order by " + order;
        }
    }

    /**
     * What an extraction produced: the schema it came out of, the tables in restore order (parents
     * before children), and the row count each one yielded. The counts are a by-product of streaming,
     * never a {@code COUNT(*)} — ADR 0002's rule holds here too, and a count taken separately from the
     * read is a count of a different instant.
     */
    record Manifest(UUID orgId, String schema, List<RoutedTable> tables, Map<String, Integer> rows) {
    }

    private final JdbcTemplate jdbc;
    private final AuditLog auditLog;

    TenantExtractionService(JdbcTemplate jdbc, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.auditLog = auditLog;
    }

    /**
     * Extracts one organization out of the tenant schema the calling thread is pinned to.
     *
     * <p>Not {@code @Transactional}, and that is a decision rather than an omission. A single
     * repeatable-read snapshot across the whole tenant would be the ideal — but an extraction runs for
     * as long as its biggest table takes, and holding one transaction open for that pins the oldest
     * xmin cluster-wide and blocks vacuum for every other tenant on the box. ADR 0010 §6 answers the
     * consistency question a different way and at the right layer: the org is frozen by a
     * {@code RESTRICT} maintenance window and its queues are paused before the copy starts, so nothing
     * is writing these rows. If that freeze is ever skipped, an extraction can straddle a write —
     * which is exactly why the runbook step exists and why this method does not pretend to replace it.
     *
     * @throws IllegalStateException if the thread is not pinned to a tenant (an extraction taken from
     *     the platform axis would enumerate {@code platform} and dump the wrong schema entirely), if
     *     the pinned tenant is not the one being extracted, or if any table in the schema cannot be
     *     routed to an organization
     */
    Manifest extract(UUID orgId, Sink sink) {
        requirePinnedTo(orgId);
        String schema = jdbc.queryForObject("select current_schema()", String.class);
        List<RoutedTable> plan = plan();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RoutedTable table : plan) {
            int[] rows = {0};
            // Typed as RowCallbackHandler rather than passed as a bare lambda: JdbcTemplate.query has
            // an overload for ResultSetExtractor with the same shape, and naming the type is what keeps
            // the streaming (row by row) form from silently resolving to the extracting one.
            RowCallbackHandler handler = resultSet -> {
                // Built per row from the metadata rather than from a mapped type: the whole point is
                // that this class does not know the columns — a column added in V60 travels because
                // nothing here enumerates columns either.
                var metaData = resultSet.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 1; column <= metaData.getColumnCount(); column++) {
                    row.put(metaData.getColumnLabel(column), resultSet.getObject(column));
                }
                rows[0]++;
                sink.row(table.table(), row);
            };
            jdbc.query(table.sql(), handler, orgId);
            counts.put(table.table(), rows[0]);
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Extracted org {} from schema {}: {} tables, {} rows", orgId, schema, plan.size(), total);
        // Distinct from compliance.org_exported on purpose. The two artifacts have different contents,
        // different recipients and different risk, so an audit trail that could not tell them apart
        // would be unable to answer the only question anyone will ever ask of it: did this org's
        // secrets leave the database, and when.
        auditLog.record("compliance.tenant_extracted", orgId, orgId.toString(), null,
                "schema=" + schema + " tables=" + plan.size() + " rows=" + total);
        return new Manifest(orgId, schema, plan, Map.copyOf(counts));
    }

    /**
     * The routing plan for the tenant schema on this thread: every table, the predicate that selects
     * one org's rows from it, and the order that makes the result reproducible. Package-private and
     * separate from {@link #extract} because the plan is the reviewable artifact — a test can assert
     * that the plan covers the schema without moving a single row.
     */
    List<RoutedTable> plan() {
        Map<String, TableMeta> tables = readTables();
        Map<String, ForeignKey> parents = readForeignKeys();
        List<RoutedTable> plan = new ArrayList<>();
        for (String table : restoreOrder(tables.keySet(), parents)) {
            plan.add(new RoutedTable(table,
                    predicateFor(table, ROW_ALIAS, tables, parents, 0),
                    // The primary key is the only order every table is guaranteed to have and the only
                    // one guaranteed unique, which is what makes two extractions of unchanged data
                    // agree row for row. GdprBundleService orders newest-first instead because it CUTS,
                    // and which rows survive a cut is a question this artifact never has to answer.
                    tables.get(table).primaryKey()));
        }
        return List.copyOf(plan);
    }

    /**
     * Refuses an extraction taken from the wrong axis. Both halves matter: taken from
     * {@code Tenant.PLATFORM} this would read {@code current_schema() = 'platform'} and cheerfully
     * "extract" the person graph and every other tenant's routing rows; taken from tenant B's axis
     * while naming org A it would select nothing at all and report a complete, empty extraction —
     * the more dangerous of the two, because it looks like success.
     */
    private static void requirePinnedTo(UUID orgId) {
        Tenant current = TenantContext.current();
        if (!(current instanceof Tenant.Org(UUID pinned)) || !pinned.equals(orgId)) {
            throw new IllegalStateException("An extraction reads the tenant's own schema, so it must run "
                    + "pinned to that tenant: wrap the call in TenantContext.runAs(" + orgId + ", …). "
                    + "The thread is currently on " + current + ".");
        }
    }

    /**
     * Where a table's organization comes from. Direct when the table carries {@code org_id}; otherwise
     * borrowed from the parent its foreign key points at, recursively. Exactly one {@code ?} appears in
     * the result however deep the chain goes — only the ancestor that actually holds {@code org_id}
     * binds anything — which is what lets {@link #extract} pass a single argument for every table.
     *
     * <p>Every column is written against an alias. Unqualified would work today by accident (no parent
     * happens to share a column name with its child) and would silently start selecting the wrong
     * rows the day one did.
     */
    private String predicateFor(String table, String alias, Map<String, TableMeta> tables,
            Map<String, ForeignKey> parents, int hop) {
        TableMeta meta = tables.get(table);
        if (meta != null && meta.hasOrgId()) {
            return alias + ".org_id = ?";
        }
        if (hop >= MAX_FK_HOPS) {
            throw unroutable(table, "its foreign keys form a chain deeper than " + MAX_FK_HOPS
                    + " hops, or a cycle");
        }
        ForeignKey parent = parents.get(table);
        if (parent == null) {
            throw unroutable(table, "it has no org_id column and no single-column foreign key to a "
                    + "table that has one");
        }
        String parentAlias = "p" + hop;
        return "exists (select 1 from " + parent.parentTable() + " " + parentAlias
                + " where " + parentAlias + "." + parent.parentColumn()
                + " = " + alias + "." + parent.childColumn()
                + " and " + predicateFor(parent.parentTable(), parentAlias, tables, parents, hop + 1) + ")";
    }

    private static IllegalStateException unroutable(String table, String why) {
        return new IllegalStateException("Cannot extract tenant table '" + table + "': " + why
                + ". An extraction that skipped it would be short by an unknown number of rows and say "
                + "nothing, so it refuses instead. Give the table an org_id, or a foreign key to a "
                + "table that has one (ADR 0010 §6).");
    }

    /**
     * Tables in an order a restore can replay: a table appears only after every table it references.
     * Depth-first over the FK graph, with the {@code ordered} set doubling as the cycle guard.
     * Alphabetical at the top level so two runs against the same schema produce the same manifest.
     */
    private static List<String> restoreOrder(Set<String> tables, Map<String, ForeignKey> parents) {
        Set<String> ordered = new LinkedHashSet<>();
        new TreeSet<>(tables).forEach(table -> visit(table, tables, parents, ordered, 0));
        return List.copyOf(ordered);
    }

    private static void visit(String table, Set<String> tables, Map<String, ForeignKey> parents,
            Set<String> ordered, int hop) {
        if (ordered.contains(table) || !tables.contains(table) || hop > MAX_FK_HOPS) {
            return;
        }
        ForeignKey parent = parents.get(table);
        if (parent != null) {
            visit(parent.parentTable(), tables, parents, ordered, hop + 1);
        }
        ordered.add(table);
    }

    private record TableMeta(String table, boolean hasOrgId, String primaryKey) {
    }

    private record ForeignKey(String childColumn, String parentTable, String parentColumn) {
    }

    /**
     * Every base table in the tenant's own schema, with whether it carries {@code org_id} and the
     * primary key to order it by. {@code current_schema()} rather than a name: it is
     * {@code tenant_pool} while the tenant is pooled and {@code t_<hex>} once it is siloed, and the
     * whole design turns on this class never knowing which.
     */
    private Map<String, TableMeta> readTables() {
        Map<String, TableMeta> tables = new LinkedHashMap<>();
        RowCallbackHandler handler = resultSet -> {
            String table = resultSet.getString("table_name");
            if (SCHEMA_HISTORY.equals(table)) {
                return;
            }
            String primaryKey = resultSet.getString("primary_key");
            if (primaryKey == null) {
                // Every table in this schema has one (ADR 0010 §6 checked it, and it is what makes
                // logical replication's default REPLICA IDENTITY sufficient at hop 2). A table without
                // one cannot be ordered reproducibly, so it stops the extraction rather than making it
                // quietly unrepeatable.
                throw unroutable(table, "it has no primary key, so its rows cannot be ordered "
                        + "reproducibly");
            }
            tables.put(table, new TableMeta(table, resultSet.getBoolean("has_org_id"), primaryKey));
        };
        jdbc.query("""
                select c.relname as table_name,
                       exists (select 1 from pg_attribute a
                                where a.attrelid = c.oid and a.attname = 'org_id'
                                  and a.attnum > 0 and not a.attisdropped) as has_org_id,
                       (select string_agg(a.attname, ', ' order by k.ord)
                          from pg_index i
                          cross join lateral unnest(i.indkey) with ordinality as k(attnum, ord)
                          join pg_attribute a on a.attrelid = c.oid and a.attnum = k.attnum
                         where i.indrelid = c.oid and i.indisprimary) as primary_key
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = current_schema() and c.relkind = 'r'
                 order by c.relname
                """, handler);
        return tables;
    }

    /**
     * Single-column foreign keys that stay inside the tenant schema, child → parent. Composite keys
     * are ignored rather than guessed at: none exists today, and a two-column reference is a routing
     * question a human should answer, which is what {@link #unroutable} makes them do.
     *
     * <p>Cross-schema FKs cannot appear here by construction — AGENTS §1's second clause forbids them
     * and {@code TenantSchemaSelfContainmentTest} fails the build on one — and the
     * {@code pn.nspname = current_schema()} arm means that if one ever did, it would be invisible to
     * the walk and the child table would refuse rather than be routed through a table outside the
     * tenant.
     */
    private Map<String, ForeignKey> readForeignKeys() {
        Map<String, ForeignKey> parents = new LinkedHashMap<>();
        RowCallbackHandler handler = resultSet ->
                // First FK wins, and the ordering below makes "first" deterministic. A table with
                // several parents only reaches the walk when it has no org_id of its own, and today
                // none does — if one ever appears, the plan is stable rather than plan-order-dependent.
                parents.putIfAbsent(resultSet.getString("child_table"),
                        new ForeignKey(resultSet.getString("child_column"),
                                resultSet.getString("parent_table"),
                                resultSet.getString("parent_column")));
        jdbc.query("""
                select child.relname as child_table, parent.relname as parent_table,
                       ca.attname as child_column, pa.attname as parent_column
                  from pg_constraint k
                  join pg_class child on child.oid = k.conrelid
                  join pg_namespace cn on cn.oid = child.relnamespace
                  join pg_class parent on parent.oid = k.confrelid
                  join pg_namespace pn on pn.oid = parent.relnamespace
                  join pg_attribute ca on ca.attrelid = k.conrelid and ca.attnum = k.conkey[1]
                  join pg_attribute pa on pa.attrelid = k.confrelid and pa.attnum = k.confkey[1]
                 where k.contype = 'f'
                   and cn.nspname = current_schema() and pn.nspname = current_schema()
                   and array_length(k.conkey, 1) = 1
                 order by child.relname, k.conname
                """, handler);
        return parents;
    }
}
