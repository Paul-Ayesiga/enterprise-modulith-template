package ug.co.smsone.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.TestPropertySource;
import ug.co.smsone.settings.SettingChanged;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * Modulith's republish-on-restart replayed the WHOLE incomplete set inside context refresh. The job
 * that replaced it caps a pass, which is only safe if the publications it did not reach are still
 * retried — a cap that quietly drops the tail is a worse bug than the slow boot it fixes. This is
 * that guarantee, pinned end to end: the cap holds, and the pass after it reaches past what the cap
 * cut off rather than replaying the same head forever.
 */
@TestPropertySource(properties = "app.events.resubmission.max-per-run=2")
@Import(OutboxResubmissionJobIntegrationTest.FailingProbe.class)
class OutboxResubmissionJobIntegrationTest extends AbstractIntegrationTest {

    /**
     * A listener that never completes, so the publications pointed at it stay incomplete across
     * passes — which is exactly the head-of-line case the backoff in {@code Window} exists for.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailingProbe {

        static final String LISTENER_ID = "outbox-resubmission-probe";
        static final AtomicInteger invocations = new AtomicInteger();

        // Explicit id: the registry addresses listeners by it, and the default is a method signature
        // this test would otherwise have to guess at to find its own rows.
        @ApplicationModuleListener(id = LISTENER_ID)
        void on(SettingChanged event) {
            invocations.incrementAndGet();
            throw new IllegalStateException("probe never completes");
        }
    }

    @Autowired
    private OutboxResubmissionJob job;

    @Autowired
    private SettingService settings;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aPassStopsAtItsCapAndTheNextOneReachesTheTail() {
        // One registry is shared by every cached context in the run; incomplete rows left by other
        // tests would be older than these and would spend the cap before it ever reached them.
        jdbc.update("delete from event_publication");
        // Publish once through the real flow purely to learn how this probe is addressed: the
        // listener id and the serialized payload have to be the registry's own, not a guess.
        // Fresh key: a settings write that changes nothing publishes nothing, and this test needs the
        // publication, not the value.
        settings.put("outbox.resubmission.probe." + UUID.randomUUID(), "x", null);
        // Wait for the delivery too, not just the row: the listener is async, and a first invocation
        // still in flight would land after the counter reset below and be miscounted as a retry.
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> !probeRows().isEmpty() && FailingProbe.invocations.get() >= 1);
        Map<String, Object> probe = probeRows().get(0);
        jdbc.update("delete from event_publication");

        Instant now = Instant.now();
        UUID oldest = insertIncomplete(probe, now.minus(Duration.ofMinutes(3)));
        UUID middle = insertIncomplete(probe, now.minus(Duration.ofMinutes(2)));
        UUID newest = insertIncomplete(probe, now.minus(Duration.ofMinutes(1)));
        FailingProbe.invocations.set(0);

        assertThat(job.resubmitOnce("test"))
                .as("max-per-run=2: a pass takes a bounded slice, never the whole backlog")
                .isEqualTo(2);
        Awaitility.await().atMost(Duration.ofSeconds(10))
                .until(() -> FailingProbe.invocations.get() >= 2);
        assertThat(resubmitted(oldest)).isTrue();
        assertThat(resubmitted(middle)).isTrue();
        assertThat(resubmitted(newest)).as("oldest first — the cap cuts the tail, not the head").isFalse();

        assertThat(job.resubmitOnce("test"))
                .as("the tail is not dropped: the two just taken are inside their backoff and cost no "
                        + "slot, so this pass reaches the one behind them")
                .isEqualTo(1);
        assertThat(resubmitted(newest)).isTrue();
    }

    /**
     * Pinned because one of its two callers polls: Awaitility evaluates the condition on ITS OWN
     * thread, which no filter and no harness ever pinned, so the borrow routes to the empty
     * {@code no_tenant} schema (ADR 0010 §3.4) — and the wait then times out on a publication that is
     * really there, which is the least useful failure this test could produce.
     */
    private List<Map<String, Object>> probeRows() {
        return TenantContext.callAsPlatform(() -> jdbc.queryForList(
                "select listener_id, event_type, serialized_event from event_publication "
                        + "where listener_id = ?", FailingProbe.LISTENER_ID));
    }

    /** A publication the registry would consider stranded: no completion, never yet resubmitted. */
    private UUID insertIncomplete(Map<String, Object> probe, Instant publishedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into event_publication (id, listener_id, event_type, serialized_event, "
                        + "publication_date, completion_date, status, completion_attempts, "
                        + "last_resubmission_date) values (?, ?, ?, ?, ?, null, 'PUBLISHED', 1, ?)",
                id, probe.get("listener_id"), probe.get("event_type"), probe.get("serialized_event"),
                Timestamp.from(publishedAt), Timestamp.from(publishedAt));
        return id;
    }

    /**
     * The registry stamps {@code last_resubmission_date} equal to {@code publication_date} on insert,
     * so "was resubmitted" is the stamp having moved past it — not the column being set.
     */
    private boolean resubmitted(UUID id) {
        Boolean moved = jdbc.queryForObject(
                "select last_resubmission_date > publication_date from event_publication where id = ?",
                Boolean.class, id);
        return Boolean.TRUE.equals(moved);
    }
}
