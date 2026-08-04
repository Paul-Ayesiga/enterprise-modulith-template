package ug.co.smsone.gateway.usage;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ug.co.smsone.gateway.core.usage.UsageSink;

/**
 * Ships the buffered counts to the platform ledger once a minute. No sink configured = metering
 * stays local-only (the buffer caps itself by consumer count, not row count). A failed ship
 * RESTORES the drained counts — usage lost is revenue lost, so it waits for the next flush instead.
 */
@Component
public class UsageFlushJob {

    private static final Logger log = LoggerFactory.getLogger(UsageFlushJob.class);

    private final UsageBuffer buffer;
    private final ObjectProvider<UsageSink> sink;

    UsageFlushJob(UsageBuffer buffer, ObjectProvider<UsageSink> sink) {
        this.buffer = buffer;
        this.sink = sink;
    }

    @Scheduled(fixedDelayString = "${gateway.platform.usage-report.flush-millis:60000}")
    public void flush() {
        UsageSink target = sink.getIfAvailable();
        if (target == null) {
            return;
        }
        Map<String, Long> counts = buffer.drain();
        if (counts.isEmpty()) {
            return;
        }
        target.publish(counts).subscribe(
                unused -> { },
                error -> {
                    log.warn("Usage flush failed ({} consumers) — restoring for the next flush: {}",
                            counts.size(), error.toString());
                    buffer.restore(counts);
                });
    }
}
