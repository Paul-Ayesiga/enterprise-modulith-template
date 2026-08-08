package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The half of ADR 0010 §4.2's runner design that needs no database: the shared providers that keep
 * Flyway's Scanner out of the fan-out, and the rule that {@code executeInTransaction=false} cannot
 * reach the tenant sequence.
 *
 * <p>No container, deliberately — these fail on the commit that breaks them rather than on the run.
 */
class MigrationScriptsTest {

    private static final Path TENANT_MIGRATIONS =
            Path.of("src", "main", "resources", TenantMigrationRunner.TENANT_LOCATION);

    /**
     * The directive as somebody would actually write it: its own comment line, which is what every
     * copy-paste from Flyway's documentation produces.
     *
     * <p>Line-anchored rather than a substring search, and that is not fussiness. Three tenant
     * migrations — V50, V52 and V53 — <em>discuss</em> this exact string in their headers, because
     * discovering that it is inert on flyway-core 12.4.0 is part of the rationale they were written to
     * record. A substring check would fail the build over the documentation of the rule it is enforcing,
     * and the fix somebody would reach for is deleting the explanation.
     */
    private static final Pattern DIRECTIVE_COMMENT =
            Pattern.compile("(?im)^\\s*--\\s*flyway\\s*:\\s*executeInTransaction");

    /**
     * The forbid, at its strongest: the tenant sequence is loaded exactly the way the runner loads it,
     * with the refusal switched on. A sidecar added to that directory fails here before it can wedge a
     * schema in production.
     */
    @Test
    void theTenantSequenceLoadsWithScriptConfigForbidden() {
        MigrationScripts tenant = MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);

        assertThat(tenant.filenames())
                .describedAs("the tenant sequence the runner would apply")
                .isNotEmpty()
                .allMatch(name -> name.endsWith(".sql"))
                .contains("V1__baseline.sql");
    }

    /**
     * And the refusal is real, proved against a fixture that carries the sidecar. Without this the test
     * above would pass just as happily if the check had been deleted — the directory has no sidecar
     * either way.
     */
    @Test
    void aScriptConfigSidecarInAForbiddenLocationRefusesToLoad() {
        assertThatThrownBy(() -> MigrationScripts.fromClasspath(FixturePaths.NON_TRANSACTIONAL, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("executeInTransaction=false is forbidden")
                .hasMessageContaining("V9101__fixture_concurrently.sql.conf");
    }

    /**
     * {@code TenantMigrationRunnerTest} models two releases so the failing migration has a previous
     * version to leave its schema at, and release N+1 re-resolves and re-checksums the migration release
     * N already applied. A drift between the two copies of {@code V9001} would fail both schemas on a
     * checksum mismatch — the test would still be red, just about something else entirely.
     */
    @Test
    void theSharedFixtureMigrationIsIdenticalInBothReleases() {
        String releaseN = read(FixturePaths.onDisk(FixturePaths.RELEASE_N).resolve(FixturePaths.SHARED_SCRIPT));
        String releaseNPlusOne =
                read(FixturePaths.onDisk(FixturePaths.RELEASE_N_PLUS_ONE).resolve(FixturePaths.SHARED_SCRIPT));

        assertThat(releaseNPlusOne)
                .describedAs("Flyway validates an applied migration's checksum against the one it resolves"
                        + " now, so these two files are one file with two homes")
                .isEqualTo(releaseN);
    }

    /**
     * The same fixture loads fine when the location is not the tenant one. The platform sequence keeps
     * the escape hatch on purpose — AGENTS §4.6 weighs the lock cost for the big shared tables, and
     * a platform migration that fails non-transactionally wedges ONE schema rather than one per silo.
     */
    @Test
    void theSameSidecarIsAllowedWhereTheRuleDoesNotApply() {
        MigrationScripts allowed = MigrationScripts.fromClasspath(FixturePaths.NON_TRANSACTIONAL);

        assertThat(allowed.filenames()).contains("V9101__fixture_concurrently.sql");
    }

    /**
     * The form the sidecar check structurally cannot see. A {@code -- flyway:executeInTransaction=false}
     * header is <em>inert</em> on flyway-core 12.4.0 — the string {@code flyway:} appears nowhere in its
     * parser and {@code SqlScriptMetadata} reads a sidecar and nothing else — so it does not wedge a
     * schema; it does something arguably worse, which is read as a working opt-out to every human who
     * sees it while every {@code CONCURRENTLY} beneath it aborts with "cannot run inside a transaction
     * block". Forbidden here for both reasons at once.
     */
    @Test
    void noTenantMigrationCarriesTheInertHeaderCommentForm() {
        List<String> offenders = tenantMigrationSources()
                .filter(source -> DIRECTIVE_COMMENT.matcher(source.text()).find())
                .map(Source::name)
                .toList();

        assertThat(offenders)
                .describedAs("`-- flyway:executeInTransaction=...` in a tenant migration: inert on"
                        + " flyway-core 12.4.0 (AGENTS §4.6) AND forbidden by ADR 0010 §4.2 even in the"
                        + " sidecar form that would work")
                .isEmpty();
    }

    /**
     * Both provider halves have to be live. Flyway only skips building the Scanner when
     * {@code getResourceProvider() != null && getJavaMigrationClassProvider() != null} — supply one and
     * it constructs one anyway to fill the other, and the 50–150 ms per schema comes straight back.
     */
    @Test
    void oneObjectAnswersBothOfFlywaysProviderQuestions() {
        MigrationScripts tenant = MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);

        assertThat(tenant.getClasses())
                .describedAs("there are no JavaMigrations here; an EMPTY answer is what stops the scan,"
                        + " an absent provider is what starts it")
                .isEmpty();
        assertThat(tenant.getResources("V", new String[] {".sql"}))
                .describedAs("what SqlMigrationResolver asks for")
                .hasSize(tenant.filenames().size());
        assertThat(tenant.getResources("R", new String[] {".sql"}))
                .describedAs("repeatable migrations — none, and the empty answer must not be the whole set")
                .isEmpty();
    }

    /**
     * {@code getResource} is the single call {@code SqlScriptMetadata.getMetadataResource} makes, with
     * {@code <script>.sql.conf}. Answering null there is the structural half of the forbid: even if the
     * construction-time refusal were somehow bypassed, the provider holds no sidecar to hand back.
     */
    @Test
    void theProviderResolvesAScriptByItsRelativePathAndNoSidecarBesideIt() {
        MigrationScripts tenant = MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);
        String anyScript = tenant.filenames().getFirst();

        assertThat(tenant.getResource(anyScript)).isNotNull();
        // Case-insensitively, the way Scanner keys its own map — Flyway assembles these names itself.
        assertThat(tenant.getResource(anyScript.toUpperCase())).isNotNull();
        assertThat(tenant.getResource(anyScript + ".conf"))
                .describedAs("the only name Flyway would read executeInTransaction from")
                .isNull();
    }

    /** Reading a resource twice must give the same content — the scripts are held, not consumed. */
    @Test
    void aScriptCanBeReadOnceForEverySchemaInTheFanOut() {
        MigrationScripts tenant = MigrationScripts.fromClasspath(TenantMigrationRunner.TENANT_LOCATION, true);
        var resource = tenant.getResource(tenant.filenames().getFirst());

        String first = readFully(resource.read());
        String second = readFully(resource.read());

        assertThat(first).isNotEmpty().isEqualTo(second);
    }

    private record Source(String name, String text) {}

    private static Stream<Source> tenantMigrationSources() {
        try (var files = Files.list(TENANT_MIGRATIONS)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> new Source(path.getFileName().toString(), read(path)))
                    .toList()
                    .stream();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + TENANT_MIGRATIONS, failure);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + path, failure);
        }
    }

    private static String readFully(java.io.Reader reader) {
        try (reader) {
            var text = new StringBuilder();
            var buffer = new char[4096];
            for (int read = reader.read(buffer); read >= 0; read = reader.read(buffer)) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read a preloaded migration script", failure);
        }
    }
}
