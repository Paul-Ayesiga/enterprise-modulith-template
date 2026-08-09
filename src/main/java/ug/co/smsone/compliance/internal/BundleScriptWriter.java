package ug.co.smsone.compliance.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <strong>{@link TenantBundler.Sink} as a restorable SQL script</strong> — the concrete artifact an
 * operator hands to {@code psql} on the far side, and the shape ADR 0010 §6's acceptance test restores.
 *
 * <h2>Why this generates statements instead of shelling out to {@code pg_dump}</h2>
 *
 * <p>The full argument is in {@link TenantBundler}'s class note; the short form is that only ONE of the
 * eleven items is a faithful copy of a schema, and that one has a perfectly good hand-run form
 * ({@code pg_dump -n t_<hex>}) an operator can use directly. The other ten are platform-side rows
 * selected by an organization predicate, whole-table snapshots, a REDUCED person projection and four
 * absences — none of which any dump utility can express, because none of them is "this schema, as it
 * is". So the tenant half can come from {@code pg_dump} and this half cannot, and a writer that emits
 * both in one file is the only form in which the bundle is a single artifact.
 *
 * <h2>The literal encoder refuses rather than guesses</h2>
 *
 * <p>{@link #literal} handles exactly the JDBC types this schema produces and throws on anything else.
 * That is checkable rather than hopeful: across {@code platform} and the tenant schema the column types
 * are {@code uuid}, {@code varchar}, {@code text}, {@code bool}, {@code int4}, {@code int8},
 * {@code numeric} and {@code timestamptz}, plus three that a bundle never carries — {@code date} only
 * on {@code api_usage_daily} (stays), {@code tsvector} only on {@code search_document} (rebuilt), and
 * {@code timestamp without time zone} only on {@code shedlock} (fresh and empty). A new column type
 * therefore fails at the first row rather than producing a script that restores something subtly
 * different.
 *
 * <p><b>Timestamps are rendered as instants, not as local text.</b> {@code java.sql.Timestamp} is
 * turned into an ISO-8601 UTC literal with an explicit {@code ::timestamptz}, so neither the writing
 * JVM's default zone nor the restoring session's {@code TimeZone} can move a row's clock. Rendering
 * {@code Timestamp.toString()} would have done exactly that, silently, and the error would be a
 * constant offset that looks like data rather than like a bug.
 *
 * <p><b>Strings double their quotes and nothing else.</b> {@code standard_conforming_strings} is on by
 * default in every supported Postgres, so a backslash in a value is a backslash and {@code ''} is the
 * whole escape. The values reaching here came out of the database this bundle is copying — they are not
 * user input arriving on a request — but a bundle that could be broken by an apostrophe in an
 * organization's name would be broken by the first Irish surname it met.
 */
final class BundleScriptWriter implements TenantBundler.Sink, AutoCloseable {

    /**
     * Where the tenant's unqualified rows land. Editable in the emitted script and deliberately
     * {@code tenant_pool}: an extracted deployment serves exactly one tenant, so it needs no silo, and
     * §6's acceptance test restores into "an empty database seeded with a single-tenant platform
     * schema". A deployment that does place its one tenant in a {@code t_<hex>} schema changes one
     * line, which is the point of emitting the line at all.
     */
    private static final String DEFAULT_TENANT_SCHEMA = "tenant_pool";

    private final Writer out;
    private String lastTable;

    BundleScriptWriter(Writer out) {
        this.out = out;
    }

    /**
     * The script's head: what it is, where its unqualified names resolve, and the transaction.
     *
     * <p><b>One transaction around the whole restore</b>, so a script that fails half way leaves an
     * empty database rather than a tenant missing whichever tables came after the failure. Combined
     * with {@code psql -v ON_ERROR_STOP=1}, which the header tells the operator to use, that is the
     * difference between "the restore failed" and "the restore appeared to work".
     */
    void preamble(UUID orgId, String sourceSchema, TenantBundler.Mode mode) {
        write("-- ADR 0010 §6 extraction bundle for organization " + orgId);
        write("-- Taken from " + sourceSchema + " in " + mode + " mode.");
        write("-- Restore with:  psql -v ON_ERROR_STOP=1 -f <this file>");
        write("--");
        write("-- The destination must already have run BOTH migration sequences —");
        write("-- db/migration/platform into `platform`, db/migration/tenant into the schema below.");
        write("-- The schema half of this bundle is the migration set, deliberately: a dumped schema");
        write("-- would arrive carrying the SOURCE's flyway_schema_history, which is another schema's");
        write("-- applied-version record wearing this one's name.");
        write("--");
        write("-- Edit the next line if this deployment places its one tenant in a t_<32hex> silo.");
        write("set search_path to " + DEFAULT_TENANT_SCHEMA + ", ext;");
        write("begin;");
        write("");
    }

    @Override
    public void row(BundlePart part, String schema, String table, Map<String, Object> row) {
        String qualified = schema == null ? quoteIdentifier(table)
                : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        if (!qualified.equals(lastTable)) {
            write("-- " + part + ": " + qualified);
            lastTable = qualified;
        }
        List<String> columns = row.keySet().stream().map(BundleScriptWriter::quoteIdentifier).toList();
        List<String> values = row.values().stream().map(BundleScriptWriter::literal).toList();
        write("insert into " + qualified + " (" + String.join(", ", columns) + ") values ("
                + String.join(", ", values) + ");");
    }

    /**
     * The script's tail: the placement row, the commit, then the manifest as comments — including the
     * parts that are an ABSENCE and therefore have no statement anywhere above to represent them.
     *
     * <p>That is the whole reason the manifest is written into the script rather than kept beside it.
     * A restored deployment's most dangerous properties are the ones nothing in the file says: that
     * {@code shedlock} is empty on purpose, that the search index has to be rebuilt, that the API keys
     * are dead, that the person graph is a projection which will drift. An operator reading the file
     * they just ran is the one reader guaranteed to exist.
     */
    void epilogue(TenantBundler.Manifest manifest) {
        write("");
        writeThePlacementRow(manifest.orgId());
        write("commit;");
        write("");
        write("-- ---- MANIFEST " + "-".repeat(60));
        write("-- organization: " + manifest.orgId());
        write("-- taken from:   " + manifest.tenantSchema() + " (" + manifest.mode() + ")");
        write("-- rows:         " + manifest.rowsBundled());
        write("--");
        write("-- The eleven parts of ADR 0010 §6, in the document's order:");
        manifest.parts().forEach(part -> write("--   " + (part.part().ordinal() + 1) + ". "
                + part.part() + " [" + part.disposition() + "] " + part.rows() + " rows — "
                + part.note()));
        write("--");
        write("-- Object store (item 7): " + manifest.objectPrefixes()
                + (manifest.objectBytes() == null
                        ? "  *** NOT TAKEN — this bundle is INCOMPLETE ***"
                        : "  taken: " + manifest.objectBytes().objects() + " objects, "
                                + manifest.objectBytes().bytes() + " bytes"));
        write("-- Identity provider (item 9): " + manifest.identityProviderDecision());
        if (!manifest.reservedPrefixes().isEmpty()) {
            write("-- API key prefixes permanently reserved on the platform: "
                    + String.join(", ", manifest.reservedPrefixes()));
        }
        write("--");
        write("-- WHAT DOES NOT TRAVEL, and what the tenant loses by it:");
        manifest.doesNotTravel().forEach(line -> write("--   " + line));
        if (!manifest.warnings().isEmpty()) {
            write("--");
            write("-- WARNINGS:");
            manifest.warnings().forEach(warning -> write("--   " + warning));
        }
        write("-- ".concat("-".repeat(72)));
    }

    /**
     * <strong>The one row the extracted deployment cannot serve a single request without, and the one
     * nothing else in this bundle produces.</strong> {@code TenantBundlePlan} judges
     * {@code platform.tenant_placement} {@link BundleDisposition#WRITTEN_FRESH} — correctly, a lifted
     * tenant has exactly one placement and it is its own — but "written fresh" named no writer, and the
     * gap is not survivable:
     *
     * <p>{@code TenantRoutes.readHome} returns {@code tenant_pool} when the registry holds no row for an
     * organization. That is <em>not</em> an error path; it is the ordinary answer for an unpromoted
     * tenant, and it is right on the platform. On the far side it is the failure ADR 0010 §1 calls the
     * worst this design can produce. The destination has run BOTH migration sequences, so its
     * {@code tenant_pool} exists and is empty; a bundle restored into a {@code t_<32hex>} schema with no
     * placement row therefore routes every statement to that empty pool. The tenant boots, authenticates,
     * and presents itself as an organization with no members, no tickets and no history — and the first
     * write lands in a schema its {@code org_id} disagrees with. Nothing logs, because nothing is wrong:
     * every layer is doing what it was told by a registry that was never told anything.
     *
     * <p>Since {@code 0822943} made {@code silo-per-org} the default placement policy, that is the shape
     * of EVERY extraction rather than an edge case, which is why this is a statement in the script and
     * not a step in the runbook.
     *
     * <p><b>{@code current_schema()} rather than the name spelled a second time, and that is the whole
     * design.</b> The preamble emits one editable {@code search_path} line because the destination
     * chooses where its single tenant lives — the same silo name, or {@code tenant_pool}, both correct.
     * A placement row naming a schema written out again here would be a second thing to edit and the
     * first thing to forget, and a placement row disagreeing with the {@code search_path} is precisely
     * the misroute above with an extra step. Derived from the session, the two cannot drift: whatever
     * the operator points the path at is what the registry records. ({@code current_schema()} is the
     * first schema in the path that exists, which the preamble guarantees is the tenant's, and it is the
     * same idiom {@code TenantExtractionService.restoreOrder} already reads for the same reason.)
     *
     * <p><b>{@code schema_version} is deliberately NULL and this is the documented safe value.</b> V57's
     * header states it outright — NULL means "not yet recorded", never "behind" — and
     * {@code TenantSchemaFloor} reads an unparseable version as unknown and SERVES. Writing the source's
     * version would be copying a fact about another installation's schema, which is the one thing
     * WRITTEN_FRESH exists to forbid; the destination's own migration runner fills it in on its next
     * pass, keyed by {@code schema_name}.
     *
     * <p>Inside the transaction, so a bundle that fails half way leaves no placement pointing at a
     * tenant that is not there.
     */
    private void writeThePlacementRow(UUID orgId) {
        write("-- The placement registry row. Without it TenantRoutes answers `tenant_pool` for this");
        write("-- organization and every statement is addressed to the empty schema the migration set");
        write("-- built, in silence (ADR 0010 §1, §6). schema_name is taken from the search_path set");
        write("-- above, so editing that one line moves this row with it.");
        write("insert into \"platform\".\"tenant_placement\""
                + " (\"org_id\", \"schema_name\", \"datasource_name\", \"state\", \"schema_version\","
                + " \"last_error\", \"updated_at\")");
        write("values ('" + orgId + "'::uuid, current_schema(), 'primary', 'ACTIVE', null, null, now())");
        write("on conflict (\"org_id\") do update set \"schema_name\" = excluded.\"schema_name\",");
        write("    \"state\" = 'ACTIVE', \"last_error\" = null, \"updated_at\" = now();");
        write("");
    }

    @Override
    public void close() {
        try {
            out.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("the extraction bundle could not be flushed", failure);
        }
    }

    private void write(String line) {
        try {
            out.write(line);
            out.write('\n');
        } catch (IOException failure) {
            throw new UncheckedIOException("the extraction bundle could not be written", failure);
        }
    }

    /** Postgres identifier quoting. Everything reaching it came from the catalogue, not from a caller. */
    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** See the class note: exactly the types this schema produces, and a refusal for anything else. */
    private static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case Boolean flag -> flag.toString();
            case UUID id -> "'" + id + "'::uuid";
            // Timestamp first: it extends java.util.Date, and a Date arm above this one would swallow it.
            case Timestamp at -> "'" + at.toInstant() + "'::timestamptz";
            case BigDecimal number -> number.toPlainString();
            case Number number -> number.toString();
            case String text -> "'" + text.replace("'", "''") + "'";
            default -> throw new IllegalStateException("the extraction bundle cannot render a "
                    + value.getClass().getName() + " as a SQL literal. Every column in `platform` and"
                    + " the tenant schema is uuid, varchar, text, bool, int4, int8, numeric or"
                    + " timestamptz — so a new type here means a new column type that nobody has"
                    + " decided how to carry. Decide, then teach this method; guessing would produce a"
                    + " script that restores something subtly different from what was extracted.");
        };
    }
}
