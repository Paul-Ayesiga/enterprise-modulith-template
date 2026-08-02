package ug.co.smsone.gateway.traffic;

import org.springframework.cloud.gateway.filter.factory.cache.keygenerator.CacheKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Makes edge response caching tenant-safe. SCG's LocalResponseCache autoconfiguration builds its
 * {@code ResponseCacheManagerFactory} from whichever {@code CacheKeyGenerator} bean is injected by
 * type, so marking a tenant-aware generator {@code @Primary} replaces the stock (URI + cookies) key
 * without disabling the autoconfiguration or its per-route {@code localResponseCache} filter.
 */
@Configuration(proxyBeanMethods = false)
class CacheConfig {

    @Bean
    @Primary
    CacheKeyGenerator tenantAwareCacheKeyGenerator() {
        return new TenantAwareCacheKeyGenerator();
    }
}
