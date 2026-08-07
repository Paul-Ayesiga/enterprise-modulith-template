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
    private final ExchangeMetrics metrics;

    ImportRunner(ExchangeJobStore store, HandlerRegistry handlers, List<FormatCodec> codecs,
            FileStorageProvider storage, ArtifactStore artifacts, ExchangeProperties config,
            ExchangeMetrics metrics) {
        this.store = store;
        this.handlers = handlers;
        this.codecs = codecs.stream()
                .collect(Collectors.toUnmodifiableMap(FormatCodec::id, Function.identity()));
        this.storage = storage;
        this.artifacts = artifacts;
        this.config = config;
        this.metrics = metrics;
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
        } catch (ug.co.smsone.files.FileNotFoundException ex) {
            // The source was deleted (its document row is org-manageable) — retrying cannot bring
            // the bytes back, so fail immediately with the honest reason instead of burning attempts.
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null,
                    "The source file no longer exists — it was deleted before the import finished.");
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
        // XLSX is itself a zip container and must reach its codec intact; every other format gets
        // the guidelines' ZIP medium — a zipped source is unwrapped to its first entry.
        try (InputStream in = "XLSX".equals(job.format())
                        ? storage.get(job.sourceKey())
                        : unwrapIfZipped(storage.get(job.sourceKey()));
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
            if (offset < job.nextOffset()) {
                // The file ended BEFORE the committed resume point: it is not the file this job's
                // progress belongs to. Completing would silently bless a truncated re-read.
                store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null,
                        "The source no longer matches the job's recorded progress (the file "
                                + "appears truncated). Submit the file again as a new job.");
                return;
            }
            long processed = job.processed();
            long failed = job.failed();
            long skippedTotal = 0;
            ExchangeContext context = new ExchangeContext(job.orgId(), job.requesterPersonId());
            boolean endOfInput = false;
            long lastBeat = System.nanoTime();
            long beatEvery = config.staleLock().toNanos() / 3;
            while (!endOfInput) {
                List<ExchangeJobStore.RowError> errors = new ArrayList<>();
                int inBatch = 0;
                int skipped = 0;
                while (inBatch < config.batchSize()) {
                    Map<String, String> record = reader.next();
                    if (record == null) {
                        endOfInput = true;
                        break;
                    }
                    offset++;
                    inBatch++;
                    try {
                        if (handler.importRecord(context, record) == ug.co.smsone.exchange.ImportOutcome.SKIPPED) {
                            skipped++;
                        }
                        processed++;
                    } catch (InvalidRecordException ex) {
                        failed++;
                        errors.add(new ExchangeJobStore.RowError(offset, ex.getMessage()));
                    }
                    // Records may do slow remote work (provisioning round-trips), so the lock is
                    // re-stamped mid-batch: a HEALTHY claimant can only go stale if one single
                    // record outlives the whole stale-lock window — the documented ceiling.
                    if (inBatch % 32 == 0 && System.nanoTime() - lastBeat > beatEvery) {
                        if (!store.heartbeat(job.id(), job.attempts())) {
                            return; // claim lost mid-batch: stop side effects NOW, discard the batch
                        }
                        lastBeat = System.nanoTime();
                    }
                }
                ExchangeJobStore.Progress progress =
                        store.progress(job.id(), job.attempts(), processed, failed, offset, errors);
                if (progress == ExchangeJobStore.Progress.LOST_CLAIM) {
                    return; // another claimant owns the job now; it resumes from ITS committed offset
                }
                lastBeat = System.nanoTime(); // progress stamped the lock too
                // Counted only after the batch COMMITTED — a replayed batch is never double-counted.
                metrics.records(job.handler(), "processed", inBatch - errors.size());
                metrics.records(job.handler(), "failed", errors.size());
                metrics.records(job.handler(), "skipped", skipped);
                skippedTotal += skipped;
                if (progress == ExchangeJobStore.Progress.CANCEL_REQUESTED) {
                    // Cancelled work still gets its report — the rows already found stay addressable.
                    String reportKey = failed > 0 ? uploadErrorReport(job) : null;
                    store.markTerminal(job.id(), job.attempts(), ExchangeJob.CANCELLED, null, reportKey, null);
                    return;
                }
            }
            if (skippedTotal > 0) {
                log.info("Exchange import {} absorbed {} already-applied records as SKIPPED",
                        job.id(), skippedTotal);
            }
            String reportKey = failed > 0 ? uploadErrorReport(job) : null;
            store.markTerminal(job.id(), job.attempts(),
                    failed > 0 ? ExchangeJob.COMPLETED_WITH_ERRORS : ExchangeJob.COMPLETED,
                    null, reportKey, null);
        }
    }

    /**
     * A zipped source is unwrapped to its FIRST entry (one data file per archive — the guidelines'
     * ZIP medium), sniffed by magic bytes so it works whatever the file was named. XLSX never
     * reaches here — it is exempted by format in {@code process()}, being a zip itself.
     */
    private InputStream unwrapIfZipped(InputStream in) throws IOException {
        java.io.PushbackInputStream pushback = new java.io.PushbackInputStream(in, 4);
        byte[] magic = pushback.readNBytes(4);
        pushback.unread(magic, 0, magic.length);
        boolean zip = magic.length == 4 && magic[0] == 'P' && magic[1] == 'K'
                && magic[2] == 3 && magic[3] == 4;
        if (!zip) {
            return pushback;
        }
        java.util.zip.ZipInputStream zipStream = new java.util.zip.ZipInputStream(pushback);
        if (zipStream.getNextEntry() == null) {
            throw new FormatCodec.StructureViolation("The ZIP archive is empty.");
        }
        return zipStream;
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
                    job.orgId(), job.requesterPersonId());
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
                    "A system error interrupted processing; the job will resume automatically.",
                    config.retryBackoff(job.attempts()), config.staleLock());
        }
    }
}
