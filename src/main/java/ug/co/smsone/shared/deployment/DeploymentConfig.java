package ug.co.smsone.shared.deployment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link DeploymentIdentity} unconditionally.
 *
 * <p>Its own class rather than a line on {@code CacheConfig}, because three subsystems in three modules
 * read it and two of them can be switched off: {@code RateLimitConfig} is
 * {@code @ConditionalOnProperty(app.rate-limit.enabled)} and the cache's Valkey beans are conditional
 * on {@code app.cache.l2-enabled}. A deployment identity that existed only when the cache was on would
 * be absent from exactly the deployment that disabled L2 and still writes objects.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeploymentIdentity.class)
class DeploymentConfig {
}
