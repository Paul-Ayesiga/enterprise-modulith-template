package ug.co.smsone.shared.cache;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Publishes cache invalidations to the shared topic so OTHER instances drop stale L1 entries.
 * Message format: {@code instanceId\ncacheName[\nkey]} — a missing key means "clear the cache".
 * The instance id lets nodes ignore their own broadcasts (a writer's fresh L1 entry must survive).
 */
public class CacheInvalidationBroadcaster {

    static final String TOPIC = "smsone:cache:invalidations";

    /** Identifies this JVM in broadcasts for the process lifetime. */
    static final String INSTANCE_ID = UUID.randomUUID().toString();

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBroadcaster.class);

    private final StringRedisTemplate redisTemplate;

    public CacheInvalidationBroadcaster(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    void publish(String cacheName, String key) {
        try {
            String message = key == null
                    ? INSTANCE_ID + "\n" + cacheName
                    : INSTANCE_ID + "\n" + cacheName + "\n" + key;
            redisTemplate.convertAndSend(TOPIC, message);
        } catch (RuntimeException e) {
            log.warn("Cache invalidation broadcast failed for '{}': {}", cacheName, e.getMessage());
        }
    }
}
