package ug.co.smsone.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Phase 3 gate: "a scheduled job fires once across 2 app instances" — two independent
 * JdbcTemplateLockProviders (as two instances would have) race for the same named lock on the
 * same real Postgres; exactly one may win.
 *
 * <p><strong>The providers here are deliberately RAW.</strong> The application's own
 * {@code LockProvider} bean wraps this one so that every {@code shedlock} statement declares the
 * platform axis (see {@code SchedulingConfig}); two instances racing is exactly what that wrapper
 * must not be allowed to hide, so this test builds the unwrapped provider and declares the axis at
 * the call site instead — which is what the wrapper, or the scheduler's {@code TaskDecorator}, does
 * for it in production (ADR 0010 §3.4).
 */
class SchedulerLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LockProvider newInstanceLockProvider() {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Test
    void onlyOneOfTwoInstancesAcquiresTheLock() throws Exception {
        LockProvider instanceA = newInstanceLockProvider();
        LockProvider instanceB = newInstanceLockProvider();
        LockConfiguration config = new LockConfiguration(
                Instant.now(), "gate-lock", Duration.ofMinutes(10), Duration.ZERO);

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Each racer runs on a pooled platform thread with no axis of its own — the same state the
            // scheduler hands its tasks in. Without the pin the INSERT lands on the empty no_tenant
            // schema and both instances fail with `relation "shedlock" does not exist` before the race
            // this test is about ever happens.
            Future<Optional<SimpleLock>> first = pool.submit(() -> {
                start.await();
                return TenantContext.callAsPlatform(() -> instanceA.lock(config));
            });
            Future<Optional<SimpleLock>> second = pool.submit(() -> {
                start.await();
                return TenantContext.callAsPlatform(() -> instanceB.lock(config));
            });
            start.countDown();

            List<Optional<SimpleLock>> results = List.of(first.get(), second.get());
            long acquired = results.stream().filter(Optional::isPresent).count();
            assertThat(acquired).as("exactly one instance runs the job").isEqualTo(1);

            results.stream().flatMap(Optional::stream).forEach(SimpleLock::unlock);
        }
    }

    @Test
    void lockRowIsWrittenToTheShedlockTable() {
        LockProvider provider = newInstanceLockProvider();
        Optional<SimpleLock> lock = provider.lock(new LockConfiguration(
                Instant.now(), "row-probe", Duration.ofMinutes(1), Duration.ZERO));
        assertThat(lock).isPresent();

        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from shedlock where name = 'row-probe'", Integer.class);
        assertThat(rows).isEqualTo(1);
        lock.get().unlock();
    }
}
