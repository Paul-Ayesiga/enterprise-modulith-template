package ug.co.smsone.gateway.usage;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/** Lock-free per-consumer counters between flushes. Drain swaps sums out atomically enough for billing. */
@Component
public class UsageBuffer {

    private final ConcurrentMap<String, LongAdder> counts = new ConcurrentHashMap<>();

    public void increment(String consumer) {
        counts.computeIfAbsent(consumer, key -> new LongAdder()).increment();
    }

    /** Take everything counted so far; callers merge back on a failed ship. */
    public Map<String, Long> drain() {
        Map<String, Long> snapshot = new HashMap<>();
        counts.keySet().forEach(consumer -> {
            LongAdder adder = counts.remove(consumer);
            if (adder != null) {
                long value = adder.sum();
                if (value > 0) {
                    snapshot.put(consumer, value);
                }
            }
        });
        return snapshot;
    }

    public void restore(Map<String, Long> unshipped) {
        unshipped.forEach((consumer, value) ->
                counts.computeIfAbsent(consumer, key -> new LongAdder()).add(value));
    }
}
