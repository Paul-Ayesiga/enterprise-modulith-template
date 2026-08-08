package ug.co.smsone.scheduler.internal;

import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.ExtensibleLockProvider;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import ug.co.smsone.shared.persistence.SoftDeleteProperties;
import ug.co.smsone.shared.tenancy.TenantContext;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
// `defaultLockAtMostFor` is a backstop that nothing currently falls through to, and that is on purpose:
// every @SchedulerLock in the codebase states its own lease next to a paragraph deriving it, and
// ScheduledJobAxisTest fails the build on one that does not. A default lease is by definition a number
// nobody sized for the job holding it, and this is the one setting where an unsized number is a
// correctness bug rather than a tuning miss — a lease that expires under a running pass lets a second
// replica start a concurrent one. `lockAtLeastFor` is genuinely shared: 30s is a clock-skew floor
// between instances, not a statement about any job's duration.
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", defaultLockAtLeastFor = "PT30S")
// The soft-delete retention policy is declared with the mapping it belongs to (shared/persistence);
// the scheduler is only the module that acts on it. The event/inbox retention record is the
// scheduler's own — its jobs are the only consumers.
@EnableConfigurationProperties({SoftDeleteProperties.class, SchedulerRetentionProperties.class})
class SchedulingConfig {

    /**
     * The lock provider, wrapped so its own {@code shedlock} reads and writes declare a tenant axis
     * (ADR 0010 §3.4).
     *
     * <p><strong>This is the borrow nobody sees.</strong> {@code @SchedulerLock} is an around-advice:
     * ShedLock {@code INSERT}s or {@code UPDATE}s the {@code shedlock} row to acquire the lease
     * <em>before</em> the annotated method's first statement, and {@code DELETE}s/updates it again
     * after the method returns. Both happen on this {@link JdbcTemplate}, which is built on the
     * {@code @Primary} routing {@code DataSource} — so with no axis pinned they resolve
     * {@code search_path} to the empty {@code no_tenant} schema and every scheduled job in the
     * application dies on {@code relation "shedlock" does not exist} before its body runs. Pinning
     * inside the job methods cannot reach it; it is outside them by construction.
     *
     * <p>On the scheduler's own threads the {@code TaskDecorator} in {@code shared.config.AsyncConfig}
     * has already pinned PLATFORM and these calls nest harmlessly. This wrapper is what covers the
     * other callers: tests that invoke a {@code @SchedulerLock} method through its proxy, and any
     * future manual trigger — neither of which goes near a {@code TaskScheduler}.
     *
     * <p>It pins rather than degrading quietly, which means it <em>throws</em> if a transaction is
     * already open around the lock acquisition. That is the correct failure: an open transaction has
     * already bound a connection, so the lease and the job would run on whatever {@code search_path}
     * that borrow got. It is also why no {@code @Scheduled} method in this codebase carries
     * {@code @Transactional} — the two would order unpredictably (both advisors default to
     * {@code LOWEST_PRECEDENCE}), and the jobs that need a transaction open it with a
     * {@code TransactionTemplate} inside their pin instead.
     */
    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new PlatformAxisLockProvider(new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                // PLATFORM-TIER, NAMED EXPLICITLY (ADR 0010 §2 row 46). `shedlock` is infrastructure: it
                // is never copied and never per-tenant, V4 creates it only in db/migration/platform, and
                // an extracted deployment that inherited a future `lock_until` would run no jobs at all
                // while logging nothing (@SchedulerLock skips a same-name relock in silence).
                //
                // The qualifier is belt to the axis pin's braces below, and it is the belt that holds if
                // the braces are ever loosened: ShedLock's table name is a plain string interpolated into
                // every statement it builds, with no mapping and no validation, so an unqualified name
                // resolves through whatever search_path the borrowing thread carries. On a tenant axis
                // that is `tenant_pool`, where there is no `shedlock` table — every scheduled job in the
                // application would then die on `relation "shedlock" does not exist` in the ShedLock
                // advice, BEFORE its own body ran and therefore before any pin the job declares.
                //
                // Verified against real Postgres, not assumed: PostgresSqlServerTimeStatementsSource
                // writes `WHERE platform.shedlock.name = :name` (schema.table.column) in both the
                // ON CONFLICT DO UPDATE clause and the update statement, and all four of its statements
                // parse and run schema-qualified.
                .withTableName("platform.shedlock")
                // DB-server UTC time — instances need no clock agreement
                .usingDbTime()
                .build()));
    }

    /**
     * Declares the platform axis around every {@code shedlock} statement ShedLock issues on its own
     * behalf. {@link ExtensibleLockProvider} rather than plain {@link LockProvider} because
     * {@code JdbcTemplateLockProvider} is one, and a wrapper that dropped the marker would silently
     * disable {@code LockExtender} for callers that check the type.
     */
    private record PlatformAxisLockProvider(ExtensibleLockProvider delegate) implements ExtensibleLockProvider {

        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            Optional<SimpleLock> acquired = TenantContext.callAsPlatform(() -> delegate.lock(lockConfiguration));
            return acquired.<SimpleLock>map(PlatformAxisLock::new);
        }
    }

    /**
     * The other half: release and extension are statements against {@code shedlock} too, and they run
     * after the job's own pin has been restored (ShedLock unlocks in a {@code finally} outside the
     * method body), so they need the axis declared again here.
     */
    private record PlatformAxisLock(SimpleLock delegate) implements SimpleLock {

        @Override
        public void unlock() {
            TenantContext.runAsPlatform(delegate::unlock);
        }

        @Override
        public Optional<SimpleLock> extend(Duration lockAtMostFor, Duration lockAtLeastFor) {
            Optional<SimpleLock> extended =
                    TenantContext.callAsPlatform(() -> delegate.extend(lockAtMostFor, lockAtLeastFor));
            return extended.<SimpleLock>map(PlatformAxisLock::new);
        }
    }
}
