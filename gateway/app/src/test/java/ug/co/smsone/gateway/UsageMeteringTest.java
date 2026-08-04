package ug.co.smsone.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.usage.UsageBuffer;

/**
 * The buffer contract the flusher and the filter share: increments accumulate per consumer, a drain
 * takes everything exactly once, and a failed ship restores counts instead of dropping revenue.
 */
class UsageMeteringTest {

    @Test
    void countsDrainOnceAndRestoreOnFailure() {
        UsageBuffer buffer = new UsageBuffer();
        buffer.increment("org-a");
        buffer.increment("org-a");
        buffer.increment("org-b");

        Map<String, Long> drained = buffer.drain();
        assertThat(drained).containsEntry("org-a", 2L).containsEntry("org-b", 1L);
        assertThat(buffer.drain()).isEmpty(); // exactly once

        buffer.restore(drained);          // ship failed → nothing lost
        buffer.increment("org-a");        // traffic continues during the outage
        assertThat(buffer.drain()).containsEntry("org-a", 3L).containsEntry("org-b", 1L);
    }

    @Test
    void publishFailureLeavesCountsForTheNextFlush() {
        UsageBuffer buffer = new UsageBuffer();
        buffer.increment("org-a");
        AtomicReference<Map<String, Long>> attempted = new AtomicReference<>();

        // Simulate the flusher's failure path directly against the contract.
        Map<String, Long> counts = buffer.drain();
        attempted.set(counts);
        Mono.<Void>error(new IllegalStateException("platform down"))
                .doOnError(e -> buffer.restore(attempted.get()))
                .onErrorComplete()
                .block();

        assertThat(buffer.drain()).containsEntry("org-a", 1L);
    }
}
