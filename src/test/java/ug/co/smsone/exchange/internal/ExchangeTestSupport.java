package ug.co.smsone.exchange.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ug.co.smsone.exchange.ExchangeContext;
import ug.co.smsone.exchange.ExchangeHandler;
import ug.co.smsone.exchange.ImportOutcome;
import ug.co.smsone.exchange.InvalidRecordException;
import ug.co.smsone.exchange.RecordWriter;
import ug.co.smsone.files.FileNotFoundException;
import ug.co.smsone.files.FileStorageProvider;
import ug.co.smsone.files.ObjectPage;
import ug.co.smsone.files.StoredObject;

/**
 * Shared context for the exchange tests: storage swapped for an inspectable in-memory map at the
 * PORT (the files IT pins real S3 semantics), plus an instrumented handler that can simulate an
 * infrastructure crash at an exact record and count every delivery — the probe that makes
 * at-least-once delivery and exactly-once effect separately assertable.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ExchangeTestSupport {

    /**
     * Empties the queue completely — <b>the jobs in both homes AND the signals that index them</b>.
     *
     * <p>Every exchange test asserts on {@code drainOnce()} returning exactly 1, and the queue is a
     * table shared by every class running against the singleton Postgres, so leftovers would be claimed
     * instead of this test's job. Since ADR 0010 Phase 3 there is a second thing to leave behind:
     * {@code platform.queue_signal} is what the worker reads to decide which scope to look at, and a
     * signal whose rows this method deleted is one wasted probe on some later test's drain. That is
     * harmless by design — the worker that finds nothing deletes it — but it is bounded by
     * {@code ExchangeWorker.MAX_EMPTY_PROBES}, and "start from an empty queue" now means both tables or
     * it means nothing.
     *
     * <p>{@code exchange_job} is a split table (ADR 0010 §2 row 10), so leftovers in EITHER home would
     * be claimed; {@code queue_signal} is platform-tier and named, so it is reachable from whichever
     * axis the harness happens to hold.
     */
    static void clearQueue(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        for (String home : ug.co.smsone.shared.tenancy.SplitTables.homes()) {
            jdbc.update("delete from " + home + ".exchange_job");
        }
        jdbc.update("delete from platform.queue_signal where queue = ?", ExchangeJobStore.QUEUE);
    }

    /**
     * Ages a claimed job's lock past the stale-lock window so the next poll reclaims it —
     * <b>including the signal that says when the scope is next worth looking at</b>.
     *
     * <p>The second half is the one a reader will not expect, and it is the design rather than a leak.
     * {@code ExchangeJobStore.releaseSignal} computes {@code due_at} from {@code locked_at + staleLock},
     * so a test that rewinds {@code locked_at} behind the queue's back leaves the signal parked in the
     * future and the drain it is about finds nothing. Production has no such path — every write that
     * makes a job claimable goes through {@code submit}, {@code releaseForRetry} or the release itself,
     * all of which keep the two in step — which is precisely why simulating a dead claimant has to do
     * by hand what those do for free.
     */
    static void expireClaim(org.springframework.jdbc.core.JdbcTemplate jdbc, java.util.UUID jobId,
            java.util.UUID orgId) {
        jdbc.update("update " + ug.co.smsone.shared.tenancy.SplitTables.homeOf(orgId)
                + ".exchange_job set locked_at = locked_at - interval '10 minutes' where id = ?", jobId);
        jdbc.update("update platform.queue_signal set due_at = now() where queue = ? and org_id = ?",
                ExchangeJobStore.QUEUE, orgId);
    }

    @Bean
    @Primary
    InMemoryFileStorage inMemoryFileStorage() {
        return new InMemoryFileStorage();
    }

    @Bean
    CountingExchangeHandler countingExchangeHandler() {
        return new CountingExchangeHandler();
    }

    public static class InMemoryFileStorage implements FileStorageProvider {

        public final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        /**
         * Kept beside {@link #objects} rather than folded into it because several tests write straight
         * into that map. Such a key has no recorded type, and {@link #open} answers the store's own
         * fallback for it — a double that invented one would let a copy pass here and lose the type
         * against a real store.
         */
        private final Map<String, String> contentTypes = new ConcurrentHashMap<>();

        @Override
        public void put(String key, InputStream content, long contentLength, String contentType) {
            try {
                objects.put(key, content.readAllBytes());
                if (contentType != null) {
                    contentTypes.put(key, contentType);
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        @Override
        public void putLarge(String key, InputStream content, long contentLength, String contentType) {
            put(key, content, contentLength, contentType);
        }

        /**
         * Lexicographic and keyset, and {@code more} is computed from what is actually left rather than
         * from the page being full — the two readings differ exactly when a page exhausts the prefix, and
         * a double that conflated them would hide the bug it exists to let tests run past.
         * {@code FileStorageIntegrationTest} pins the same semantics against real SeaweedFS.
         */
        @Override
        public ObjectPage list(String prefix, String startAfter, int maxKeys) {
            List<String> matching = objects.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .filter(key -> startAfter == null || key.compareTo(startAfter) > 0)
                    .sorted()
                    .toList();
            return new ObjectPage(matching.stream().limit(maxKeys).toList(), matching.size() > maxKeys);
        }

        @Override
        public StoredObject open(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new FileNotFoundException("No such object: " + key, null);
            }
            return new StoredObject(key, contentTypes.get(key), bytes.length,
                    new ByteArrayInputStream(bytes));
        }

        @Override
        public void write(StoredObject object) {
            put(object.key(), object.content(), object.sizeBytes(), object.contentType());
        }

        @Override
        public InputStream get(String key) {
            byte[] bytes = objects.get(key);
            if (bytes == null) {
                throw new FileNotFoundException("No such object: " + key, null);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public URL presignGet(String key, Duration ttl) {
            return url("http://storage.local/signed/" + key.replace("/", "_"));
        }

        @Override
        public URL presignPut(String key, String contentType, Duration ttl) {
            return url("http://storage.local/upload/" + key.replace("/", "_"));
        }

        private static URL url(String value) {
            try {
                return URI.create(value).toURL();
            } catch (MalformedURLException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    /**
     * Records with key/value columns. A blank key or the value {@code bad} is a DATA error; the
     * {@code failAtCall} trigger throws a one-shot runtime exception — the INFRASTRUCTURE species.
     * {@code applied} holds distinct keys (the idempotent effect); {@code applyCalls} counts every
     * invocation (the delivery attempts).
     */
    public static class CountingExchangeHandler implements ExchangeHandler {

        public static final String ID = "test-counter";

        public final Set<String> applied = ConcurrentHashMap.newKeySet();
        public final AtomicLong applyCalls = new AtomicLong();
        public volatile long failAtCall = -1;
        public final AtomicBoolean failFired = new AtomicBoolean();
        public volatile LongConsumer onCall = call -> { };

        public void reset() {
            applied.clear();
            applyCalls.set(0);
            failAtCall = -1;
            failFired.set(false);
            onCall = call -> { };
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public String importPermission() {
            return "org:read";
        }

        @Override
        public String exportPermission() {
            return "member:read";
        }

        @Override
        public List<String> header() {
            return List.of("key", "value");
        }

        @Override
        public ImportOutcome importRecord(ExchangeContext context, Map<String, String> record) {
            long call = applyCalls.incrementAndGet();
            onCall.accept(call);
            if (call == failAtCall && failFired.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated infrastructure crash at call " + call);
            }
            String key = record.getOrDefault("key", "").trim();
            if (key.isEmpty()) {
                throw new InvalidRecordException("key is required.");
            }
            if ("bad".equals(record.getOrDefault("value", ""))) {
                throw new InvalidRecordException("value 'bad' is not allowed.");
            }
            return applied.add(key) ? ImportOutcome.APPLIED : ImportOutcome.SKIPPED;
        }

        @Override
        public void export(ExchangeContext context, RecordWriter out) {
            for (int i = 1; i <= 3; i++) {
                out.write(Map.of("key", "k" + i, "value", "v" + i));
            }
        }
    }
}
