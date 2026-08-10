package ug.co.smsone.shared.tenancy.promotion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * <strong>What a tenant IS, physically: every table in a tenant schema, in an order a copy can be
 * applied in, each with the predicate that selects one organization's rows out of it.</strong> The plan
 * {@link TenantRows} executes and {@link TenantPromoter} verifies against (ADR 0010 §6 hop 0→1).
 *
 * <h2>Read from the catalogue, never from a list</h2>
 *
 * <p>The obvious implementation is a constant: twenty-seven table names and the {@code where} clause
 * for each. It is also the implementation that silently under-copies the first time somebody adds a
 * tenant table in V60 and does not think about promotion — which is exactly the failure
 * {@code OrgExportService}'s 21 hand-maintained {@code Extract} rows have today, and which ADR 0010 §5
 * names as one of the two things this design makes BETTER: "<em>a tenant table added in V60 lands in
 * {@code pg_dump -n} automatically; today, forgetting to add it silently under-exports and nothing
 * catches it</em>". A promoter with a hand-kept list would re-introduce that hazard at the exact moment
 * the ADR claims to have removed it. AGENTS §1 states the same rule for runtime table names: resolve
 * them off the mapping or the catalogue, "<em>never from a second list of which tables are the
 * platform's</em>".
 *
 * <p>So the table set comes from {@code information_schema}, and the predicates come from
 * {@code pg_constraint}. What is left over is the judgement no catalogue holds — and that judgement is
 * made loudly:
 *
 * <ul>
 *   <li>A table with an {@code org_id} column is selected directly: {@code org_id = ?}. Nineteen of the
 *       twenty-seven.</li>
 *   <li>A table without one reaches its organization <strong>through its parent's foreign key</strong>:
 *       {@code ticket_message} through {@code ticket}, {@code role_permission} through
 *       {@code org_role}, {@code org_group_member} through {@code org_group},
 *       {@code exchange_job_error} through {@code exchange_job}, {@code integration_setting} through
 *       {@code integration}. Five, and every one of them is a real intra-tenant foreign key
 *       (AGENTS §1 permits exactly those), so the catalogue can be asked rather than told.</li>
 *   <li>A table with neither {@link #resolve} <strong>refuses to plan at all</strong>. That is the whole
 *       value of deriving this: an orphan tenant table is a table whose rows a promotion would silently
 *       leave behind, and it fails here, at plan time, naming itself — not at 03:00 in a support ticket
 *       about a promoted tenant with no messages on its tickets.</li>
 * </ul>
 *
 * <h2>The order is the foreign keys, topologically</h2>
 *
 * <p>Parents before children, so an {@code INSERT} never lands a row whose parent is not there yet, and
 * so a child's predicate — a subquery against its parent <em>in the same schema</em> — has something to
 * read. The reverse of that order is the delete order, for the same constraints seen from the other
 * side. Both come from one traversal rather than from two hand-ordered constants, which is what stops
 * them drifting apart the way {@code SoftDeletePurgeJob.PURGE_ORDER} needs a test to stop it drifting
 * from the entity set.
 *
 * <p>{@code flyway_schema_history} is excluded and is the only exclusion: it is the destination
 * schema's own record of the migrations that BUILT it, written by the Flyway pass the promoter runs
 * before this one, and copying the source's would overwrite a true history with another schema's.
 */
@Component
public class TenantTierTables {

    /**
     * The discriminator itself. ADR 0010 §1 makes keeping this column non-negotiable — "in
     * {@code tenant_pool} the predicate is the only thing separating tenants; in a silo it is redundant
     * but free and it is the detector that catches a {@code search_path} mistake" — and this class is
     * one of the places that redundancy pays for itself: it is what lets a copy be planned from the
     * catalogue at all.
     */
    private static final String ORG_COLUMN = "org_id";

    /** Flyway's, not ours. See the class note. */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    /**
     * The alias every generated statement gives the table it is working on, and every predicate here is
     * written against it.
     *
     * <p>A fixed short alias rather than the table's own name, for one specific reason: the fingerprint
     * in {@link TenantRows} takes a WHOLE-ROW reference ({@code r::text}), and Postgres resolves such a
     * name as a column first and as the row only if no column matches. Aliasing {@code document} as
     * {@code document} would therefore silently fingerprint a single column the day somebody adds
     * {@code document.document}. {@code r} cannot collide with anything in this schema, and a
     * single-character column would be a naming problem long before it were this one.
     */
    static final String ROW_ALIAS = "r";

    private final JdbcTemplate jdbc;

    TenantTierTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The copy plan for {@code schemaName}: every table in it, parents first, each with the predicate
     * that selects one organization's rows.
     *
     * <p>Derived per call rather than cached. It is read once per promotion — a few catalogue queries
     * against an operation that moves rows — and a cached plan is one that describes the schema as it
     * was before the last migration ran.
     */
    public Plan planFor(String schemaName) {
        return planFor(jdbc, schemaName);
    }

    /**
     * The same plan read through a caller-supplied {@code JdbcTemplate} — ADR 0011's cutover verifies
     * a tenant across TWO databases, and the destination's catalogue is not behind this bean's pool.
     * Same derivation, same refusals; only where the catalogue is read from moves.
     */
    public Plan planFor(JdbcTemplate side, String schemaName) {
        String schema = TenantSchemaNames.require(schemaName);
        Map<String, List<String>> columns = columns(side, schema);
        if (columns.isEmpty()) {
            throw new IllegalStateException("schema '" + schema + "' holds no tables at all — nothing has"
                    + " migrated it, so there is no tenant here to copy (ADR 0010 §4.2)");
        }
        List<ForeignKey> foreignKeys = foreignKeys(side, schema, columns.keySet());
        Map<String, String> parentOf = new LinkedHashMap<>();
        Map<String, ForeignKey> linkTo = new LinkedHashMap<>();
        for (ForeignKey key : foreignKeys) {
            parentOf.putIfAbsent(key.childTable(), key.parentTable());
            linkTo.putIfAbsent(key.childTable(), key);
        }

        List<TenantTable> ordered = new ArrayList<>();
        for (String table : topological(columns.keySet(), foreignKeys)) {
            ordered.add(new TenantTable(table, columns.get(table),
                    predicateFor(schema, table, columns, linkTo, ROW_ALIAS, 0)));
        }
        return new Plan(schema, List.copyOf(ordered));
    }

    /**
     * How {@code table} names one organization's rows, resolved recursively through parent foreign keys
     * for the five tables that carry no {@code org_id} of their own.
     *
     * <p>The subquery names its parent WITH THE SCHEMA. It has to: the same statement runs against the
     * source and the destination, neither of which is necessarily on the connection's
     * {@code search_path} — the promoter works on the platform axis, where an unqualified
     * {@code ticket} resolves to nothing at all. Every identifier interpolated here came out of
     * {@code information_schema} for a schema that has already been through
     * {@link TenantSchemaNames#require}, which is the only reason this string can be built at all: a
     * schema name cannot be a bound parameter.
     */
    private static String predicateFor(String schema, String table, Map<String, List<String>> columns,
            Map<String, ForeignKey> linkTo, String alias, int depth) {
        if (columns.get(table).contains(ORG_COLUMN)) {
            return alias + "." + quote(ORG_COLUMN) + " = ?";
        }
        ForeignKey link = linkTo.get(table);
        if (link == null) {
            throw new IllegalStateException("tenant table '" + schema + "." + table + "' has no `org_id`"
                    + " and no foreign key to a table that has one, so a promotion cannot tell which of"
                    + " its rows belong to the organization being moved — and would leave every one of"
                    + " them behind. Give it an `org_id`, or an intra-tenant foreign key to the row that"
                    + " owns it (ADR 0010 §2; AGENTS §1's tier clause).");
        }
        // A fresh alias per level: two nested subqueries over the same parent table would otherwise
        // shadow each other, and the inner predicate would silently correlate to the wrong level.
        String parentAlias = "p" + depth;
        String parentPredicate =
                predicateFor(schema, link.parentTable(), columns, linkTo, parentAlias, depth + 1);
        return alias + "." + quote(link.childColumn()) + " in (select "
                + parentAlias + "." + quote(link.parentColumn())
                + " from " + quote(schema) + "." + quote(link.parentTable()) + " " + parentAlias
                + " where " + parentPredicate + ")";
    }

    /** Every base table in the schema with its columns, in the order the table declares them. */
    private Map<String, List<String>> columns(JdbcTemplate side, String schema) {
        Map<String, List<String>> columns = new TreeMap<>();
        for (Map<String, Object> row : side.queryForList("""
                select c.table_name, c.column_name
                  from information_schema.columns c
                  join information_schema.tables t
                    on t.table_schema = c.table_schema and t.table_name = c.table_name
                 where c.table_schema = ?
                   and t.table_type = 'BASE TABLE'
                   and c.table_name <> ?
                 order by c.table_name, c.ordinal_position
                """, schema, FLYWAY_HISTORY)) {
            columns.computeIfAbsent((String) row.get("table_name"), name -> new ArrayList<>())
                    .add((String) row.get("column_name"));
        }
        return columns;
    }

    /**
     * The intra-schema foreign keys, one row per key. Composite keys are refused rather than guessed
     * at: none exists in this schema, and a promoter that silently took the first column of a two-column
     * key would build a predicate that is quietly wrong for a subset of rows.
     */
    private List<ForeignKey> foreignKeys(JdbcTemplate side, String schema, Set<String> tables) {
        List<ForeignKey> keys = side.query("""
                select con.conname                as constraint_name,
                       child.relname              as child_table,
                       childatt.attname           as child_column,
                       parent.relname             as parent_table,
                       parentatt.attname          as parent_column,
                       array_length(con.conkey, 1) as columns
                  from pg_constraint con
                  join pg_class child on child.oid = con.conrelid
                  join pg_namespace childns on childns.oid = child.relnamespace
                  join pg_class parent on parent.oid = con.confrelid
                  join pg_namespace parentns on parentns.oid = parent.relnamespace
                  join pg_attribute childatt
                    on childatt.attrelid = child.oid and childatt.attnum = con.conkey[1]
                  join pg_attribute parentatt
                    on parentatt.attrelid = parent.oid and parentatt.attnum = con.confkey[1]
                 where con.contype = 'f'
                   and childns.nspname = ?
                   and parentns.nspname = ?
                 order by con.conname
                """,
                (rs, row) -> new ForeignKey(rs.getString("constraint_name"), rs.getString("child_table"),
                        rs.getString("child_column"), rs.getString("parent_table"),
                        rs.getString("parent_column"), rs.getInt("columns")),
                schema, schema);
        for (ForeignKey key : keys) {
            if (key.columns() != 1) {
                throw new IllegalStateException("foreign key " + key.constraintName() + " on " + schema
                        + "." + key.childTable() + " spans " + key.columns() + " columns; a promotion"
                        + " predicate built from its first column alone would be wrong for some rows."
                        + " Teach this class the composite form before shipping the key.");
            }
            if (!tables.contains(key.childTable()) || !tables.contains(key.parentTable())) {
                throw new IllegalStateException("foreign key " + key.constraintName() + " joins "
                        + key.childTable() + " to " + key.parentTable() + ", and one of them is not a base"
                        + " table this plan covers");
            }
        }
        return keys;
    }

    /**
     * Parents first. Kahn's algorithm over the foreign keys, with the alphabetical order of the
     * catalogue as the tie-break so a plan is reproducible run to run — a promotion report that lists
     * its tables in a different order every time is one nobody can diff against the last one.
     *
     * <p>A cycle throws. There is none today and a self-referencing tenant table would need a copy
     * strategy this class does not have (deferred constraints, or a two-pass insert), so guessing is
     * worse than refusing.
     */
    private static List<String> topological(Set<String> tables, List<ForeignKey> foreignKeys) {
        Map<String, Set<String>> children = new TreeMap<>();
        Map<String, Integer> parents = new TreeMap<>();
        tables.forEach(table -> {
            children.put(table, new LinkedHashSet<>());
            parents.put(table, 0);
        });
        for (ForeignKey key : foreignKeys) {
            if (key.childTable().equals(key.parentTable())) {
                throw new IllegalStateException("table " + key.childTable() + " references itself through "
                        + key.constraintName() + "; a single-pass copy cannot satisfy a self-reference");
            }
            if (children.get(key.parentTable()).add(key.childTable())) {
                parents.merge(key.childTable(), 1, Integer::sum);
            }
        }
        Deque<String> ready = new ArrayDeque<>(parents.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .toList());
        List<String> ordered = new ArrayList<>(tables.size());
        while (!ready.isEmpty()) {
            String table = ready.removeFirst();
            ordered.add(table);
            for (String child : children.get(table)) {
                if (parents.merge(child, -1, Integer::sum) == 0) {
                    ready.addLast(child);
                }
            }
        }
        if (ordered.size() != tables.size()) {
            List<String> cyclic = new ArrayList<>(tables);
            cyclic.removeAll(ordered);
            throw new IllegalStateException("foreign keys among " + cyclic + " form a cycle, so there is no"
                    + " order in which a copy can insert them one table at a time");
        }
        return ordered;
    }

    /** Postgres identifier quoting. Everything reaching it came from the catalogue, not from a caller. */
    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * One tenant schema's copy plan.
     *
     * @param schema the schema this plan was read from — every predicate in it names that schema, so a
     *     plan is not interchangeable between a source and a destination
     * @param tables parents first; reverse for deletes
     */
    public record Plan(String schema, List<TenantTable> tables) {

        /** The same tables children-first, which is the only order a delete can run in. */
        public List<TenantTable> deleteOrder() {
            List<TenantTable> reversed = new ArrayList<>(tables);
            java.util.Collections.reverse(reversed);
            return List.copyOf(reversed);
        }

        /** Just the names, in plan order — for comparing two schemas' shapes before a copy. */
        public List<String> tableNames() {
            return tables.stream().map(TenantTable::name).toList();
        }
    }

    /**
     * One table in the plan.
     *
     * @param columns in declaration order, quoted into every statement so neither side depends on
     *     {@code select *} agreeing about ordinals
     * @param predicate selects one organization's rows, written against {@link #ROW_ALIAS} — so every
     *     statement built from it must alias the table it reads exactly that way; {@link #parameters()}
     *     says how many times the organization id has to be bound into it
     */
    public record TenantTable(String name, List<String> columns, String predicate) {

        /**
         * How many {@code ?} the predicate carries — one per nesting level, since each level repeats the
         * {@code org_id = ?} test at the bottom of its subquery chain. Counted rather than tracked
         * because the predicate is the single source of truth for its own shape.
         */
        public int parameters() {
            return (int) predicate.chars().filter(character -> character == '?').count();
        }

        /** The column list, quoted, for both halves of an {@code INSERT … SELECT}. */
        public String columnList() {
            return columns.stream().map(TenantTierTables::quote).reduce((a, b) -> a + ", " + b).orElseThrow();
        }
    }

    private record ForeignKey(String constraintName, String childTable, String childColumn,
            String parentTable, String parentColumn, int columns) {
    }
}
