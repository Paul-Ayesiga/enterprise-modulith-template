package ug.co.smsone.shared.persistence;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.flywaydb.core.api.ClassProvider;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * One migration directory, read off the classpath <strong>once</strong> and handed to every
 * {@code Flyway.migrate()} that follows (ADR 0010 §4.2).
 *
 * <p><strong>Why this class exists at all, in one measurement.</strong> Per-schema Flyway overhead is
 * 50–150 ms <em>with zero pending migrations</em>, and almost none of it is the migration: a fresh
 * connection, four probe queries, and — the expensive part —
 * {@code FlywayExecutor.createResourceAndClassProviders} building a {@code new Scanner<>} on every
 * single {@code migrate()} call, which rescans the classpath location and re-reads every script so
 * {@code validateOnMigrate} (default true) can re-checksum it. Across a fleet that is the whole cost.
 * The escape hatch is written into Flyway's own source and is exact: supply <strong>both</strong>
 * {@code resourceProvider} and {@code javaMigrationClassProvider} and the branch that constructs the
 * Scanner is never taken — <em>"don't create the scanner at all in this case"</em> is the comment on
 * the line. Supply only one and Flyway builds the Scanner anyway to fill the other, so a half-measure
 * buys nothing. That is why this type is both interfaces at once.
 *
 * <p>The scripts are also held <strong>in memory</strong> after the first read. Checksumming still
 * happens per migrate (it must — validation is what catches an edited applied migration), but it
 * happens against a {@link StringReader} rather than against the jar. 33 tenant scripts are a few
 * hundred kilobytes; a fan-out across a fleet re-reads them once per schema otherwise.
 *
 * <p><strong>The {@code .sql.conf} rule is enforced here, not documented somewhere.</strong> ADR 0010
 * §4.2 forbids {@code executeInTransaction=false} in the tenant sequence, and the reason is that the
 * failure modes are not symmetric. With Postgres' transactional DDL and the default, a failed
 * migration rolls back whole and writes <em>no</em> history row — the schema sits at V(n−1),
 * internally consistent, and the next run retries it. Non-transactional, Flyway writes
 * {@code success = false} and that schema is wedged until someone runs {@code repair()}: one flaky
 * {@code CREATE INDEX CONCURRENTLY} becomes one manual repair per silo. Both halves are verified in
 * flyway-core 12.4.0's {@code DbMigrate.applyMigrations}, which logs "Changes successfully rolled
 * back" in the first case and calls {@code addAppliedMigration(..., false)} in the second.
 *
 * <p>Flyway reads that setting from exactly one place —
 * {@code SqlScriptMetadata.getMetadataResource} asks the resource provider for
 * {@code <script>.sql.conf} and nothing else. The header-comment form does not work on this Flyway at
 * all (AGENTS §4.6: the string {@code flyway:} appears nowhere in the parser), so the sidecar is the
 * only thing to check for and {@link #fromClasspath(String, boolean)} refuses to build at all when a
 * forbidden location holds one. The platform sequence keeps the escape hatch — its tables are the big
 * ones, and AGENTS §4.6 weighs that trade-off for them.
 */
public final class MigrationScripts implements ResourceProvider, ClassProvider<JavaMigration> {

    /**
     * There are no {@code JavaMigration}s in this repo and adding one would be a decision, not an
     * accident. Returning an empty list rather than leaving the provider unset is the point: an unset
     * provider is what makes Flyway build the Scanner.
     */
    private static final ClassProvider<JavaMigration> NO_JAVA_MIGRATIONS = List::of;

    private final String location;
    private final List<LoadableResource> resources;

    /**
     * Keyed the way {@code Scanner} keys it — lower-cased relative path — because
     * {@link #getResource(String)} is called with names Flyway assembles itself and the built-in
     * implementation is case-insensitive.
     */
    private final Map<String, LoadableResource> byRelativePath;

    private MigrationScripts(String location, List<LoadableResource> resources) {
        this.location = location;
        this.resources = List.copyOf(resources);
        Map<String, LoadableResource> index = new HashMap<>();
        for (LoadableResource resource : this.resources) {
            index.put(resource.getRelativePath().toLowerCase(Locale.ROOT), resource);
        }
        this.byRelativePath = Map.copyOf(index);
    }

    /** The platform sequence: script config sidecars are allowed, per AGENTS §4.6. */
    public static MigrationScripts fromClasspath(String location) {
        return fromClasspath(location, false);
    }

    /**
     * @param location a classpath directory without scheme or trailing slash, e.g.
     *     {@code db/migration/tenant}
     * @param forbidScriptConfig refuse to build if the directory holds any {@code *.sql.conf} — the
     *     tenant sequence's rule (ADR 0010 §4.2). Refusing at construction is deliberate: a provider
     *     that merely declined to serve the sidecar would silently run the migration transactionally
     *     while its author believed otherwise, and "silently did the opposite" is worse than either
     *     outcome.
     * @throws IllegalStateException if the location holds no scripts, or holds a forbidden sidecar
     */
    public static MigrationScripts fromClasspath(String location, boolean forbidScriptConfig) {
        var resolver = new PathMatchingResourcePatternResolver(MigrationScripts.class.getClassLoader());
        Resource[] found;
        try {
            // classpath*: so a location split across the classes dir and a jar is seen whole. One
            // read, here, for the life of the process.
            found = resolver.getResources("classpath*:" + location + "/*");
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read migration scripts from classpath:" + location, failure);
        }

        var scripts = new ArrayList<LoadableResource>();
        var sidecars = new TreeSet<String>();
        for (Resource resource : found) {
            String filename = resource.getFilename();
            if (filename == null || filename.isBlank()) {
                continue;
            }
            if (filename.toLowerCase(Locale.ROOT).endsWith(".sql.conf")) {
                sidecars.add(filename);
            }
            scripts.add(new PreloadedScript(location, filename, read(resource), diskPathOf(resource, location, filename)));
        }

        if (!sidecars.isEmpty() && forbidScriptConfig) {
            throw new IllegalStateException(
                    "executeInTransaction=false is forbidden in " + location + " (ADR 0010 §4.2) and these"
                            + " script-config sidecars are the only way to set it on flyway-core 12.4.0: "
                            + sidecars + ". A non-transactional tenant migration that fails writes"
                            + " success=false into that schema's flyway_schema_history and wedges it until"
                            + " someone runs the runner's repair mode — once per silo. The transactional"
                            + " default rolls back whole, writes no history row, and the next run retries"
                            + " it. Split the statement out, or promote it to the platform sequence where"
                            + " AGENTS §4.6 weighs the lock cost.");
        }
        if (scripts.isEmpty()) {
            throw new IllegalStateException(
                    "No migration scripts on classpath:" + location + " — a provider that resolves nothing"
                            + " makes Flyway report every schema as up to date, which is the one failure this"
                            + " runner must never produce silently.");
        }

        scripts.sort(Comparator.comparing(LoadableResource::getRelativePath));
        return new MigrationScripts(location, scripts);
    }

    /** The classpath directory this was read from — {@code db/migration/tenant} and friends. */
    public String location() {
        return location;
    }

    /** Filenames, sorted. For tests and for the manifest line that says what was applied from where. */
    public List<String> filenames() {
        return resources.stream().map(LoadableResource::getFilename).toList();
    }

    /** The empty {@code ClassProvider} half — see {@link #NO_JAVA_MIGRATIONS}. */
    @Override
    public Collection<Class<? extends JavaMigration>> getClasses() {
        return NO_JAVA_MIGRATIONS.getClasses();
    }

    @Override
    public LoadableResource getResource(String name) {
        return name == null ? null : byRelativePath.get(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        var matched = new ArrayList<LoadableResource>();
        for (LoadableResource resource : resources) {
            if (startsAndEndsWith(resource.getFilename(), prefix, suffixes)) {
                matched.add(resource);
            }
        }
        return matched;
    }

    /**
     * {@code org.flywaydb.core.internal.util.StringUtils.startsAndEndsWith}, reimplemented rather than
     * imported: it is internal API, and the two callers that matter pass suffix arrays this has to
     * agree with exactly — {@code SqlMigrationResolver} passes {@code .sql}, and
     * {@code ScriptMigrationResolver} passes a single empty string meaning "any". The length guard is
     * Flyway's and is load-bearing: it is what stops a file literally named {@code .sql} from
     * resolving as a migration.
     */
    private static boolean startsAndEndsWith(String filename, String prefix, String[] suffixes) {
        if (prefix != null && !prefix.isEmpty() && !filename.startsWith(prefix)) {
            return false;
        }
        String head = prefix == null ? "" : prefix;
        for (String suffix : suffixes) {
            if (filename.toUpperCase(Locale.ROOT).endsWith(suffix.toUpperCase(Locale.ROOT))
                    && filename.length() > (head + suffix).length()) {
                return true;
            }
        }
        return false;
    }

    private static String read(Resource resource) {
        try {
            // UTF-8 explicitly: Flyway's own default, and a platform-default read would make the
            // checksum of an applied migration depend on the JVM that ran it.
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read migration script " + resource.getDescription(), failure);
        }
    }

    /**
     * Flyway records this as a resolved migration's {@code physicalLocation} and prints it in failure
     * messages. Best effort on purpose: inside a fat jar there is no file, and a provider that threw
     * from a diagnostics accessor would turn a readable migration failure into an unreadable one.
     */
    private static String diskPathOf(Resource resource, String location, String filename) {
        try {
            return resource.getURL().toString();
        } catch (IOException ignored) {
            return location + "/" + filename;
        }
    }

    /**
     * One script, content and all, held for the life of the process. {@code relativePath} is the bare
     * filename because that is what a {@code classpath:<location>} {@code Location} produces, and it is
     * the key Flyway appends {@code .conf} to when it looks for script config.
     */
    private static final class PreloadedScript extends LoadableResource {

        private final String location;
        private final String filename;
        private final String content;
        private final String diskPath;

        private PreloadedScript(String location, String filename, String content, String diskPath) {
            this.location = location;
            this.filename = filename;
            this.content = content;
            this.diskPath = diskPath;
        }

        @Override
        public Reader read() {
            return new StringReader(content);
        }

        @Override
        public String getAbsolutePath() {
            return location + "/" + filename;
        }

        @Override
        public String getAbsolutePathOnDisk() {
            return diskPath;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public String getRelativePath() {
            return filename;
        }
    }
}
