package ug.co.smsone.gateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.quota.ConsumerResolver;
import ug.co.smsone.gateway.core.quota.Quota;
import ug.co.smsone.gateway.core.quota.QuotaProvider;

/**
 * Regression pin for the double-subscribe: {@code chain.filter} is a {@code Mono<Void>} and always
 * completes EMPTY, so an empty-fallback placed downstream of it re-ran the whole filter chain against
 * an already-committed response — the route's rate limiter fired twice and its late header write
 * closed the connection under strict keep-alive clients (mcp-remote reported "other side closed",
 * Claude Desktop showed "Server disconnected"). Every admitted outcome must subscribe the chain
 * exactly once; a denial must not subscribe it at all.
 */
class QuotaFilterChainTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final AtomicInteger chainRuns = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> Mono.fromRunnable(chainRuns::incrementAndGet);
    private final MockServerWebExchange exchange =
            MockServerWebExchange.from(MockServerHttpRequest.get("/quota/x").build());

    private QuotaFilter filter(ConsumerResolver resolver, QuotaProvider provider,
            ReactiveStringRedisTemplate redis) {
        @SuppressWarnings("unchecked")
        ObjectProvider<QuotaProvider> holder = mock(ObjectProvider.class);
        when(holder.getIfAvailable()).thenReturn(provider);
        return new QuotaFilter(resolver, holder, redis);
    }

    private static ReactiveStringRedisTemplate redisCounting(long windowCount) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveValueOperations<String, String> values = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(Mono.just(windowCount));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        return redis;
    }

    @Test
    void anUnlimitedQuotaRunsTheChainExactlyOnce() {
        QuotaFilter quotaFilter = filter(e -> Mono.just("org-1"),
                consumer -> Mono.just(Quota.UNLIMITED), mock(ReactiveStringRedisTemplate.class));

        quotaFilter.filter(exchange, chain).block(TIMEOUT);

        assertThat(chainRuns).hasValue(1);
    }

    @Test
    void anUnderLimitQuotaRunsTheChainExactlyOnce() {
        QuotaFilter quotaFilter = filter(e -> Mono.just("org-1"),
                consumer -> Mono.just(new Quota(2, Duration.ofMinutes(1))), redisCounting(1));

        quotaFilter.filter(exchange, chain).block(TIMEOUT);

        assertThat(chainRuns).hasValue(1);
    }

    @Test
    void anOverLimitQuotaIsA429ThatNeverTouchesTheChain() {
        QuotaFilter quotaFilter = filter(e -> Mono.just("org-1"),
                consumer -> Mono.just(new Quota(2, Duration.ofMinutes(1))), redisCounting(3));

        assertThatThrownBy(() -> quotaFilter.filter(exchange, chain).block(TIMEOUT))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        denied -> assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(chainRuns).hasValue(0);
    }

    @Test
    void noResolvableConsumerRunsTheChainExactlyOnce() {
        QuotaFilter quotaFilter = filter(e -> Mono.empty(),
                consumer -> Mono.just(Quota.UNLIMITED), mock(ReactiveStringRedisTemplate.class));

        quotaFilter.filter(exchange, chain).block(TIMEOUT);

        assertThat(chainRuns).hasValue(1);
    }

    @Test
    void noQuotaFromTheProviderRunsTheChainExactlyOnce() {
        QuotaFilter quotaFilter = filter(e -> Mono.just("org-1"),
                consumer -> Mono.empty(), mock(ReactiveStringRedisTemplate.class));

        quotaFilter.filter(exchange, chain).block(TIMEOUT);

        assertThat(chainRuns).hasValue(1);
    }
}
