package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.Table;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The gate on ADR 0010 §2's addressing rule: a PLATFORM-tier table is always named
 * {@code platform.<table>}, a TENANT-tier table never is.
 *
 * <p><b>Why it is a rule and not a convention.</b> Hibernate honours a schema declared on
 * {@code @Table} whatever the connection's {@code search_path} is, and raw SQL honours a schema
 * written into the statement, so an explicit {@code platform.} is what keeps a platform read correct
 * on a tenant-pinned connection — and leaving a tenant table bare is what lets one query serve
 * {@code tenant_pool} today and {@code t_<hex>} after promotion. The two halves are the same
 * mechanism seen from either side. Get it backwards on ONE table and it reads the wrong tier, which
 * is the worst failure this design can produce.
 *
 * <p><b>And it fails silently in both directions.</b> A platform table addressed bare from a tenant
 * axis does not resolve to a different tenant's rows — it resolves to nothing, and the shapes that
 * follow are all quiet: an {@code UPDATE} matching zero rows, a {@code NOT EXISTS} guard that
 * excludes nothing, a reconciler whose exception is caught and logged at 04:00. A tenant table
 * addressed as {@code platform.ticket} is worse still, because after promotion it would read the
 * pool while the tenant's own rows sit in a silo. Neither is a compile error and neither is visible
 * in review, which is what a build gate is for. The qualification itself was a one-time edit; this
 * is the half that stops the next entity from undoing it.
 *
 * <p><b>The tier is read from {@code docs/DATA_MODEL.md}</b> — the {@code Tier:} line Phase 0 put on
 * every table, and the same source {@code TenancyTierBoundaryTest} enforces the completeness of. A
 * copy of the assignment in Java would be the thing that drifts, and it would drift toward whatever
 * the code already does, which is exactly the assertion this test must not make. Nothing here reads
 * the database, so it runs without a container and fails on the commit rather than on the run.
 *
 * <p>Split ({@code platform + tenant}) tables are the deliberate hole: the same DDL lives in both
 * schemas, so {@code audit_log} bare (the tenant's copy, chosen by {@code search_path}) and
 * {@code platform.audit_log} (the null-org copy) are both correct and only the routing adapter knows
 * which one a call wants. What is still an error for them is any OTHER schema — {@code tenant_pool.}
 * hard-codes the pool and stops being true the day the tenant is promoted.
 *
 * <p><b>What it cannot see, and what the codebase does instead.</b> A lexical scan only recognises a
 * table name that sits in the same string literal as the keyword introducing it, so a name assembled
 * at runtime — {@code DELETE_BATCH.formatted(table)}, {@code "update " + owned} — is invisible here.
 * That is why the two places doing it resolve the schema from the entity mapping
 * ({@code MappedTables}) or from a single method on the constant ({@code ComplianceService.OwnedTable
 * .qualified()}) rather than from a list of their own: the mapping is what the first rule below pins,
 * so the dynamic names inherit a checked answer instead of needing an unenforceable one. A literal
 * holding a bare relation with its keyword concatenated on elsewhere would slip through, which is why
 * {@code SearchQueryService} keeps {@code from} inside the fragment.
 */
class PlatformSchemaQualificationTest {

    private static final Path DATA_MODEL = Path.of("docs", "DATA_MODEL.md");
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final String PLATFORM_SCHEMA = "platform";

    /** ADR 0010 §2's three homes, spelled as {@code docs/DATA_MODEL.md} spells them. */
    private static final String TIER_PLATFORM = "platform";
    private static final String TIER_TENANT = "tenant";
    private static final String TIER_SPLIT = "platform + tenant";

    private static final Pattern TABLE_HEADING = Pattern.compile("^### ([a-z_][a-z0-9_]*)\\s*$");
    private static final Pattern TIER_LINE = Pattern.compile("^\\*\\*Tier:\\*\\*\\s+(.+)$");

    /**
     * A table reference in SQL: the keyword that can only be followed by a relation, then the name.
     * {@code delete from x} is covered by {@code from}; {@code insert into x} by {@code into}.
     * A name interpolated at runtime ({@code from %1$s}, {@code "update " + table}) does not match and
     * is not meant to — those resolve their schema from the entity mapping through
     * {@code MappedTables}, which this test pins through the {@code @Table} rules below.
     */
    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:from|join|into|update)\\s+([a-z_][a-z0-9_]*)(?:\\.([a-z_][a-z0-9_]*))?\\b");

    private static final Map<String, String> TIERS = tiers();

    /**
     * Rule one: the mapping. Hibernate qualifies every statement it generates from this, so a wrong
     * or missing {@code schema} here is wrong for the entity's whole surface at once — every finder,
     * every save, every cascade — and no query has to be read to see it.
     */
    @Test
    void everyMappedTableDeclaresTheSchemaItsTierRequires() {
        List<String> violations = new ArrayList<>();
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ug.co.smsone");

        for (JavaClass entity : classes) {
            entity.tryGetAnnotationOfType(Table.class).ifPresent(table ->
                    check(violations, entity.getName() + " @Table", table.name(), table.schema()));
            entity.tryGetAnnotationOfType(SecondaryTable.class).ifPresent(table ->
                    check(violations, entity.getName() + " @SecondaryTable", table.name(), table.schema()));
            for (JavaField field : entity.getAllFields()) {
                field.tryGetAnnotationOfType(CollectionTable.class).ifPresent(table ->
                        check(violations, entity.getName() + "." + field.getName() + " @CollectionTable",
                                table.name(), table.schema()));
            }
        }

        assertThat(entitiesWithATable(classes))
                .describedAs("no @Entity was imported — this test would pass without checking anything")
                .isNotEmpty()
                .allSatisfy(table -> assertThat(TIERS)
                        .describedAs("%s has no '**Tier:**' line in %s (ADR 0010 §2)", table, DATA_MODEL)
                        .containsKey(table));

        assertThat(violations)
                .describedAs("entity mappings that name the wrong tenancy home (ADR 0010 §2)")
                .isEmpty();
    }

    /**
     * Rule two: every table named in hand-written SQL — the {@code @SQLDelete}/{@code @Query} strings
     * Hibernate passes through untouched, and the ~22 classes that hold a {@code JdbcTemplate} and
     * never go near the mapping at all. Those are the ones a missed qualification fails at RUNTIME
     * rather than at compile time, which is why the check reads the source rather than the bytecode:
     * ArchUnit can see an annotation's value but not the string a method call was handed.
     */
    @Test
    void everyTableNamedInHandWrittenSqlIsAddressedByItsTier() {
        List<String> violations = new ArrayList<>();
        int qualifiedPlatformReferences = 0;
        int bareTenantReferences = 0;

        for (SourceFile source : mainSources()) {
            for (Literal literal : source.literals()) {
                Matcher reference = TABLE_REFERENCE.matcher(literal.text());
                while (reference.find()) {
                    // "platform.person" parses as (schema, table); "person" as (table, null).
                    String schema = reference.group(2) == null ? null : reference.group(1);
                    String table = reference.group(2) == null ? reference.group(1) : reference.group(2);
                    String tier = TIERS.get(table);
                    if (tier == null) {
                        continue; // not one of ours: a DuckDB mart, a catalog view, an alias
                    }
                    String where = source.path() + ":" + literal.line();
                    if (TIER_PLATFORM.equals(tier)) {
                        if (PLATFORM_SCHEMA.equals(schema)) {
                            qualifiedPlatformReferences++;
                        } else {
                            violations.add(where + " — '" + table + "' is platform-tier and must be named"
                                    + " platform." + table + ", was '" + reference.group() + "'");
                        }
                    } else if (TIER_TENANT.equals(tier)) {
                        if (schema == null) {
                            bareTenantReferences++;
                        } else {
                            violations.add(where + " — '" + table + "' is tenant-tier and must be"
                                    + " unqualified so search_path places it, was '" + reference.group() + "'");
                        }
                    } else if (schema != null && !PLATFORM_SCHEMA.equals(schema)) {
                        violations.add(where + " — '" + table + "' is split (platform + tenant): bare for"
                                + " the tenant copy or platform." + table + " for the platform one, never '"
                                + reference.group() + "'");
                    }
                }
            }
        }

        assertThat(violations)
                .describedAs("hand-written SQL naming the wrong tenancy home (ADR 0010 §2)")
                .isEmpty();
        // Both arms have to be live. A literal scanner that silently stopped matching — a Java syntax
        // it cannot lex, a source tree it cannot find — would report zero violations forever, and the
        // one thing this test must never do is pass because it looked at nothing.
        assertThat(qualifiedPlatformReferences)
                .describedAs("no `platform.<table>` reference found in any SQL literal — the scanner is "
                        + "not reading the source it thinks it is")
                .isPositive();
        assertThat(bareTenantReferences)
                .describedAs("no bare tenant-tier reference found in any SQL literal — same")
                .isPositive();
    }

    private static void check(List<String> violations, String site, String table, String schema) {
        String tier = TIERS.get(table);
        if (tier == null) {
            return; // reported by the completeness assertion, which names the file to fix
        }
        String declared = schema.isBlank() ? null : schema;
        if (TIER_PLATFORM.equals(tier) && !PLATFORM_SCHEMA.equals(declared)) {
            violations.add(site + " maps platform-tier '" + table + "' with schema="
                    + describe(declared) + "; it must declare schema = \"platform\", or a read of it on a"
                    + " tenant-pinned connection resolves through search_path and finds nothing");
        }
        if (TIER_TENANT.equals(tier) && declared != null) {
            violations.add(site + " maps tenant-tier '" + table + "' with schema=\"" + declared
                    + "\"; it must declare none, or the tenant's rows stop being wherever search_path"
                    + " says they are and a promoted tenant keeps reading the pool");
        }
        if (TIER_SPLIT.equals(tier) && declared != null && !PLATFORM_SCHEMA.equals(declared)) {
            violations.add(site + " maps split table '" + table + "' with schema=\"" + declared + "\"");
        }
    }

    private static String describe(String schema) {
        return schema == null ? "none" : "\"" + schema + "\"";
    }

    private static Set<String> entitiesWithATable(JavaClasses classes) {
        Set<String> tables = new TreeSet<>();
        for (JavaClass type : classes) {
            if (type.isAnnotatedWith(Entity.class)) {
                type.tryGetAnnotationOfType(Table.class)
                        .filter(table -> !table.name().isBlank())
                        .ifPresent(table -> tables.add(table.name()));
            }
        }
        return tables;
    }

    /** Table name to tier, straight off the {@code **Tier:**} lines — see the class note. */
    private static Map<String, String> tiers() {
        Map<String, String> tiers = new LinkedHashMap<>();
        String table = null;
        try {
            for (String line : Files.readAllLines(DATA_MODEL, StandardCharsets.UTF_8)) {
                Matcher heading = TABLE_HEADING.matcher(line);
                if (heading.matches()) {
                    table = heading.group(1);
                    continue;
                }
                Matcher tier = TIER_LINE.matcher(line);
                if (tier.matches() && table != null) {
                    // "platform + tenant — <prose>": the tier is everything before the em dash. Same
                    // parse as TenancyTierBoundaryTest, deliberately reading the same lines.
                    tiers.put(table, tier.group(1).split("—", 2)[0].strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + DATA_MODEL.toAbsolutePath(), e);
        }
        assertThat(tiers.values())
                .describedAs("tiers parsed out of %s", DATA_MODEL)
                .isNotEmpty()
                .allMatch(Set.of(TIER_PLATFORM, TIER_TENANT, TIER_SPLIT)::contains);
        return Map.copyOf(tiers);
    }

    private static List<SourceFile> mainSources() {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            List<SourceFile> sources = files.filter(path -> path.toString().endsWith(".java"))
                    .map(SourceFile::read)
                    .toList();
            assertThat(sources).describedAs("java sources under %s", MAIN_SOURCES).isNotEmpty();
            return sources;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + MAIN_SOURCES.toAbsolutePath(), e);
        }
    }

    private record Literal(String text, int line) {}

    private record SourceFile(String path, List<Literal> literals) {

        static SourceFile read(Path path) {
            try {
                return new SourceFile(path.toString(), literalsIn(Files.readString(path, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + path, e);
            }
        }

        /**
         * Every string literal in a compilation unit, comments excluded.
         *
         * <p>Hand-lexed rather than regexed because both mistakes a regex makes here are the wrong
         * way round: javadoc in this codebase is dense with {@code {@code from person}}, so treating
         * comments as text produces false failures on prose, and text blocks hold most of the SQL, so
         * missing them produces false passes on exactly the statements that matter most.
         */
        private static List<Literal> literalsIn(String source) {
            List<Literal> literals = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int line = 1;
            int startLine = 1;
            int i = 0;
            int length = source.length();
            while (i < length) {
                char c = source.charAt(i);
                if (c == '\n') {
                    line++;
                }
                if (source.startsWith("//", i)) {
                    while (i < length && source.charAt(i) != '\n') {
                        i++;
                    }
                    continue;
                }
                if (source.startsWith("/*", i)) {
                    int end = source.indexOf("*/", i + 2);
                    int stop = end < 0 ? length : end + 2;
                    line += (int) source.substring(i, stop).chars().filter(ch -> ch == '\n').count();
                    i = stop;
                    continue;
                }
                if (c == '\'') {
                    i++; // a char literal holds no table name; skip it so its quote cannot open a string
                    while (i < length && source.charAt(i) != '\'') {
                        i += source.charAt(i) == '\\' ? 2 : 1;
                    }
                    i++;
                    continue;
                }
                if (source.startsWith("\"\"\"", i)) {
                    startLine = line;
                    i += 3;
                    current.setLength(0);
                    while (i < length && !source.startsWith("\"\"\"", i)) {
                        char t = source.charAt(i);
                        if (t == '\n') {
                            line++;
                        }
                        if (t == '\\') {
                            i += 2;
                            continue;
                        }
                        current.append(t);
                        i++;
                    }
                    i += 3;
                    literals.add(new Literal(current.toString(), startLine));
                    continue;
                }
                if (c == '"') {
                    startLine = line;
                    i++;
                    current.setLength(0);
                    while (i < length && source.charAt(i) != '"') {
                        if (source.charAt(i) == '\\') {
                            i += 2;
                            continue;
                        }
                        current.append(source.charAt(i));
                        i++;
                    }
                    i++;
                    literals.add(new Literal(current.toString(), startLine));
                    continue;
                }
                i++;
            }
            return literals;
        }
    }
}
