package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The scale contract, proven end to end on a 100k-row file: a job that dies mid-run RESUMES from
 * its last committed batch instead of restarting, replays only the uncommitted tail (at-least-once
 * delivery), and the idempotent handler turns that into exactly-once effect — counters included.
 * Also pins the two recovery paths (released retry, stale-lock reclaim of a dead claimant) and
 * cancellation at both edges.
 */
@Import(ExchangeTestSupport.class)
class ExchangeResumeTest extends AbstractIntegrationTest {

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
        // The queue is one shared table and drainOnce() claims strictly oldest-first — leftovers
        // from other tests would be claimed instead of this test's job.
        jdbc.update("delete from exchange_job");
    }

    @Test
    void aCrashedImportResumesFromItsCommittedOffsetWithExactlyOnceEffects() {
        UUID orgId = UUID.randomUUID();
        UUID jobId = submitCsv(orgId, 100_000);
        handler.failAtCall = 30_050; // mid-batch, 50 records past the last commit at 30 000

        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob afterCrash = store.find(jobId, orgId).orElseThrow();
        assertThat(afterCrash.status()).isEqualTo(ExchangeJob.PROCESSING);
        assertThat(afterCrash.nextOffset()).isEqualTo(30_000);
        assertThat(afterCrash.processed()).isEqualTo(30_000);

        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.COMPLETED);
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(done.processed()).isEqualTo(100_000);
        assertThat(done.failed()).isZero();
        // Exactly-once effect: every key applied once. At-least-once delivery: the 50 records of
        // the uncommitted batch were re-delivered (100 050 calls) and absorbed as SKIPPED.
        assertThat(handler.applied).hasSize(100_000);
        assertThat(handler.applyCalls.get()).isEqualTo(100_050);
    }

    @Test
    void aDeadClaimantsJobIsReclaimedAfterTheStaleLockAndFinishes() {
        UUID orgId = UUID.randomUUID();
        UUID jobId = submitCsv(orgId, 1_000);

        // "Instance A" claims and then dies without releasing: the row stays locked.
        assertThat(store.claimOne(Duration.ofMinutes(5))).isPresent();
        assertThat(worker.drainOnce()).as("a live claim is invisible to the queue").isZero();

        jdbc.update("update exchange_job set locked_at = locked_at - interval '10 minutes' where id = ?", jobId);
        assertThat(worker.drainOnce()).isEqualTo(1);

        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.COMPLETED);
        assertThat(done.attempts()).isEqualTo(2);
        assertThat(done.processed()).isEqualTo(1_000);
        assertThat(handler.applied).hasSize(1_000);
    }

    @Test
    void cancelBeforeStartNeverProcessesARecord() {
        UUID orgId = UUID.randomUUID();
        UUID jobId = submitCsv(orgId, 500);
        assertThat(store.requestCancel(jobId, orgId)).isTrue();

        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.CANCELLED);
        assertThat(done.processed()).isZero();
        assertThat(handler.applyCalls.get()).isZero();
    }

    @Test
    void cancelMidRunStopsAtTheNextBatchBoundaryKeepingCommittedWork() {
        UUID orgId = UUID.randomUUID();
        UUID jobId = submitCsv(orgId, 2_000);
        handler.onCall = call -> {
            if (call == 750) {
                store.requestCancel(jobId, orgId); // lands while batch 2 (records 501-1000) runs
            }
        };

        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.CANCELLED);
        assertThat(done.processed()).as("the batch in flight commits; nothing after it starts")
                .isEqualTo(1_000);
        assertThat(handler.applyCalls.get()).isEqualTo(1_000);
    }

    private UUID submitCsv(UUID orgId, int rows) {
        StringBuilder csv = new StringBuilder("key,value\n");
        for (int i = 1; i <= rows; i++) {
            csv.append("k").append(i).append(",v").append(i).append('\n');
        }
        String key = "exch/o/" + orgId + "/test/source.csv";
        storage.objects.put(key, csv.toString().getBytes(StandardCharsets.UTF_8));
        return store.submit(orgId, "requester-1", ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, "CSV", key);
    }
}
