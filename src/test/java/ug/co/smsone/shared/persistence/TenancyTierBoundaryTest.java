package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Pins the tenancy boundary of ADR 0010 §2: every table is owned by the platform, by a tenant, or —
 * for the seven split ones — by both, and no foreign key joins two different owners.
 *
 * <p>The tier is recorded as a {@code **Tier:**} line per table in {@code docs/DATA_MODEL.md} and read
 * back from there, deliberately. A second copy in Java would be the thing that drifts, and the document
 * is what a human reads before adding a table; a constant nobody opens would not stop the decision from
 * being skipped. Nothing here parses the migrations for a table list either — the list comes from
 * {@code information_schema} against the real Postgres, so a table that arrives in V60 fails this test
 * on the day it lands rather than the day someone tries to extract a tenant. A boundary rots by
 * accretion, not by decision, and Phase 0 routes nothing: these assertions are the only thing standing
 * between the decision and the phase that actually moves data.
 *
 * <p><b>{@link #noForeignKeyCrossesTheTenancyBoundary()} rests on V53</b>, which cut the five boundary
 * foreign keys of ADR 0010 §6 ({@code membership}, {@code org_role} and {@code org_group} to
 * {@code organization}, {@code org_subscription} to {@code plan}, {@code user_device_trust} to
 * {@code user_device}) down to soft refs. Revert that migration and this test is the thing that says so.
 *
 * <p>Complements {@link FlywayBaselineTest}, which proves the migrations ran at all.
 */
class TenancyTierBoundaryTest extends AbstractIntegrationTest {

    private static final Path DATA_MODEL = Path.of("docs", "DATA_MODEL.md");

    /** Table sections are the only {@code ###} headings in the document, and are named exactly. */
    private static final Pattern TABLE_HEADING = Pattern.compile("^### ([a-z_][a-z0-9_]*)\\s*$");

    private static final Pattern TIER_LINE = Pattern.compile("^\\*\\*Tier:\\*\\*\\s+(.+)$");
    private static final Pattern DECLARED_TABLE_COUNT = Pattern.compile("\\*\\*(\\d+) tables\\*\\*");
    private static final String COLUMN_HEADER = "| Column | Type | Null | Description |";

    /**
     * A tier line is {@code platform + tenant — <the rule deciding which copy a row belongs to>}: the
     * tier is everything before the em dash, the clause after it is prose for a human and is not parsed.
     * Only the tier is machine-readable, so the reasons can be rewritten without touching this test.
     */
    private static final String CLAUSE_SEPARATOR = "—";

    /** ADR 0010 §2's three homes. An unknown fourth is a typo, not a new tier, and fails the parse. */
    private static final Set<String> KNOWN_TIERS = Set.of("platform", "tenant", "platform + tenant");

    /**
     * Flyway creates it, no migration declares it, and {@code docs/DATA_MODEL.md} says in its header
     * that it is not counted — so it is not a table anyone assigns a tier to.
     */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The gate: a table cannot reach production without a tenancy decision recorded against it. Asserted
     * in both directions — a schema table with no entry is an undecided table, and an entry with no
     * schema table is a tier for something that no longer exists, which is how the document starts
     * lying. Set equality against the live schema also means this can never pass vacuously: a query that
     * returned nothing (a wrong {@code search_path}, say) fails against the tables the document names.
     */
    @Test
    void everyTableInTheSchemaHasATenancyTierRecordedInTheDataModel() throws IOException {
        DataModel doc = parse();

        assertThat(doc.tables())
                .describedAs("every ### table in %s needs a '**Tier:** …' line under it (ADR 0010 §2)",
                        DATA_MODEL)
                .containsExactlyInAnyOrderElementsOf(doc.tiers().keySet());

        assertThat(doc.tiers().keySet())
                .describedAs("%s must record a tier for exactly the tables that exist", DATA_MODEL)
                .containsExactlyInAnyOrderElementsOf(schemaTables());
    }

    /**
     * The tier line is only worth reading if the rest of the entry is true, and this document had
     * already drifted: V51 dropped {@code user_device.trusted} and added {@code user_device_trust}, and
     * neither reached the document — so the header claimed 54 tables against a schema holding 55, and
     * the one table the whole device-trust bypass turns on was undocumented. AGENTS §13 already makes
     * updating this file a duty on any column change; this is that duty with teeth.
     */
    @Test
    void everyColumnInTheSchemaIsDocumentedAndNothingIsDocumentedThatIsGone() throws IOException {
        DataModel doc = parse();
        List<String> tables = schemaTables();

        assertThat(doc.declaredTableCount())
                .describedAs("the '**N tables**' count in %s's header", DATA_MODEL)
                .isEqualTo(tables.size());

        for (String table : tables) {
            assertThat(doc.columns().getOrDefault(table, List.of()))
                    .describedAs("columns documented for %s in %s", table, DATA_MODEL)
                    .containsExactlyInAnyOrderElementsOf(schemaColumns(table));
        }
    }

    /**
     * A tier boundary is a future schema boundary and then a future database boundary, and a foreign key
     * cannot span either — so the rule is stricter than "platform must not reference tenant": both ends
     * must carry the <em>same</em> tier. A split ({@code platform + tenant}) table exists in both
     * schemas, so a foreign key from it to a platform-only table would resolve for the platform copy and
     * dangle for the tenant one.
     *
     * <p>This is the gap in AGENTS §1's foreign-key rule that ADR 0010 §6 names: that rule is stated on
     * the module axis, and every one of the five survivors is an <em>intra</em>-module key that happens
     * to cross the tenant axis.
     *
     * <p>Deliberately no assertion on the number of foreign keys. The invariant is that no key crosses,
     * not that there are twelve; a count would fail on the next legitimate intra-tier key and teach
     * whoever hits it that the number is the rule.
     */
    @Test
    void noForeignKeyCrossesTheTenancyBoundary() throws IOException {
        Map<String, String> tiers = parse().tiers();

        List<Map<String, Object>> foreignKeys = jdbc.queryForList(
                """
                select con.conname as constraint_name,
                       child.relname as child_table,
                       parent.relname as parent_table
                  from pg_constraint con
                  join pg_class child on child.oid = con.conrelid
                  join pg_class parent on parent.oid = con.confrelid
                  join pg_namespace ns on ns.oid = child.relnamespace
                 where con.contype = 'f'
                   and ns.nspname = current_schema()
                 order by con.conname
                """);

        assertThat(foreignKeys)
                .describedAs("the schema still declares foreign keys; an empty result would pass this "
                        + "test for the wrong reason")
                .isNotEmpty();

        List<String> crossing = new ArrayList<>();
        for (Map<String, Object> fk : foreignKeys) {
            String child = (String) fk.get("child_table");
            String parent = (String) fk.get("parent_table");
            String childTier = tierOf(tiers, child);
            String parentTier = tierOf(tiers, parent);
            if (!childTier.equals(parentTier)) {
                crossing.add("%s: %s (%s) -> %s (%s)"
                        .formatted(fk.get("constraint_name"), child, childTier, parent, parentTier));
            }
        }

        assertThat(crossing)
                .describedAs("foreign keys joining two tenancy tiers — cut them to soft refs (ADR 0010 §6)")
                .isEmpty();
    }

    private static String tierOf(Map<String, String> tiers, String table) {
        String tier = tiers.get(table);
        assertThat(tier).describedAs("tier recorded for %s in %s", table, DATA_MODEL).isNotNull();
        return tier;
    }

    private List<String> schemaTables() {
        return jdbc.queryForList(
                """
                select table_name from information_schema.tables
                 where table_schema = current_schema()
                   and table_type = 'BASE TABLE'
                   and table_name <> ?
                """,
                String.class, FLYWAY_HISTORY);
    }

    private List<String> schemaColumns(String table) {
        return jdbc.queryForList(
                """
                select column_name from information_schema.columns
                 where table_schema = current_schema() and table_name = ?
                """,
                String.class, table);
    }

    /** What {@code docs/DATA_MODEL.md} claims: the tables it covers, their tiers and their columns. */
    private record DataModel(List<String> tables, Map<String, String> tiers,
                             Map<String, List<String>> columns, int declaredTableCount) {}

    private static DataModel parse() throws IOException {
        List<String> tables = new ArrayList<>();
        Map<String, String> tiers = new LinkedHashMap<>();
        Map<String, List<String>> columns = new LinkedHashMap<>();
        int declaredTableCount = -1;
        String table = null;
        boolean inColumnTable = false;

        for (String line : Files.readAllLines(DATA_MODEL, StandardCharsets.UTF_8)) {
            if (declaredTableCount < 0) {
                Matcher count = DECLARED_TABLE_COUNT.matcher(line);
                if (count.find()) {
                    declaredTableCount = Integer.parseInt(count.group(1));
                }
            }

            Matcher heading = TABLE_HEADING.matcher(line);
            if (heading.matches()) {
                table = heading.group(1);
                assertThat(tables).describedAs("%s is documented twice", table).doesNotContain(table);
                tables.add(table);
                inColumnTable = false;
                continue;
            }

            if (line.isBlank()) {
                inColumnTable = false;
                continue;
            }
            if (line.strip().equals(COLUMN_HEADER)) {
                inColumnTable = true;
                continue;
            }
            // "|---|---|..." is the header rule, not a column.
            if (inColumnTable && line.startsWith("|") && !line.startsWith("|---")) {
                columns.computeIfAbsent(table, ignored -> new ArrayList<>()).add(firstCell(line));
                continue;
            }

            Matcher tier = TIER_LINE.matcher(line);
            if (tier.matches()) {
                assertThat(table)
                        .describedAs("a '**Tier:**' line above the first ### table heading")
                        .isNotNull();
                String value = tier.group(1).split(CLAUSE_SEPARATOR, 2)[0].strip();
                assertThat(value).describedAs("tier recorded for %s", table).isIn(KNOWN_TIERS);
                assertThat(tiers.put(table, value))
                        .describedAs("%s carries more than one '**Tier:**' line", table)
                        .isNull();
            }
        }

        assertThat(declaredTableCount)
                .describedAs("%s's header states how many tables it covers", DATA_MODEL)
                .isNotNegative();
        return new DataModel(tables, tiers, columns, declaredTableCount);
    }

    private static String firstCell(String row) {
        return row.split("\\|")[1].strip();
    }
}
