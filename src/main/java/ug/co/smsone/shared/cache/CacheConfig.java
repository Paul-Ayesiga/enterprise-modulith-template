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
import ug.co.smsone.shared.deployment.DeploymentIdentity;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties({CacheProperties.class, IdentityStaleProperties.class})
public class CacheConfig {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CacheConfig.class);

    /**
     * The cache subsystem's own Valkey namespace, before the deployment's (ADR 0010 §6 hop 2→3). It is
     * never used raw: {@link DeploymentIdentity#valkeyKey} is what reaches Valkey, so two deployments
     * sharing one instance cannot read each other's entries. See {@code DeploymentIdentity} for why a
     * shared cache namespace is worse than a copied {@code shedlock} row.
     */
    static final String CACHE_PREFIX = "smsone:cache:";

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

    /**
     * <b>The deployment's namespace goes on the L2 prefix and nowhere else, and that is deliberate.</b>
     * L1 is in-process: one JVM is one deployment, so there is no second writer for a Caffeine entry to
     * collide with. That is exactly the asymmetry {@code TwoLevelCache}'s "both levels, or neither"
     * rule does NOT have — tenants share a JVM, deployments do not — so scoping L1 by deployment would
     * lengthen every key to prove something a JVM boundary already proves. Applying it here also means
     * a cache added tomorrow inherits the namespace by existing, rather than by its author remembering.
     */
    @Bean
    @ConditionalOnProperty(name = "app.cache.l2-enabled", havingValue = "true", matchIfMissing = true)
    RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory, CacheProperties properties,
            DeploymentIdentity deployment) {
        String prefix = deployment.valkeyKey(CACHE_PREFIX);
        // Named at INFO on the way up because the far side of an extraction is where this matters and
        // nobody there can see it any other way: a deployment restored with the platform's identity
        // reads the platform's cached answers, and every layer above is behaving correctly.
        log.info("Deployment '{}' caches into Valkey under '{}'", deployment.id(), prefix);
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(properties.l2Ttl())
                .prefixCacheNameWith(prefix)
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
    CacheInvalidationBroadcaster cacheInvalidationBroadcaster(StringRedisTemplate redisTemplate,
            DeploymentIdentity deployment) {
        return new CacheInvalidationBroadcaster(redisTemplate, deployment);
    }

    /**
     * The GLOBAL/TENANT classification of every cache name (ADR 0010 §3.5). A bean rather than a static
     * lookup so a test can supply its own — {@code CacheRegistry.standardPlus(...)} marked
     * {@code @Primary} — to declare a scratch cache without putting a probe name in the application's
     * own declarations.
     */
    @Bean
    CacheRegistry cacheRegistry() {
        return CacheRegistry.standard();
    }

    @Bean
    @Primary
    TwoLevelCacheManager cacheManager(CacheRegistry cacheRegistry, CaffeineCacheManager caffeineCacheManager,
            org.springframework.beans.factory.ObjectProvider<RedisCacheManager> redisCacheManager,
            org.springframework.beans.factory.ObjectProvider<CacheInvalidationBroadcaster> broadcaster,
            io.micrometer.core.instrument.MeterRegistry meters) {
        return new TwoLevelCacheManager(cacheRegistry, caffeineCacheManager,
                redisCacheManager.getIfAvailable(), broadcaster.getIfAvailable(), meters);
    }

    /**
     * Subscribes to THIS deployment's invalidation topic. The topic is namespaced for the same reason
     * the entries are: a shared topic would have one deployment's cache churn dropping another's L1
     * entries — over-eviction rather than a wrong answer, but a permanent, unattributable hit-rate hole
     * in a deployment where nothing is being written.
     */
    @Bean
    @ConditionalOnProperty(name = "app.cache.l2-enabled", havingValue = "true", matchIfMissing = true)
    RedisMessageListenerContainer cacheInvalidationListenerContainer(RedisConnectionFactory connectionFactory,
            TwoLevelCacheManager cacheManager, CacheInvalidationBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            String[] parts = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8).split("\n", 3);
            if (parts.length < 2 || CacheInvalidationBroadcaster.INSTANCE_ID.equals(parts[0])) {
                return; // own broadcast — the local caches were already updated synchronously
            }
            cacheManager.evictLocal(parts[1], parts.length == 3 ? parts[2] : null);
        }, new ChannelTopic(broadcaster.topic()));
        return container;
    }
}
