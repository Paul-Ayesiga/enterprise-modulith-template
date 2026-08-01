package ug.co.smsone.exchange.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Exchange throughput as numbers someone can alert on: jobs by terminal outcome and records by
 * result. Registered per tag set on first use — the registry dedupes, so this stays allocation-free
 * after warm-up.
 */
@Component
class ExchangeMetrics {

    private static final Set<String> TERMINAL = Set.of(ExchangeJob.COMPLETED,
            ExchangeJob.COMPLETED_WITH_ERRORS, ExchangeJob.FAILED, ExchangeJob.CANCELLED);

    private final MeterRegistry meters;

    ExchangeMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Counts a job once, at its terminal status; a released-for-retry job is not an outcome yet. */
    void jobFinished(ExchangeJob job) {
        if (!TERMINAL.contains(job.status())) {
            return;
        }
        Counter.builder("smsone.exchange.jobs")
                .description("Exchange jobs by terminal outcome")
                .tag("handler", job.handler())
                .tag("type", job.jobType())
                .tag("outcome", job.status().toLowerCase())
                .register(meters)
                .increment();
    }

    void records(String handler, String result, long count) {
        if (count <= 0) {
            return;
        }
        Counter.builder("smsone.exchange.records")
                .description("Exchange records moved, by result")
                .tag("handler", handler)
                .tag("result", result)
                .register(meters)
                .increment(count);
    }
}
