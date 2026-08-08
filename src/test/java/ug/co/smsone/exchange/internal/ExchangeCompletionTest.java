package ug.co.smsone.exchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.shared.tenancy.SplitTables;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The guideline-completion contract: every shipped format round-trips through the SAME reader/
 * writer seam (XLSX and XML no differently than CSV), a zipped source unwraps, a malformed file is
 * a DATA failure with a curated message (never "system error"), exhausted retries end in FAILED,
 * and the fence's LOST_CLAIM answer — the concurrency story's core — actually fires.
 */
@Import(ExchangeTestSupport.class)
class ExchangeCompletionTest extends AbstractIntegrationTest {
    /** exchange_job.requester_person_id is a uuid soft ref — any person id seeds a requester. */
    private static final UUID REQUESTER = UUID.randomUUID();


    @Autowired
    private ExchangeWorker worker;

    @Autowired
    private ExchangeJobStore store;

    @Autowired
    private ExchangeTestSupport.InMemoryFileStorage storage;

    @Autowired
    private ExchangeTestSupport.CountingExchangeHandler handler;

    @Autowired
    private XlsxCodec xlsxCodec;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        handler.reset();
        for (String home : ug.co.smsone.shared.tenancy.SplitTables.homes()) {
            // exchange_job is a split table: leftovers in EITHER home would be claimed ahead of this
            // test's job, which claims strictly oldest-first (ADR 0010 §2 row 10).
            jdbc.update("delete from " + home + ".exchange_job");
        }
    }

    @Test
    void xlsxImportsAndExportsThroughTheSameSeam() throws IOException {
        UUID orgId = UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FormatCodec.RecordSink sink = xlsxCodec.writer(out, handler.header())) {
            sink.write(Map.of("key", "k1", "value", "v1"));
            sink.write(Map.of("key", "k2", "value", "v2"));
        }
        String key = "exch/o/" + orgId + "/test/source.xlsx";
        storage.objects.put(key, out.toByteArray());
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "XLSX", key);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.COMPLETED);
        assertThat(done.processed()).isEqualTo(2);
        assertThat(handler.applied).containsExactlyInAnyOrder("k1", "k2");

        UUID exportId = store.submit(orgId, REQUESTER, ExchangeJob.EXPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "XLSX", null);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob export = store.find(exportId, orgId).orElseThrow();
        assertThat(export.status()).isEqualTo(ExchangeJob.COMPLETED);
        try (Workbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(storage.objects.get(export.resultKey())))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            assertThat(headerRow.getCell(0).getStringCellValue()).isEqualTo("key");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("k1");
            assertThat(sheet.getLastRowNum()).isEqualTo(3); // header + 3 exported records
        }
    }

    @Test
    void xmlRoundTripsAndValidatesItsShape() {
        UUID orgId = UUID.randomUUID();
        String xml = """
                <?xml version="1.0"?>
                <records>
                  <record><key>k1</key><value>v1</value></record>
                  <record><key>k2</key><value>bad</value></record>
                </records>""";
        String key = "exch/o/" + orgId + "/test/source.xml";
        storage.objects.put(key, xml.getBytes(StandardCharsets.UTF_8));
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "XML", key);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.COMPLETED_WITH_ERRORS);
        assertThat(done.processed()).isEqualTo(1);
        assertThat(done.failed()).isEqualTo(1);

        // Wrong root: a whole-file shape problem with a curated message.
        String badKey = "exch/o/" + orgId + "/test/bad.xml";
        storage.objects.put(badKey, "<rows><r/></rows>".getBytes(StandardCharsets.UTF_8));
        UUID badJob = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "XML", badKey);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob failed = store.find(badJob, orgId).orElseThrow();
        assertThat(failed.status()).isEqualTo(ExchangeJob.FAILED);
        assertThat(failed.lastError()).contains("root element");
    }

    @Test
    void aZippedCsvSourceUnwrapsToItsFirstEntry() throws IOException {
        UUID orgId = UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("data.csv"));
            zip.write("key,value\nk1,v1\nk2,v2\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        String key = "exch/o/" + orgId + "/test/source.zip";
        storage.objects.put(key, out.toByteArray());
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "CSV", key);
        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(store.find(jobId, orgId).orElseThrow().processed()).isEqualTo(2);
    }

    @Test
    void aMalformedCsvMidFileFailsAsDataNotInfrastructure() {
        UUID orgId = UUID.randomUUID();
        String key = "exch/o/" + orgId + "/test/broken.csv";
        storage.objects.put(key,
                "key,value\nk1,v1\n\"unclosed,v2\n".getBytes(StandardCharsets.UTF_8));
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "CSV", key);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.FAILED);
        assertThat(done.attempts()).as("a malformed file never burns retries").isEqualTo(1);
        assertThat(done.lastError()).contains("malformed");
    }

    @Test
    void exhaustedInfrastructureRetriesEndInFailedWithACuratedMessage() {
        UUID orgId = UUID.randomUUID();
        String key = "exch/o/" + orgId + "/test/source.csv";
        storage.objects.put(key, "key,value\nk1,v1\n".getBytes(StandardCharsets.UTF_8));
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "CSV", key);
        handler.onCall = call -> {
            throw new IllegalStateException("provider down");
        };
        // Test profile: retry-base-backoff PT0S, max-attempts 3 → three drains reach FAILED.
        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob done = store.find(jobId, orgId).orElseThrow();
        assertThat(done.status()).isEqualTo(ExchangeJob.FAILED);
        assertThat(done.attempts()).isEqualTo(3);
        assertThat(done.lastError()).contains("Quote job id").doesNotContain("provider down");
    }

    @Test
    void aLostClaimIsAnsweredAtTheNextProgressCommit() {
        UUID orgId = UUID.randomUUID();
        String key = "exch/o/" + orgId + "/test/source.csv";
        StringBuilder csv = new StringBuilder("key,value\n");
        for (int i = 1; i <= 600; i++) {
            csv.append("k").append(i).append(",v").append(i).append('\n');
        }
        storage.objects.put(key, csv.toString().getBytes(StandardCharsets.UTF_8));
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "CSV", key);

        // "Instance A" claims and stalls; its lock goes stale; B reclaims and finishes the job.
        // Names the home (ADR 0010 §2 row 10): a claim is one scan per home now, and an org's job is in
        // the tenant home because submit() routes on org_id, not on the submitter's axis.
        ExchangeJob claimA = store.claimOne(Duration.ofMinutes(5), SplitTables.TENANT_POOL).orElseThrow();
        jdbc.update("update " + SplitTables.TENANT_POOL
                + ".exchange_job set locked_at = locked_at - interval '10 minutes' where id = ?", jobId);
        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(store.find(jobId, orgId).orElseThrow().status()).isEqualTo(ExchangeJob.COMPLETED);

        // A wakes up and tries to commit its batch: the fence must refuse — and its heartbeat too.
        // On the JOB'S axis, deliberately. progress() and heartbeat() are keyed on the job id alone,
        // so they ride the search_path (ADR 0010 §2 row 10) — run from the harness's PLATFORM pin they
        // would refuse because the row is in another schema, and this test would pass while proving
        // nothing about the attempts fence it is named for.
        ug.co.smsone.shared.tenancy.TenantContext.runAs(orgId, () -> {
            assertThat(store.progress(jobId, claimA.attempts(), 500, 0, 500, List.of()))
                    .isEqualTo(ExchangeJobStore.Progress.LOST_CLAIM);
            assertThat(store.heartbeat(jobId, claimA.attempts())).isFalse();
        });
    }

    @Test
    void jsonlRoundTripsLikeEveryOtherFormat() throws IOException {
        UUID orgId = UUID.randomUUID();
        String key = "exch/o/" + orgId + "/test/source.jsonl";
        storage.objects.put(key,
                "{\"key\":\"k1\",\"value\":\"v1\"}\n{\"key\":\"k2\",\"value\":\"v2\"}\n"
                        .getBytes(StandardCharsets.UTF_8));
        UUID jobId = store.submit(orgId, REQUESTER, ExchangeJob.IMPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "JSONL", key);
        assertThat(worker.drainOnce()).isEqualTo(1);
        assertThat(store.find(jobId, orgId).orElseThrow().processed()).isEqualTo(2);

        UUID exportId = store.submit(orgId, REQUESTER, ExchangeJob.EXPORT,
                ExchangeTestSupport.CountingExchangeHandler.ID, 1, "JSONL", null);
        assertThat(worker.drainOnce()).isEqualTo(1);
        ExchangeJob export = store.find(exportId, orgId).orElseThrow();
        String result = new String(storage.objects.get(export.resultKey()), StandardCharsets.UTF_8);
        assertThat(result.lines()).hasSize(3);
        assertThat(result).contains("\"key\":\"k1\"");
    }
}
