package ug.co.smsone.shared.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Manifest;
import ug.co.smsone.shared.persistence.TenantMigrationRunner.Mode;

/**
 * The entry point {@code deploy/helm/smsone/templates/tenant-migration-job.yaml} runs: a {@code main}
 * that migrates the fleet and exits with the manifest (ADR 0010 §7 Phase 4).
 *
 * <h2>A {@code main}, not a Spring context</h2>
 *
 * <p>The obvious implementation — boot the application with
 * {@code spring.main.web-application-type=none} and a {@code CommandLineRunner} — would drag in the
 * whole context to do one job: an OIDC issuer that has to be reachable, Valkey, SeaweedFS, Kill Bill,
 * fourteen scheduled jobs and three queue workers, all of which would have to be switched off by a
 * profile that then has to be kept correct forever. A migration job that cannot run because the identity
 * provider is down is a migration job that blocks the rollout for a reason unrelated to migrations. This
 * class opens a JDBC pool, runs {@link TenantMigrationRunner}, prints, and exits.
 *
 * <h2>How it is launched from the application image</h2>
 *
 * <p>The image's entry point runs the Boot jar; this class is a second {@code main} inside the same jar,
 * reached through Boot's own {@code PropertiesLauncher}:
 *
 * <pre>{@code
 * java -cp /app.jar \
 *      -Dloader.main=ug.co.smsone.shared.persistence.TenantMigrationJob \
 *      org.springframework.boot.loader.launch.PropertiesLauncher --mode=migrate
 * }</pre>
 *
 * <p>Same image, same jar, same classpath — which is the property that matters: the migration scripts
 * the Job applies are byte-for-byte the ones the release it precedes will read.
 *
 * <h2>Configuration</h2>
 *
 * <p>Connection settings come from the same environment variables {@code application.yaml} reads, so the
 * Job's container can reuse the deployment's env block and Secret verbatim: {@code POSTGRES_HOST},
 * {@code POSTGRES_PORT}, {@code POSTGRES_DB}, {@code POSTGRES_USER}, {@code POSTGRES_PASSWORD}. Flags
 * override them for a local run.
 *
 * <ul>
 *   <li>{@code --mode=migrate|repair|info} (or {@code TENANT_MIGRATION_MODE}) — see {@link Mode}.</li>
 *   <li>{@code --workers=N} (or {@code TENANT_MIGRATION_WORKERS}) — 1..16, default
 *       {@link TenantMigrationRunner#DEFAULT_WORKERS}.</li>
 *   <li>{@code --schemas=a,b} — visit exactly these and skip both discovery and the platform pass. The
 *       shape of a targeted repair: one wedged silo, without touching the fleet.</li>
 *   <li>{@code --url=}, {@code --user=}, {@code --password=} — for a laptop run against the Compose
 *       stack.</li>
 * </ul>
 *
 * <h2>Exit codes</h2>
 *
 * <p>0 clean, 1 one or more tenants are behind, 2 nothing was attempted. The Job's
 * {@code backoffLimit} turns a 1 into a retry, which is safe because every mode here is idempotent; a 2
 * means the platform sequence or the database itself is the problem and retrying will not help.
 */
public final class TenantMigrationJob {

    private static final Logger log = LoggerFactory.getLogger(TenantMigrationJob.class);

    private static final int FATAL = 2;

    private TenantMigrationJob() {}

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(Options.from(args, System.getenv()));
        } catch (RuntimeException failure) {
            // Nothing was attempted: bad arguments, an unreachable database, a classpath with no
            // migrations on it. Distinct from a tenant failure and worth its own code — the runbook
            // response is different.
            log.error("tenant migration could not start: {}", failure.getMessage(), failure);
            exit = FATAL;
        }
        // Explicit, and the reason is the pool: a non-daemon Hikari housekeeper would otherwise keep the
        // JVM alive after a perfectly finished job and the Kubernetes Job would never complete.
        System.exit(exit);
    }

    /**
     * Everything {@link #main(String[])} does except exit, so it can be driven from a test. Returns the
     * exit code and logs the manifest.
     */
    static int run(Options options) {
        log.info("tenant migration starting mode={} workers={} url={} schemas={}",
                options.mode(), options.workers(), options.url(),
                options.schemas().isEmpty() ? "<discovered>" : options.schemas());

        try (HikariDataSource pool = pool(options)) {
            TenantMigrationRunner runner = TenantMigrationRunner.fromClasspath(pool, options.workers());
            Manifest manifest = options.schemas().isEmpty()
                    ? runner.run(options.mode())
                    : runner.fanOut(options.mode(), options.schemas());
            // The manifest is the artifact. A Kubernetes Job's log is the only thing an operator has after
            // the pod is gone, so every line is greppable and stable rather than pretty.
            manifest.report().forEach(line -> log.info("MANIFEST {}", line));
            manifest.failures().forEach(failure ->
                    log.error("MANIFEST FAILED schema={} error={}", failure.schema(), failure.error()));
            return manifest.exitCode();
        }
    }

    /**
     * A pool of exactly the size the fan-out needs. Sized from
     * {@link TenantMigrationRunner#connectionsRequired(int)} rather than from a value written here, so
     * the two cannot drift into the deadlock that shape of mistake produces.
     *
     * <p>No {@code schema} is set on it, deliberately: {@code TenantMigrationRunner} pins the baseline
     * {@code search_path} on every borrow itself, and a pool-level default would be a second, quieter
     * answer to the same question.
     */
    private static HikariDataSource pool(Options options) {
        var config = new HikariConfig();
        config.setPoolName("tenant-migration");
        config.setJdbcUrl(options.url());
        config.setUsername(options.user());
        config.setPassword(options.password());
        config.setMaximumPoolSize(TenantMigrationRunner.connectionsRequired(options.workers()));
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30_000);
        // A tenant migration can legitimately hold a connection for minutes on a large silo; the default
        // leak-detection and idle timeouts are not the ones a batch job wants.
        config.setIdleTimeout(0);
        config.setMaxLifetime(0);
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    /**
     * Parsed arguments. Flags win over environment variables so a laptop run can point at the Compose
     * stack without unsetting anything.
     */
    record Options(String url, String user, String password, Mode mode, int workers, List<String> schemas) {

        static Options from(String[] args, Map<String, String> environment) {
            Map<String, String> flags = flags(args);
            String url = flags.getOrDefault("url", "jdbc:postgresql://%s:%s/%s".formatted(
                    environment.getOrDefault("POSTGRES_HOST", "localhost"),
                    environment.getOrDefault("POSTGRES_PORT", "5432"),
                    environment.getOrDefault("POSTGRES_DB", "modulith")));
            String user = flags.getOrDefault("user", environment.getOrDefault("POSTGRES_USER", "modulith"));
            // Never logged, never echoed back in an error. The dev default matches the Compose stack, the
            // same way application.yaml's does.
            String password =
                    flags.getOrDefault("password", environment.getOrDefault("POSTGRES_PASSWORD", "modulith"));

            String requested = flags.getOrDefault("mode", environment.getOrDefault("TENANT_MIGRATION_MODE", "migrate"));
            Mode mode = mode(requested);
            int workers = workers(flags.getOrDefault("workers",
                    environment.getOrDefault("TENANT_MIGRATION_WORKERS", String.valueOf(
                            TenantMigrationRunner.DEFAULT_WORKERS))));

            var schemas = new ArrayList<String>();
            String named = flags.get("schemas");
            if (named != null && !named.isBlank()) {
                Arrays.stream(named.split(",")).map(String::trim).filter(name -> !name.isEmpty()).forEach(schemas::add);
            }
            return new Options(url, user, password, mode, workers, List.copyOf(schemas));
        }

        private static Map<String, String> flags(String[] args) {
            var parsed = new LinkedHashMap<String, String>();
            for (String arg : args) {
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException(
                            "Unrecognised argument '" + arg + "'. Expected --key=value; see TenantMigrationJob.");
                }
                int split = arg.indexOf('=');
                if (split < 0) {
                    throw new IllegalArgumentException(
                            "Argument '" + arg + "' has no value. Expected --key=value; see TenantMigrationJob.");
                }
                parsed.put(arg.substring(2, split).toLowerCase(Locale.ROOT), arg.substring(split + 1));
            }
            return parsed;
        }

        private static Mode mode(String requested) {
            try {
                return Mode.valueOf(requested.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException(
                        "Unknown mode '" + requested + "'. One of " + Arrays.toString(Mode.values()) + ".", unknown);
            }
        }

        private static int workers(String requested) {
            try {
                return Integer.parseInt(requested.trim());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("workers must be a number, was '" + requested + "'", notANumber);
            }
        }
    }
}
