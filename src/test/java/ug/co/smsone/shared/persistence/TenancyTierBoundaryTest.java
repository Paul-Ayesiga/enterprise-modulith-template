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
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <p><b>Since Phase 2 a tier is also an address.</b> Through Phase 1 the tier was a recorded decision
 * and nothing else: all 56 tables sat in one schema and a wrong tier line cost nothing until extraction
 * day. Phase 2 moved them, so a tier now names the schema (or schemas) a table must physically occupy —
 * {@code platform} for the platform tier, {@code tenant_pool} for the tenant tier, both for the seven
 * split ones. Every assertion here is against the UNION of those two schemas rather than against
 * {@code current_schema()}, which is what makes this test strictly stronger than the Phase 1 version it
 * replaces: it no longer only asks whether a table is documented, it asks whether it landed where its
 * documentation says it did. A table created by the wrong migration directory now fails here, and that
 * is a mistake with no other detector — it resolves perfectly well at runtime right up until someone
 * runs {@code pg_dump -n tenant_pool} and finds half a tenant.
 *
 * <p>Reading {@code current_schema()} would be worse than wrong now, it would be quietly wrong: with an
 * axis pinned it names ONE schema holding a subset, so a set-equality assertion against the whole
 * document would fail for the platform tier and a per-tier one would pass without ever looking at the
 * tenant tables.
 *
 * <p>The tier is recorded as a {@code **Tier:**} line per table in {@code docs/DATA_MODEL.md} and read
 * back from there, deliberately. A second copy in Java would be the thing that drifts, and the document
 * is what a human reads before adding a table; a constant nobody opens would not stop the decision from
 * being skipped. Nothing here parses the migrations for a table list either — the list comes from
 * {@code information_schema} against the real Postgres, so a table that arrives in V60 fails this test
 * on the day it lands rather than the day someone tries to extract a tenant. A boundary rots by
 * accretion, not by decision.
 *
 * <p><b>{@link #noForeignKeyCrossesTheTenancyBoundary()} rests on V53</b>, which cut the five boundary
 * foreign keys of ADR 0010 §6 ({@code membership}, {@code org_role} and {@code org_group} to
 * {@code organization}, {@code org_subscription} to {@code plan}, {@code user_device_trust} to
 * {@code user_device}) down to soft refs. Revert that migration and this test is the thing that says so.
 *
 * <p>One span, not two. Every query below names its schemas in the statement — {@code information_schema}
 * and {@code pg_catalog} resolve from any axis, and the schema is a WHERE predicate rather than the
 * connection's {@code search_path} — so the harness's platform pin serves the whole class and nothing
 * here needs a second connection.
 *
 * <p>Complements {@link FlywayBaselineTest}, which proves the migrations ran at all, and
 * {@link PlatformSchemaQualificationTest}, which reads the same tier lines and proves the CODE addresses
 * each table the way its tier requires. This one proves the DATABASE agrees.
 */
class TenancyTierBoundaryTest extends AbstractIntegrationTest {

    private static final Path DATA_MODEL = Path.of("docs", "DATA_MODEL.md");

    /** Table sections are the only {@code ###} headings in the document, and are named exactly. */
    private static final Pattern TABLE_HEADING = Pattern.compile("^### ([a-z_][a-z0-9_]*)\\s*$");

    private static final Pattern TIER_LINE = Pattern.compile("^\\*\\*Tier:\\*\\*\\s+(.+)$");
    private static final Pattern DECLARED_TABLE_COUNT = Pattern.compile("\\*\\*(\\d+) tables\\*\\*");
    private static final String COLUMN_HEADER = "| Column | Type | Null | Description |";

    private static final String PLATFORM_SCHEMA = "platform";

    /**
     * The tenant tier's shared schema — the home of every tenant nobody has promoted, and the reference
     * every silo is compared against. Spelled literally rather than taken from
     * {@code TenantSchemas.TENANT_POOL}: this test is one half of the pair that says where the tables
     * ACTUALLY are, and reading the constant the router routes by would make it agree with the router
     * by construction instead of checking it.
     */
    private static final String TENANT_SCHEMA = "tenant_pool";

    /**
     * ADR 0010 §3.1's silo name, as a Postgres regex — the same shape {@code TenantSchemas.siloSchema}
     * mints. Written as SQL here for the same reason {@link #TENANT_SCHEMA} is a literal: the question
     * is what the CATALOGUE holds, and asking it through the class that decides the name would make the
     * answer agree with the namer rather than with the database.
     */
    private static final String SILO_SCHEMA_PATTERN = "^t_[0-9a-f]{32}$";

    /**
     * A tier line is {@code platform + tenant — <the rule deciding which copy a row belongs to>}: the
     * tier is everything before the em dash, the clause after it is prose for a human and is not parsed.
     * Only the tier is machine-readable, so the reasons can be rewritten without touching this test.
     */
    private static final String CLAUSE_SEPARATOR = "—";

    /**
     * ADR 0010 §2's three homes, and what each one now MEANS physically. An unknown fourth is a typo,
     * not a new tier, and fails the parse.
     */
    private static final Map<String, Set<String>> SCHEMAS_BY_TIER = Map.of(
            "platform", Set.of(PLATFORM_SCHEMA),
            "tenant", Set.of(TENANT_SCHEMA),
            "platform + tenant", Set.of(PLATFORM_SCHEMA, TENANT_SCHEMA));

    /**
     * Flyway creates it, no migration declares it, and {@code docs/DATA_MODEL.md} says in its header
     * that it is not counted — so it is not a table anyone assigns a tier to.
     */
    private static final String FLYWAY_HISTORY = "flyway_schema_history";

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The gate: a table cannot reach production without a tenancy decision recorded against it, AND it
     * must sit in the schema (or both schemas) that decision names.
     *
     * <p>Asserted in every direction. A table with no entry is an undecided table; an entry with no
     * table is a tier for something that no longer exists, which is how the document starts lying; and a
     * table whose homes disagree with its tier is the Phase 2 mistake that has no other detector — a
     * platform-tier table created by {@code db/migration/tenant/} works perfectly on a tenant-pinned
     * connection and is simply absent from the platform one, silently, until something reads it from a
     * job. Set equality against the live schemas also means this can never pass vacuously: a query that
     * returned nothing fails against the tables the document names.
     */
    @Test
    void everyTableCarriesATierAndSitsInExactlyTheSchemasThatTierNames() throws IOException {
        DataModel doc = parse();
        Map<String, Set<String>> homes = tableHomes();

        assertThat(doc.tables())
                .describedAs("every ### table in %s needs a '**Tier:** …' line under it (ADR 0010 §2)",
                        DATA_MODEL)
                .containsExactlyInAnyOrderElementsOf(doc.tiers().keySet());

        assertThat(doc.tiers().keySet())
                .describedAs("%s must record a tier for exactly the tables that exist in %s and %s",
                        DATA_MODEL, PLATFORM_SCHEMA, TENANT_SCHEMA)
                .containsExactlyInAnyOrderElementsOf(homes.keySet());

        assertThat(doc.tiers()).allSatisfy((table, tier) -> assertThat(homes.get(table))
                .describedAs("%s is '%s' in %s, so it must exist in exactly the schemas that tier names"
                        + " — a copy in the wrong one is a table an extraction leaves behind (ADR 0010 §2)",
                        table, tier, DATA_MODEL)
                .isEqualTo(SCHEMAS_BY_TIER.get(tier)));
    }

    /**
     * Everything outside the data-bearing schemas, which must be nothing at all.
     *
     * <p>Each of the three is load-bearing and each fails silently if it stops being true. {@code ext}
     * sits on EVERY tenant's {@code search_path}, so a table there is a table every tenant can read
     * through fallthrough — the one thing that would make a multi-element path unsafe. {@code no_tenant}
     * is the poison schema an axis-less connection is pointed at, and it only converts a misrouted read
     * into {@code relation "…" does not exist} while it stays empty. {@code public} is what a tenant
     * must not depend on, or it cannot be lifted onto a database of its own.
     *
     * <p>Deliberately not a list of those three names: a table in a schema nobody declared is the same
     * failure and would be invisible to a per-name check. Postgres' own {@code pg_*} schemas (catalog,
     * toast, per-session temp) are excluded because they are not ours to be empty.
     *
     * <p><b>Silo schemas are excluded here and checked properly in
     * {@link #everySiloHoldsExactlyTheTenantTierAndNothingElse()}.</b> Through Phase 4 there was exactly
     * one tenant home and "outside {@code platform} and {@code tenant_pool}" and "not a tenant home"
     * were the same sentence. Phase 5 made them different: a {@code t_<32hex>} schema is a tenant home,
     * it is what the whole design exists to produce, and listing it here as a stray would fail this test
     * for every promoted tenant in production. The exclusion is the NAME SHAPE and nothing else — a
     * schema called anything but {@code t_} plus 32 lower-case hex is still a stray, and a silo that
     * holds the wrong tables now fails one assertion down instead of not being looked at.
     */
    @Test
    void nothingLivesOutsideTheDataBearingSchemas() {
        assertThat(jdbc.queryForList(
                """
                select table_schema || '.' || table_name from information_schema.tables
                 where table_type = 'BASE TABLE'
                   and table_schema not in ('information_schema', ?, ?)
                   and table_schema !~ '^pg_'
                   and table_schema !~ ?
                 order by 1
                """,
                String.class, PLATFORM_SCHEMA, TENANT_SCHEMA, SILO_SCHEMA_PATTERN))
                .describedAs("tables outside %s, %s and the tenant silos — `ext` is on every tenant's "
                        + "path, `no_tenant` only fails closed while it is empty, and `public` is what a "
                        + "lifted tenant must not need (ADR 0010 §3.1)", PLATFORM_SCHEMA, TENANT_SCHEMA)
                .isEmpty();
    }

    /**
     * <b>A silo is a tenant home, so it holds the tenant tier — every table of it, and not one table
     * more.</b> ADR 0010 §7 Phase 5's boundary claim, and the one this test can make that nothing at
     * runtime would ever notice being false.
     *
     * <p>Both directions matter and they fail in opposite ways. A silo MISSING a tenant table is a
     * tenant whose next request 500s on {@code relation "…" does not exist} — loud, local, and easy.
     * A silo carrying a PLATFORM table is the quiet one: it resolves perfectly well (the platform copy
     * is reached by explicit qualification, so nothing reads the stray), it survives every test in this
     * suite, and it surfaces on extraction day as a {@code pg_dump -n t_<hex>} that carries a slice of
     * the platform's catalogue — plans, settings, people — into what was supposed to be one tenant's
     * data. That is the mistake §2 exists to prevent, and replaying the platform migration sequence into
     * a tenant schema is the single way to make it.
     *
     * <p>Asserted against {@code tenant_pool}'s own table set rather than against a list parsed out of
     * the document: the pool is migrated by the same {@code TenantSchemaMigrator} that builds every
     * silo, so equality here is the statement that the two ran the same sequence. A silo built from a
     * different directory disagrees immediately, whatever the document says.
     *
     * <p>Vacuous when no silo exists, which is honest rather than a gap: under the pooled policy there
     * are none, and {@code TenantSilosHarnessTest} is what proves a placed tenant gets one at all.
     */
    @Test
    void everySiloHoldsExactlyTheTenantTierAndNothingElse() {
        Set<String> tenantHome = new TreeSet<>(tablesIn(TENANT_SCHEMA));

        for (String silo : jdbc.queryForList(
                "select schema_name from information_schema.schemata where schema_name ~ ? order by 1",
                String.class, SILO_SCHEMA_PATTERN)) {
            assertThat(new TreeSet<>(tablesIn(silo)))
                    .describedAs("%s must hold exactly what %s holds — both are built by the same tenant"
                            + " migration sequence, so any difference means one of them was migrated from"
                            + " the wrong directory (ADR 0010 §2, §4.1)", silo, TENANT_SCHEMA)
                    .isEqualTo(tenantHome);
        }
    }

    private List<String> tablesIn(String schema) {
        return jdbc.queryForList(
                "select table_name from information_schema.tables"
                        + " where table_schema = ? and table_type = 'BASE TABLE'"
                        // Flyway's own bookkeeping is per-schema and belongs to no tier — it is in every
                        // tenant home including the pool, so comparing the two sets never sees it, but
                        // excluding it keeps the failure message about tenant tables.
                        + " and table_name <> 'flyway_schema_history'",
                String.class, schema);
    }

    /**
     * The tier line is only worth reading if the rest of the entry is true, and this document had
     * already drifted: V51 dropped {@code user_device.trusted} and added {@code user_device_trust}, and
     * neither reached the document — so the header claimed 54 tables against a schema holding 55, and
     * the one table the whole device-trust bypass turns on was undocumented. AGENTS §13 already makes
     * updating this file a duty on any column change; this is that duty with teeth.
     *
     * <p>Since Phase 2 it does a second job for free. A split table's DDL is written twice — once in
     * {@code db/migration/platform/}, once in {@code db/migration/tenant/} — and both copies are checked
     * against the SINGLE documented column list, so the two are proved identical. A column added to one
     * sequence and forgotten in the other is a table that means different things depending on which axis
     * read it, and nothing else in the build would notice.
     */
    @Test
    void everyColumnInBothSchemasIsDocumentedAndNothingIsDocumentedThatIsGone() throws IOException {
        DataModel doc = parse();
        Map<String, Set<String>> homes = tableHomes();

        assertThat(doc.declaredTableCount())
                .describedAs("the '**N tables**' count in %s's header — the seven split tables have two "
                        + "homes and are counted once, as the header itself says", DATA_MODEL)
                .isEqualTo(homes.size());

        homes.forEach((table, schemas) -> schemas.forEach(schema ->
                assertThat(doc.columns().getOrDefault(table, List.of()))
                        .describedAs("columns documented for %s in %s, against the real %s.%s",
                                table, DATA_MODEL, schema, table)
                        .containsExactlyInAnyOrderElementsOf(schemaColumns(schema, table))));
    }

    /**
     * A tier boundary is now a schema boundary and later a database boundary, and a foreign key cannot
     * span either — so the rule is stricter than "platform must not reference tenant": both ends must
     * carry the <em>same</em> tier, and both ends must sit in the same schema. A split
     * ({@code platform + tenant}) table exists in both schemas, so a foreign key from it to a
     * platform-only table would resolve for the platform copy and dangle for the tenant one.
     *
     * <p>The two halves catch different mistakes. The SCHEMA check is the physical one and it is what
     * literally stops {@code pg_dump -n tenant_pool} producing a restorable tenant — a constraint
     * reaching out of the schema comes back as an unsatisfiable reference. The TIER check is the one
     * that still bites where the physical one cannot: two tables can share a schema today and belong to
     * different tiers, and that key becomes a cross-database key at Phase 7 without anything moving in
     * between.
     *
     * <p>This is the gap in AGENTS §1's foreign-key rule that ADR 0010 §6 names: that rule is stated on
     * the module axis, and every one of the five survivors is an <em>intra</em>-module key that happens
     * to cross the tenant axis.
     *
     * <p>Deliberately no assertion on the number of foreign keys. The invariant is that no key crosses,
     * not that there are twelve; a count would fail on the next legitimate intra-tier key and teach
     * whoever hits it that the number is the rule. What IS asserted is that both schemas contributed
     * keys, because a query that had quietly stopped seeing one of them would report no crossings
     * forever.
     */
    @Test
    void noForeignKeyCrossesTheTenancyBoundary() throws IOException {
        Map<String, String> tiers = parse().tiers();

        List<Map<String, Object>> foreignKeys = jdbc.queryForList(
                """
                select con.conname as constraint_name,
                       childns.nspname as child_schema,
                       child.relname as child_table,
                       parentns.nspname as parent_schema,
                       parent.relname as parent_table
                  from pg_constraint con
                  join pg_class child on child.oid = con.conrelid
                  join pg_namespace childns on childns.oid = child.relnamespace
                  join pg_class parent on parent.oid = con.confrelid
                  join pg_namespace parentns on parentns.oid = parent.relnamespace
                 where con.contype = 'f'
                   and childns.nspname in (?, ?)
                 order by childns.nspname, con.conname
                """,
                PLATFORM_SCHEMA, TENANT_SCHEMA);

        List<String> leavingTheirSchema = new ArrayList<>();
        List<String> crossingTiers = new ArrayList<>();
        Set<String> schemasThatDeclaredKeys = new TreeSet<>();
        for (Map<String, Object> fk : foreignKeys) {
            String child = (String) fk.get("child_table");
            String parent = (String) fk.get("parent_table");
            String childSchema = (String) fk.get("child_schema");
            String parentSchema = (String) fk.get("parent_schema");
            schemasThatDeclaredKeys.add(childSchema);
            if (!childSchema.equals(parentSchema)) {
                leavingTheirSchema.add("%s: %s.%s -> %s.%s"
                        .formatted(fk.get("constraint_name"), childSchema, child, parentSchema, parent));
                continue;
            }
            String childTier = tierOf(tiers, child);
            String parentTier = tierOf(tiers, parent);
            if (!childTier.equals(parentTier)) {
                crossingTiers.add("%s in %s: %s (%s) -> %s (%s)".formatted(
                        fk.get("constraint_name"), childSchema, child, childTier, parent, parentTier));
            }
        }

        assertThat(schemasThatDeclaredKeys)
                .describedAs("both schemas must declare foreign keys; a result missing one of them would "
                        + "pass this test for the wrong reason")
                .containsExactlyInAnyOrder(PLATFORM_SCHEMA, TENANT_SCHEMA);
        assertThat(leavingTheirSchema)
                .describedAs("foreign keys reaching out of their own schema — a tenant carrying one of "
                        + "these cannot be dumped and restored on its own (ADR 0010 §6)")
                .isEmpty();
        assertThat(crossingTiers)
                .describedAs("foreign keys joining two tenancy tiers — cut them to soft refs (ADR 0010 §6)")
                .isEmpty();
    }

    private static String tierOf(Map<String, String> tiers, String table) {
        String tier = tiers.get(table);
        assertThat(tier).describedAs("tier recorded for %s in %s", table, DATA_MODEL).isNotNull();
        return tier;
    }

    /**
     * Every base table in the two data-bearing schemas, as table name to the schemas holding it. The
     * seven split tables come back with both; everything else with exactly one, and WHICH one is the
     * assertion.
     */
    private Map<String, Set<String>> tableHomes() {
        Map<String, Set<String>> homes = new TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                """
                select table_schema, table_name from information_schema.tables
                 where table_schema in (?, ?)
                   and table_type = 'BASE TABLE'
                   and table_name <> ?
                """,
                PLATFORM_SCHEMA, TENANT_SCHEMA, FLYWAY_HISTORY)) {
            homes.computeIfAbsent((String) row.get("table_name"), ignored -> new TreeSet<>())
                    .add((String) row.get("table_schema"));
        }
        return homes;
    }

    private List<String> schemaColumns(String schema, String table) {
        return jdbc.queryForList(
                """
                select column_name from information_schema.columns
                 where table_schema = ? and table_name = ?
                """,
                String.class, schema, table);
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
                assertThat(value).describedAs("tier recorded for %s", table)
                        .isIn(SCHEMAS_BY_TIER.keySet());
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
