package ug.co.smsone.gateway.platform;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ug.co.smsone.gateway.core.quota.Quota;
import ug.co.smsone.gateway.core.quota.QuotaProvider;

/**
 * Fetches a consumer's quota from the platform's subscription endpoint over reactive HTTP, presenting
 * the shared gateway secret, and caches it briefly so the plan lookup does not run on every proxied
 * request. A {@code limit} of {@code -1} is the plan's "no ceiling" → {@link Quota#UNLIMITED}. A failed
 * fetch also fails OPEN (unlimited): a subscription-service blip must not lock every tenant out.
 */
class ModulithQuotaProvider implements QuotaProvider {

    private final WebClient webClient;
    private final String secret;
    private final Cache<String, Quota> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    ModulithQuotaProvider(String uri, String secret) {
        this.webClient = WebClient.create(uri);
        this.secret = secret;
    }

    @Override
    public Mono<Quota> quotaFor(String consumer) {
        Quota cached = cache.getIfPresent(consumer);
        if (cached != null) {
            return Mono.just(cached);
        }
        return webClient.get()
                .uri(uri -> uri.queryParam("consumer", consumer).build())
                .header("X-Gateway-Secret", secret)
                .retrieve()
                .bodyToMono(QuotaResult.class)
                .map(result -> result.limit() < 0 ? Quota.UNLIMITED
                        : new Quota(result.limit(), Duration.ofSeconds(result.windowSeconds())))
                .doOnNext(quota -> cache.put(consumer, quota))
                .onErrorReturn(Quota.UNLIMITED);
    }

    record QuotaResult(long limit, long windowSeconds) {
    }
}
