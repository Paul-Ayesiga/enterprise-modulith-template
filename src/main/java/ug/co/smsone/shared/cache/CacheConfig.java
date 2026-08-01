package ug.co.smsone.shared.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig {

    private static final String CACHE_PREFIX = "smsone:cache:";

    /**
     * L2 stores JSON, which is type-free: without type ids a cached {@code Set<String>} reads back as
     * an {@code ArrayList} and a record as a {@code LinkedHashMap}, so the {@code @Cacheable} proxy
     * throws ClassCastException — and only after L1 expires, which makes it look intermittent.
     * Typing is therefore ON, but never {@code enableUnsafeDefaultTyping()}: a cache is attacker-
     * reachable if Valkey is, so the validator allows only our own types and the JDK value types we
     * actually cache. Anything else fails closed at deserialization.
     */
    private static final PolymorphicTypeValidator CACHE_TYPE_VALIDATOR = BasicPolymorphicTypeValidator.builder()
            .allowIfSubType("ug.co.smsone.")
            .allowIfSubType("java.util.")
            .allowIfSubType("java.lang.")
            .allowIfSubType("java.time.")
            .allowIfSubType("java.math.")
            // Spring's NullValue sentinel — without it a cached null poisons its key: every read of
            // the L2 entry fails type validation, counts as a miss, re-caches, and broadcasts.
            .allowIfSubType("org.springframework.cache.support.")
            .build();

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
                .prefixCacheNameWith(CACHE_PREFIX)
                // Jackson 3 JSON values (readable in Valkey, no Serializable/JVM coupling), carrying
                // the type id needed to reconstruct non-scalar values — see CACHE_TYPE_VALIDATOR.
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        GenericJacksonJsonRedisSerializer.builder()
                                .enableDefaultTyping(CACHE_TYPE_VALIDATOR)
                                // Negative caching: the default config allows null values but the
                                // serializer does not round-trip NullValue unless told to.
                                .enableSpringCacheNullValueSupport()
                                .build()));
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
            org.springframework.beans.factory.ObjectProvider<CacheInvalidationBroadcaster> broadcaster,
            io.micrometer.core.instrument.MeterRegistry meters) {
        return new TwoLevelCacheManager(caffeineCacheManager,
                redisCacheManager.getIfAvailable(), broadcaster.getIfAvailable(), meters);
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.l2-enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer cacheInvalidationListenerContainer(RedisConnectionFactory connectionFactory,
            TwoLevelCacheManager cacheManager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String[] parts = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8).split("\n", 3);
            if (parts.length < 2 || CacheInvalidationBroadcaster.INSTANCE_ID.equals(parts[0])) {
                return; // own broadcast — the local caches were already updated synchronously
            }
            cacheManager.evictLocal(parts[1], parts.length == 3 ? parts[2] : null);
        }, new ChannelTopic(CacheInvalidationBroadcaster.TOPIC));
        return container;
    }
}
