package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.TenantContext;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The {@link ug.co.smsone.exchange.JobCompleted} flow end to end: a job reaching terminal
 * publishes through the registry, and the notification consumer tells the REQUESTER in-app —
 * awaited, because {@code @ApplicationModuleListener} delivery is async after commit.
 */
@Import(ExchangeTestSupport.class)
class ExchangeJobCompletedFlowTest extends AbstractIntegrationTest {

    @Autowired
    private ExchangeWorker worker;

    @Autowired
    private ExchangeJobStore store;

    @Autowired
    private ExchangeTestSupport.InMemoryFileStorage storage;

    @Autowired
    private ExchangeTestSupport.CountingExchangeHandler handler;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        handler.reset();
        // Both homes AND the signals that index them — see ExchangeTestSupport.clearQueue.
        ExchangeTestSupport.clearQueue(jdbc);
    }

    @Test
    void aTerminalJobNotifiesItsRequesterInApp() {
        UUID orgId = UUID.randomUUID();
        // The requester is a person.id now, and JobCompleted carries it as requesterPersonId.
        UUID requester = UUID.randomUUID();
        String key = "exch/o/" + orgId + "/test/source.csv";
        storage.objects.put(key, "key,value\nk1,v1\nk2,v2\n".getBytes(StandardCharsets.UTF_8));
        UUID jobId = store.submit(orgId, requester, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "CSV", key);

        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(store.find(jobId, orgId).orElseThrow().status()).isEqualTo(ExchangeJob.COMPLETED);

        // The delivery worker is off in tests; the durable QUEUE row is the consumer's observable
        // effect — an IN_APP delivery addressed to the requester, carrying the job id.
        // notification_delivery.recipient is text across all five channels (an address for four of
        // them), so the in-app address is the person id RENDERED — binding the raw UUID would ask
        // Postgres for a text = uuid operator that does not exist.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(queuedInAppFor(requester, jobId))
                        .as("the requester's completion notification is queued").isEqualTo(1));
    }

    /**
     * Pinned because its only caller polls: Awaitility runs the condition on ITS OWN thread, which
     * carries no tenant axis, so the count would fail with {@code relation "notification_delivery"
     * does not exist} rather than answering (ADR 0010 §3.4). The consumer under test is asynchronous,
     * so there is no way to assert on it except from a thread the harness never pinned.
     */
    private Integer queuedInAppFor(UUID requester, UUID jobId) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject(
                "select count(*) from notification_delivery "
                        + "where channel = 'IN_APP' and recipient = ? and body like ?",
                Integer.class, requester.toString(), "%" + jobId + "%"));
    }
}
