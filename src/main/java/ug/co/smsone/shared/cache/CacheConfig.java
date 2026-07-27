package ug.co.smsone.shared.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private static final String CACHE_PREFIX = "smsone:cache:";

    @Bean
    CaffeineCacheManager caffeineCacheManager(CacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(properties.l1Ttl())
                .maximumSize(properties.l1MaxSize()));
        return manager;
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.l2-enabled", havingValue = "true", matchIfMissing = true)
    RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory, CacheProperties properties) {
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.l2Ttl())
                .prefixCacheNameWith(CACHE_PREFIX);
        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.l2-enabled", havingValue = "true", matchIfMissing = true)
    CacheInvalidationBroadcaster cacheInvalidationBroadcaster(StringRedisTemplate redisTemplate) {
        return new CacheInvalidationBroadcaster(redisTemplate);
    }

    @Bean
    @Primary
    TwoLevelCacheManager cacheManager(CaffeineCacheManager caffeineCacheManager,
            org.springframework.beans.factory.ObjectProvider<RedisCacheManager> redisCacheManager,
            org.springframework.beans.factory.ObjectProvider<CacheInvalidationBroadcaster> broadcaster) {
        return new TwoLevelCacheManager(caffeineCacheManager,
                redisCacheManager.getIfAvailable(), broadcaster.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.l2-enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer cacheInvalidationListenerContainer(RedisConnectionFactory connectionFactory,
            TwoLevelCacheManager cacheManager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String[] parts = new String(message.getBody()).split("\n", 3);
            if (parts.length < 2 || CacheInvalidationBroadcaster.INSTANCE_ID.equals(parts[0])) {
                return; // own broadcast — the local caches were already updated synchronously
            }
            cacheManager.evictLocal(parts[1], parts.length == 3 ? parts[2] : null);
        }, new ChannelTopic(CacheInvalidationBroadcaster.TOPIC));
        return container;
    }
}
