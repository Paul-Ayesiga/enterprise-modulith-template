package ug.co.smsone.exchange.internal;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ug.co.smsone.exchange.ExchangeContext;
import ug.co.smsone.exchange.ExchangeHandler;

/**
 * Executes one claimed EXPORT job: the handler streams records into the codec's sink over a temp
 * file, which then becomes a stored, document-registered artifact. Exports are read-only, so a
 * retry regenerates the WHOLE file rather than resuming — correctness by reconstruction, no
 * partial-file bookkeeping. Cancellation is honored before the run starts; a run in flight
 * finishes (it is already paid for).
 */
@Component
class ExportRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportRunner.class);

    private final ExchangeJobStore store;
    private final HandlerRegistry handlers;
    private final Map<String, FormatCodec> codecs;
    private final ArtifactStore artifacts;
    private final ExchangeProperties config;
    private final ExchangeMetrics metrics;

    ExportRunner(ExchangeJobStore store, HandlerRegistry handlers, List<FormatCodec> codecs,
            ArtifactStore artifacts, ExchangeProperties config, ExchangeMetrics metrics) {
        this.store = store;
        this.handlers = handlers;
        this.codecs = codecs.stream()
                .collect(Collectors.toUnmodifiableMap(FormatCodec::id, Function.identity()));
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
        } catch (RuntimeException | IOException ex) {
            failOrRetry(job, ex);
        }
    }

    private void process(ExchangeJob job, ExchangeHandler handler, FormatCodec codec) throws IOException {
        if (ExchangeJob.PENDING.equals(job.status())
                && !store.transition(job.id(), job.attempts(), ExchangeJob.PENDING, ExchangeJob.PROCESSING)) {
            return;
        }
        Path temp = Files.createTempFile("exchange-export-", "." + codec.fileExtension());
        try {
            AtomicLong written = new AtomicLong();
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp));
                    FormatCodec.RecordSink sink = codec.writer(out, handler.header())) {
                handler.export(new ExchangeContext(job.orgId(), job.requester()), record -> {
                    try {
                        sink.write(record);
                        written.incrementAndGet();
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                });
            }
            String fileName = handler.id() + "-export." + codec.fileExtension();
            String key = ArtifactStore.artifactKey(job.orgId(), job.id(), fileName);
            artifacts.store(temp, key, codec.contentType(), fileName, job.orgId(), job.requester());
            if (store.progress(job.id(), job.attempts(), written.get(), 0, 0, List.of())
                    == ExchangeJobStore.Progress.LOST_CLAIM) {
                return; // another claimant re-exports; this artifact stays as an unreferenced document
            }
            metrics.records(job.handler(), "exported", written.get());
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.COMPLETED, key, null, null);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void failOrRetry(ExchangeJob job, Exception ex) {
        log.error("Exchange export {} attempt {} failed", job.id(), job.attempts(), ex);
        if (job.attempts() >= config.maxAttempts()) {
            store.markTerminal(job.id(), job.attempts(), ExchangeJob.FAILED, null, null,
                    "The export stopped after " + job.attempts() + " attempts because of a system "
                            + "error. Quote job id " + job.id() + " to support.");
        } else {
            store.releaseForRetry(job.id(), job.attempts(),
                    "A system error interrupted the export; it will restart automatically.");
        }
    }
}
