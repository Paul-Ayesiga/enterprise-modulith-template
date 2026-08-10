package ug.co.smsone.shared.tenancy.promotion;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.Plan;
import ug.co.smsone.shared.tenancy.promotion.TenantTierTables.TenantTable;

/**
 * Every statement that moves one organization's rows between two tenant schemas, and the fingerprint
 * that decides whether the move was faithful (ADR 0010 §6 hop 0→1).
 *
 * <p>One class for the same reason {@code TenantPlacements} is one class: "<em>which statements can
 * change where a tenant's data is, and under what condition</em>" should be a question with one file
 * for an answer. Three statements live here — copy, fingerprint, delete — and they are built from one
 * {@link Plan}, so a table cannot be copied under one predicate and deleted under another.
 *
 * <h2>{@code INSERT … SELECT}, not {@code CREATE TABLE AS SELECT}</h2>
 *
 * <p>ADR 0010 §6 writes the copy as {@code CREATE TABLE AS SELECT … WHERE org_id = ?} and quotes 1.72 s
 * for 150k rows across the eight largest tables. <strong>The measurement is the ADR's; the statement is
 * not the one to ship, and the reason is in the same paragraph.</strong> §6 also says the destination
 * schema has already had the tenant migration set run against it — so its tables exist, with their 184
 * indexes, their primary keys, their partial unique constraints over {@code deleted_at is null}, their
 * check constraints and their intra-tenant foreign keys. {@code CREATE TABLE AS} produces a table with
 * <em>none</em> of those and cannot be made to: it copies column names and types and stops. A promoter
 * built on it would hand the tenant a schema that looks right in a row count and has no uniqueness, no
 * cascade and no Flyway history — and the next tenant migration would fail against it, on the one
 * tenant that most needed it to work.
 *
 * <p>{@code INSERT … SELECT} is the same server-side path with the DDL removed: one scan of the source
 * with the org predicate applied, one heap insert, no round trip per row. What it adds over CTAS is
 * index maintenance on the destination, which is the cost of arriving with the indexes.
 *
 * <p><strong>Columns are named explicitly, never {@code SELECT *}.</strong> Both schemas were built by
 * the same migration sequence so their column order agrees — and {@link #assertSameShape} proves it
 * before anything is copied — but naming them means a disagreement is a clear failure at the first
 * table instead of a type error somewhere in the middle, and it makes the statement readable in a log.
 *
 * <h2>The fingerprint: counts AND a checksum, and why both</h2>
 *
 * <p>A row count alone answers "did the same NUMBER of rows arrive". It cannot see a column dropped by
 * a mismatched insert list, a {@code numeric} that lost scale, a timestamp that arrived in another zone,
 * or — the case that matters here — <em>a row deleted from the source and a different row inserted while
 * the copy was running</em>, which leaves the count untouched. ADR 0010 §7 Phase 5's gate asks for
 * counts; §6 asks for verification; this class does both because the count is the cheap half of a
 * check that is worthless if it can be fooled by a one-in-one-out.
 *
 * <p>The checksum is {@code sum(('x' || substr(md5(t::text), 1, 16))::bit(64)::bigint)}. Four properties
 * decided it:
 *
 * <ul>
 *   <li><strong>Order-independent.</strong> {@code sum} over rows, so the two schemas need not — and
 *       will not — return rows in the same physical order, and no {@code ORDER BY} is imposed on a
 *       600k-row table.</li>
 *   <li><strong>Single pass, constant memory.</strong> The obvious alternative,
 *       {@code md5(string_agg(md5(t::text), '' order by …))}, is stronger per bit and materialises a
 *       19 MB sorted string for {@code ticket_message} alone.</li>
 *   <li><strong>64 bits per row, summed into {@code numeric}</strong>, so there is no wraparound to
 *       argue about and the comparison is exact.</li>
 *   <li><strong>Whole-row.</strong> {@code t::text} renders every column of the row through the
 *       composite type, so a column that arrived null, truncated or reordered changes it. That is also
 *       why the two sides are read <strong>inside one transaction</strong>: {@code t::text} renders a
 *       {@code timestamptz} through the session's {@code TimeZone}, and two sessions could disagree.</li>
 * </ul>
 *
 * <p>What it is not is a cryptographic guarantee, and it does not need to be: this compares a copy
 * against its own source minutes later, not against an adversary. A 64-bit sum missing a real difference
 * needs a collision engineered into row text nobody controls.
 */
@Component
public class TenantRows {

    private static final Logger log = LoggerFactory.getLogger(TenantRows.class);

    private final JdbcTemplate jdbc;

    TenantRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Refuses to copy between two schemas whose tables or columns disagree.
     *
     * <p>Both are built by {@code db/migration/tenant/}, so they agree or something is wrong in a way
     * that no row count would explain: a destination that skipped a migration, a source that has been
     * hand-edited, a silo left behind by a promotion that failed before Phase 4's runner existed. Every
     * one of those produces a copy that "succeeds" and a tenant that is missing a table.
     *
     * <p>It also underwrites the checksum. {@code t::text} renders columns in declaration order, so two
     * schemas whose column ORDER differed would fingerprint identical data differently, and the failure
     * would read as data loss rather than as the schema drift it is.
     */
    public void assertSameShape(Plan source, Plan destination) {
        if (!source.tableNames().equals(destination.tableNames())) {
            throw new TenantPromotionException("schemas '" + source.schema() + "' and '"
                    + destination.schema() + "' hold different tables, so one of them is not at the head of"
                    + " the tenant migration sequence: " + difference(source.tableNames(),
                    destination.tableNames()));
        }
        Map<String, List<String>> destinationColumns = new LinkedHashMap<>();
        destination.tables().forEach(table -> destinationColumns.put(table.name(), table.columns()));
        for (TenantTable table : source.tables()) {
            List<String> theirs = destinationColumns.get(table.name());
            if (!table.columns().equals(theirs)) {
                throw new TenantPromotionException("table '" + table.name() + "' has columns "
                        + table.columns() + " in " + source.schema() + " and " + theirs + " in "
                        + destination.schema() + ". Order counts as much as membership here: the copy's"
                        + " checksum renders whole rows, so a reordered column would read as corrupted"
                        + " data rather than as the schema drift it is.");
            }
        }
    }

    /**
     * Copies {@code orgId}'s rows from one tenant schema into another, parents first, and returns what
     * each table contributed.
     *
     * <p><strong>Call it inside one transaction.</strong> The whole copy either lands or does not: a
     * half-copied tenant in a destination nothing routes to yet is recoverable only by knowing exactly
     * which tables got through, and the transaction knows that for free. It also gives the copy ONE
     * snapshot of the source, which is what makes the verification that follows meaningful — see
     * {@link #fingerprint}.
     */
    public Map<String, Long> copy(Plan source, Plan destination, UUID orgId) {
        Map<String, Long> copied = new LinkedHashMap<>();
        for (int index = 0; index < source.tables().size(); index++) {
            TenantTable from = source.tables().get(index);
            TenantTable into = destination.tables().get(index);
            String sql = "insert into " + TenantTierTables.quote(destination.schema()) + "."
                    + TenantTierTables.quote(into.name()) + " (" + into.columnList() + ")"
                    + " select " + from.columnList()
                    + " from " + qualified(source.schema(), from.name())
                    + " where " + from.predicate();
            long rows = jdbc.update(sql, bindings(from, orgId));
            copied.put(from.name(), rows);
        }
        log.info("copied {} rows for tenant {} from {} into {}",
                copied.values().stream().mapToLong(Long::longValue).sum(), orgId, source.schema(),
                destination.schema());
        return copied;
    }

    /**
     * Counts and checksums {@code orgId}'s rows in one schema, per table.
     *
     * <p><strong>Run the two sides in ONE fresh transaction, and never in the transaction that did the
     * copy.</strong> Both halves of that sentence are load-bearing and they pull in opposite directions:
     *
     * <ul>
     *   <li>ONE transaction, because the checksum renders timestamps through the session's time zone and
     *       because the two sides must be compared against the same instant. Two transactions could
     *       straddle a write and report a difference that had already been fixed.</li>
     *   <li>NOT the copy's transaction, because inside it the source is pinned to the snapshot the copy
     *       read and the destination holds exactly what the copy wrote — so the comparison is true by
     *       construction and proves nothing. A background write that landed in the source <em>during</em>
     *       the copy is precisely what this has to be able to see, and it can only see it from a snapshot
     *       taken afterwards.</li>
     * </ul>
     *
     * <p>That is the whole verification argument, and it is worth being blunt about what it means: the
     * freeze narrows the window, and this is what makes the copy correct. A promotion whose fingerprints
     * disagree has caught a writer the freeze did not, and the right response is to abort — which is what
     * {@link TenantPromoter} does, before the placement flip, while the tenant is still serving from the
     * schema it has always served from.
     */
    public Map<String, Fingerprint> fingerprint(Plan plan, UUID orgId) {
        return fingerprint(jdbc, plan, orgId);
    }

    /**
     * The same fingerprint through a caller-supplied {@code JdbcTemplate} — a cutover (ADR 0011 §7.2
     * step 7) compares the two sides of a move that spans TWO DATABASES, so "one transaction" is not
     * available and is replaced by two facts that hold exactly there: the freeze has stopped every
     * writer on both sides, and both pools live in one JVM, so pgjdbc pins both sessions'
     * {@code TimeZone} to the same JVM default and {@code t::text} renders identically. Neither fact
     * holds for the same-database promotion, which keeps its one-transaction rule.
     */
    public Map<String, Fingerprint> fingerprint(JdbcTemplate side, Plan plan, UUID orgId) {
        Map<String, Fingerprint> fingerprints = new LinkedHashMap<>();
        for (TenantTable table : plan.tables()) {
            String sql = "select count(*) as rows,"
                    + " coalesce(sum(('x' || substr(md5(" + TenantTierTables.ROW_ALIAS
                    + "::text), 1, 16))::bit(64)::bigint), 0) as checksum"
                    + " from " + qualified(plan.schema(), table.name())
                    + " where " + table.predicate();
            Fingerprint fingerprint = side.queryForObject(sql,
                    (rs, row) -> new Fingerprint(rs.getLong("rows"), rs.getBigDecimal("checksum")),
                    bindings(table, orgId));
            fingerprints.put(table.name(), fingerprint);
        }
        return fingerprints;
    }

    /**
     * Deletes {@code orgId}'s rows from a schema, children first, and returns what each table gave up.
     *
     * <p><strong>This is the step that makes a promotion a MOVE.</strong> Leaving the source rows behind
     * looks like a free rollback and is not: {@code tenant_pool}'s fan-out jobs would keep finding them,
     * so the promoted tenant would have its tickets escalated twice, its webhooks retried from two
     * schemas and its retention purge run against a copy nobody reads — and a later demotion would
     * collide on the primary key of every row it tried to bring back. The reversal ADR 0010 §6 promises
     * ("copying back and flipping one row") is a copy in the other direction, not an undelete.
     *
     * <p><strong>It runs LAST, after the flip.</strong> If anything between the copy and here fails, the
     * tenant is either still serving from the source (nothing was flipped) or already serving correct
     * rows from the destination (everything was) — and in both cases the worst outcome is a duplicate
     * set of rows an operator can remove with the same statement this method issues. Delete first and the
     * failure mode is a tenant with no data at all.
     *
     * <p>Children first, which is the reverse of the copy: {@code role_permission} cascades from
     * {@code org_role} and would go anyway, but {@code membership.role_id → org_role} does not cascade,
     * so the order is a correctness requirement and not an optimisation. It is also why the delete
     * predicates are the copy's predicates — a child's subquery needs its parent still present, and
     * reversing the order is what guarantees that.
     */
    public Map<String, Long> delete(Plan plan, UUID orgId) {
        Map<String, Long> deleted = new LinkedHashMap<>();
        for (TenantTable table : plan.deleteOrder()) {
            String sql = "delete from " + qualified(plan.schema(), table.name())
                    + " where " + table.predicate();
            deleted.put(table.name(), (long) jdbc.update(sql, bindings(table, orgId)));
        }
        log.info("removed {} rows for tenant {} from {}",
                deleted.values().stream().mapToLong(Long::longValue).sum(), orgId, plan.schema());
        return deleted;
    }

    /**
     * Every row in the schema, whatever tenant it belongs to — the emptiness check {@code reclaim} runs
     * before it drops a schema, and the one a promotion runs against its destination before it copies.
     *
     * <p>Deliberately not scoped to an organization. "This silo holds no rows for the tenant I am about
     * to drop it for" is the wrong question: a silo is one tenant's, so a row in it belonging to anyone
     * else is a misroute — ADR 0010 §1's worst failure — and dropping the schema would destroy the
     * evidence along with the row.
     */
    public Map<String, Long> countEverything(Plan plan) {
        return countEverything(jdbc, plan);
    }

    /** The same emptiness check on another database — the cutover's destination (ADR 0011 §7.2 step 1). */
    public Map<String, Long> countEverything(JdbcTemplate side, Plan plan) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TenantTable table : plan.tables()) {
            Long rows = side.queryForObject("select count(*) from " + TenantTierTables.quote(plan.schema())
                    + "." + TenantTierTables.quote(table.name()), Long.class);
            if (rows != null && rows > 0) {
                counts.put(table.name(), rows);
            }
        }
        return counts;
    }

    /**
     * {@code "schema"."table" r} — the aliased relation every generated statement reads or deletes
     * from. The alias is {@link TenantTierTables#ROW_ALIAS} because that is what the plan's predicates
     * are written against, and because the fingerprint takes a whole-row reference through it.
     */
    private static String qualified(String schema, String table) {
        return TenantTierTables.quote(schema) + "." + TenantTierTables.quote(table) + " "
                + TenantTierTables.ROW_ALIAS;
    }

    /** The organization id, repeated once per {@code ?} the table's predicate carries. */
    private static Object[] bindings(TenantTable table, UUID orgId) {
        Object[] parameters = new Object[table.parameters()];
        java.util.Arrays.fill(parameters, orgId);
        return parameters;
    }

    private static String difference(List<String> source, List<String> destination) {
        List<String> onlyInSource = new ArrayList<>(source);
        onlyInSource.removeAll(destination);
        List<String> onlyInDestination = new ArrayList<>(destination);
        onlyInDestination.removeAll(source);
        return "only in the source " + onlyInSource + ", only in the destination " + onlyInDestination;
    }

    /**
     * One table's contribution to a tenant, from one side of the copy.
     *
     * @param checksum {@code numeric}, because a sum of 64-bit values is not a {@code long} and the
     *     comparison has to be exact — {@link BigDecimal#compareTo} rather than {@code equals}, since
     *     {@code 0} and {@code 0.00} are the same number and not the same object
     */
    public record Fingerprint(long rows, BigDecimal checksum) {

        /** Public since ADR 0011: the cutover's cross-database verifier compares from another package. */
        public boolean matches(Fingerprint other) {
            return other != null && rows == other.rows && checksum.compareTo(other.checksum) == 0;
        }

        @Override
        public String toString() {
            return rows + " rows / checksum " + checksum;
        }
    }
}
