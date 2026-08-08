package ug.co.smsone.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCache;

/**
 * {@code @Cacheable(sync = true)} support. Concurrent loads of a cold key must invoke the loader
 * exactly once (striped-lock stampede protection); a warm key must never enter the loader at all.
 * Pure unit test — L1 Caffeine only, no L2, no broadcaster.
 */
class TwoLevelCacheSyncLoadTest {

    /**
     * GLOBAL, so the keys here are the keys the cache stores: stampede protection is about the striped
     * locks and nothing else, and a tenant prefix would only add a thread-local to every assertion.
     * {@code TenantCacheScopingTest} owns the scoping half, including that the stripe is taken from the
     * SCOPED key so two tenants do not serialize on one lock.
     */
    private TwoLevelCache newCache() {
        return new TwoLevelCache("t", CacheScope.GLOBAL, new CaffeineCache("t", Caffeine.newBuilder().build()),
                null, null, new SimpleMeterRegistry());
    }

    @Test
    void concurrentLoadsOfAColdKeyInvokeTheLoaderOnce() throws Exception {
        TwoLevelCache cache = newCache();
        AtomicInteger loads = new AtomicInteger();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return cache.get("k", () -> {
                    loads.incrementAndGet();
                    Thread.sleep(50); // widen the race window so a broken impl loads more than once
                    return "v";
                });
            }));
        }
        ready.await();
        go.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("v");
        }
        pool.shutdown();
        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    void aWarmKeyReturnsTheCachedValueWithoutLoading() throws Exception {
        TwoLevelCache cache = newCache();
        cache.put("k", "warm");
        AtomicInteger loads = new AtomicInteger();
        String value = cache.get("k", () -> {
            loads.incrementAndGet();
            return "cold";
        });
        assertThat(value).isEqualTo("warm");
        assertThat(loads.get()).isZero();
    }
}
