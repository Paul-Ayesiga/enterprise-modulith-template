package ug.co.smsone.exchange.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Exchange throughput as numbers someone can alert on: jobs by terminal outcome and records by
 * result. The registry dedupes the counter per tag set (the builder itself still allocates per
 * call — fine at per-batch and per-job frequency, would not be on a per-record path).
 */
@Component
class ExchangeMetrics {

    private final MeterRegistry meters;

    ExchangeMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /** Counts a job once, at its terminal status; a released-for-retry job is not an outcome yet. */
    void jobFinished(ExchangeJob job) {
        if (!job.terminal()) {
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
