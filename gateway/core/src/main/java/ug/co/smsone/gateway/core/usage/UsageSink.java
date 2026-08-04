package ug.co.smsone.gateway.core.usage;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * Port — where metered per-consumer request counts go (the platform's usage ledger). The edge
 * batches locally and flushes; a sink failure must never touch request latency, and the flusher
 * restores unshipped counts so usage is never silently dropped (undercharging is a defect too).
 */
public interface UsageSink {

    Mono<Void> publish(Map<String, Long> countsByConsumer);
}
