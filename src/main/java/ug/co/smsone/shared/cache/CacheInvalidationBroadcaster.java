package ug.co.smsone.shared.cache;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import ug.co.smsone.shared.deployment.DeploymentIdentity;

/**
 * Publishes cache invalidations to the shared topic so OTHER instances drop stale L1 entries.
 * Message format: {@code instanceId\ncacheName[\nkey]} — a missing key means "clear the cache".
 * The instance id lets nodes ignore their own broadcasts (a writer's fresh L1 entry must survive).
 *
 * <p>"Other instances" means other instances <em>of this deployment</em>: the topic carries the
 * deployment's namespace (ADR 0010 §6 hop 2→3), so an extracted deployment sharing a Valkey neither
 * hears nor is heard by the platform. Sharing it would not corrupt anything — the message names a
 * cache and a key, and the worst outcome is dropping an L1 entry that was fine — but it would be a
 * permanent, unattributable hit-rate hole in whichever deployment is quieter.
 */
public class CacheInvalidationBroadcaster {

    /** The topic's own name, before the deployment's namespace. Never published to raw. */
    static final String TOPIC = "smsone:cache:invalidations";

    /** Identifies this JVM in broadcasts for the process lifetime. */
    static final String INSTANCE_ID = UUID.randomUUID().toString();

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationBroadcaster.class);

    private final StringRedisTemplate redisTemplate;
    private final String topic;

    public CacheInvalidationBroadcaster(StringRedisTemplate redisTemplate, DeploymentIdentity deployment) {
        this.redisTemplate = redisTemplate;
        this.topic = deployment.valkeyKey(TOPIC);
    }

    /** The channel this deployment publishes on and subscribes to. */
    public String topic() {
        return topic;
    }

    void publish(String cacheName, String key) {
        try {
            String message = key == null
                    ? INSTANCE_ID + "\n" + cacheName
                    : INSTANCE_ID + "\n" + cacheName + "\n" + key;
            redisTemplate.convertAndSend(topic, message);
        } catch (RuntimeException e) {
            log.warn("Cache invalidation broadcast failed for '{}': {}", cacheName, e.getMessage());
        }
    }
}
