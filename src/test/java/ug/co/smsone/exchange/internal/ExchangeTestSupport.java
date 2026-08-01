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

/**
 * Shared context for the exchange tests: storage swapped for an inspectable in-memory map at the
 * PORT (the files IT pins real S3 semantics), plus an instrumented handler that can simulate an
 * infrastructure crash at an exact record and count every delivery — the probe that makes
 * at-least-once delivery and exactly-once effect separately assertable.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ExchangeTestSupport {

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

        @Override
        public void put(String key, InputStream content, long contentLength, String contentType) {
            try {
                objects.put(key, content.readAllBytes());
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        @Override
        public void putLarge(String key, InputStream content, long contentLength, String contentType) {
            put(key, content, contentLength, contentType);
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
