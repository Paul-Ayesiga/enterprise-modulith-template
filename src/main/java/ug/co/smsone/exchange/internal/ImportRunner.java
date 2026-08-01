package ug.co.smsone.exchange.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.ExchangeContext;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.exchange.InvalidRecordException;
import ug.co.smsone.files.FileStorageProvider;

/**
 * Executes one claimed IMPORT job: stream the source, skip to the committed offset, apply records
 * through the handler, commit progress + this batch's errors every {@code batch-size} records.
 * The two failure species are kept strictly apart — {@link InvalidRecordException} is DATA (that
 * row is reported and never retried; the job continues), anything else is INFRASTRUCTURE (the
 * uncommitted batch is abandoned and the job retries from the last committed offset, up to
 * max-attempts). Handlers see at-least-once delivery of the in-flight batch; they are idempotent
 * by contract.
 */
@Component
class ImportRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportRunner.class);

    private final ExchangeJobStore store;
    private final HandlerRegistry handlers;
    private final Map<String, FormatCodec> codecs;
    private final FileStorageProvider storage;
    private final ArtifactStore artifacts;
    private final ExchangeProperties config;

    ImportRunner(ExchangeJobStore store, HandlerRegistry handlers, List<FormatCodec> codecs,
            FileStorageProvider storage, ArtifactStore artifacts, ExchangeProperties config) {
        this.store = store;
        this.handlers = handlers;
        this.codecs = codecs.stream()
                .collect(Collectors.toUnmodifiableMap(FormatCodec::id, Function.identity()));
        this.storage = storage;
        this.artifacts = artifacts;
        this.config = config;
    }

    void run(ExchangeJob job) {
        ExchangeHandler handler = handlers.find(job.handler()).orElse(null);
        FormatCodec codec = codecs.get(job.format());
        if (handler == null || codec == null) {
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null,
                    "This job references a handler or format the platform no longer provides.");
            return;
        }
        if (job.cancelRequested()) {
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.CANCELLED, null, null, null);
            return;
        }
        try {
            process(job, handler, codec);
        } catch (FormatCodec.StructureViolation ex) {
            // A whole-file shape problem: retrying re-reads the same file, so fail now with the
            // curated message — it is what the tenant sees on the job.
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null, ex.getMessage());
        } catch (RuntimeException | IOException ex) {
            failOrRetry(job, ex);
        }
    }

    private void process(ExchangeJob job, ExchangeHandler handler, FormatCodec codec) throws IOException {
        String status = job.status();
        if (ExchangeJob.PENDING.equals(status)) {
            if (!store.transition(job.id(), job.attempts(), ExchangeJob.PENDING, ExchangeJob.VALIDATING)) {
                return; // lost the claim before starting
            }
            status = ExchangeJob.VALIDATING;
        }
        try (InputStream in = storage.get(job.sourceKey());
                FormatCodec.RecordReader reader = codec.reader(in, handler.header())) {
            // Reaching here means the reader accepted the file's structure — validation passed.
            if (ExchangeJob.VALIDATING.equals(status)
                    && !store.transition(job.id(), job.attempts(), ExchangeJob.VALIDATING, ExchangeJob.PROCESSING)) {
                return;
            }
            long offset = 0;
            while (offset < job.nextOffset() && reader.next() != null) {
                offset++; // committed by a previous attempt — never re-applied
            }
            long processed = job.processed();
            long failed = job.failed();
            ExchangeContext context = new ExchangeContext(job.orgId(), job.requester());
            boolean endOfInput = false;
            while (!endOfInput) {
                List<ExchangeJobStore.RowError> errors = new ArrayList<>();
                int inBatch = 0;
                while (inBatch < config.batchSize()) {
                    Map<String, String> record = reader.next();
                    if (record == null) {
                        endOfInput = true;
                        break;
                    }
                    offset++;
                    inBatch++;
                    try {
                        handler.importRecord(context, record);
                        processed++;
                    } catch (InvalidRecordException ex) {
                        failed++;
                        errors.add(new ExchangeJobStore.RowError(offset, ex.getMessage()));
                    }
                }
                ExchangeJobStore.Progress progress =
                        store.progress(job.id(), job.attempts(), processed, failed, offset, errors);
                if (progress == ExchangeJobStore.Progress.LOST_CLAIM) {
                    return; // another claimant owns the job now; it resumes from ITS committed offset
                }
                if (progress == ExchangeJobStore.Progress.CANCEL_REQUESTED) {
                    store.markTerminal(job.id(), job.attempts(), ExchangeJob.CANCELLED, null, null, null);
                    return;
                }
            }
            String reportKey = failed > 0 ? uploadErrorReport(job) : null;
            store.markTerminal(job.id(), job.attempts(),
                    failed > 0 ? ExchangeJob.COMPLETED_WITH_ERRORS : ExchangeJob.COMPLETED,
                    null, reportKey, null);
        }
    }

    /** Row-addressed report, streamed DB → temp file → files port, filed as an EXCHANGE document. */
    private String uploadErrorReport(ExchangeJob job) throws IOException {
        Path temp = Files.createTempFile("exchange-errors-", ".csv");
        try {
            try (CSVPrinter printer = new CSVPrinter(
                    Files.newBufferedWriter(temp, StandardCharsets.UTF_8),
                    CSVFormat.RFC4180.builder().setHeader("row_number", "error").get())) {
                store.forEachError(job.id(), (rowNum, error) -> {
                    try {
                        printer.printRecord(rowNum, error);
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
            }
            String key = ArtifactStore.artifactKey(job.orgId(), job.id(), "errors.csv");
            return artifacts.store(temp, key, "text/csv", "import-errors-" + job.id() + ".csv",
                    job.orgId(), job.requester());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void failOrRetry(ExchangeJob job, Exception ex) {
        // The real cause goes to the log; last_error is tenant-visible and stays curated.
        log.error("Exchange import {} attempt {} failed", job.id(), job.attempts(), ex);
        if (job.attempts() >= config.maxAttempts()) {
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null,
                    "The import stopped after " + job.attempts() + " attempts because of a system "
                            + "error. Records committed before the failure are applied. Quote job id "
                            + job.id() + " to support.");
        } else {
            store.releaseForRetry(job.id(), job.attempts(),
                    "A system error interrupted processing; the job will resume automatically.");
        }
    }
}
