package ug.co.smsone.shared.tenancy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * <strong>Puts the process-wide route resolver back on the suite's singleton database after a test
 * class that ran against a private one.</strong> Test-support, in this package because the seam it
 * heals is this package's: {@link TenantRoutes}' constructor installs itself into a static slot that
 * {@code TenantRoutingDataSource} resolves every tenant-axis borrow through.
 *
 * <h2>The seam, precisely</h2>
 *
 * <p>{@code TenantRoutes}' javadoc says "last one installed wins, and that is safe here because every
 * instance answers out of the same {@code platform.tenant_placement} in the same database" — which was
 * true for as long as every Spring context in the JVM sat on {@link
 * ug.co.smsone.testsupport.AbstractIntegrationTest#POSTGRES} (the own-container classes all borrowed
 * it: {@code SettingsModuleTest}, {@code NotificationDeliveryTest}, {@code WebhookEventTest}). ADR
 * 0011's outage tests are the first classes with a genuinely SEPARATE database, because severing the
 * shared one would fail the rest of the suite — and the moment such a context is built, its
 * {@code TenantRoutes} wins the static slot. Its own tests are correct (a class's tests run
 * contiguously, right after its context is built). What breaks is every LATER class that reuses a
 * context CACHED from before it: those contexts never reconstruct their beans, so tenant routes keep
 * being read from the private database — where no later test's placement rows exist — and, under
 * {@code silo-per-org}, every routed statement of every newly placed tenant lands in
 * {@code tenant_pool}: ADR 0010 §1's misroute, surfacing as "the row I just wrote is not there" in
 * whichever unrelated class runs next.
 *
 * <p>So a class that builds a context over its own database <strong>must</strong> call
 * {@link #reinstallReadingFrom} from {@code @AfterAll}. A fresh context built later heals the slot by
 * itself (its constructor reinstalls); this exists for the cached ones, which never do.
 */
public final class TenantRoutesTestInstalls {

    /** Kept for the JVM: cached contexts may route through it until the next context build. */
    private static HikariDataSource pool;

    private TenantRoutesTestInstalls() {
    }

    /**
     * Installs a fresh {@link TenantRoutes} whose registry reads come from {@code container} — call
     * with {@code AbstractIntegrationTest.POSTGRES} so cached contexts resolve routes against the
     * database their own beans and placement rows actually live in.
     */
    public static synchronized void reinstallReadingFrom(PostgreSQLContainer container) {
        if (pool == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(container.getJdbcUrl());
            config.setUsername(container.getUsername());
            config.setPassword(container.getPassword());
            // One connection, held never: a route read is a single indexed lookup once per tenant per
            // TTL, and this pool only serves until the next Spring context installs its own resolver.
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setPoolName("tenant-routes-reinstall");
            pool = new HikariDataSource(config);
        }
        // The constructor installs (and clears the shared memo map). The TTL mirrors
        // application-test.yaml's app.tenancy.route-ttl — the value every cached context was built
        // with, and the number the promoter's post-flip freeze hold reads back through routeTtl(); a
        // production-sized value here would stretch every later promotion test's sleep to match.
        new TenantRoutes(pool, Duration.ofSeconds(2));
    }
}
