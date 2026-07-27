package ug.co.smsone.shared.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Wires the Lettuce client used for Valkey-backed rate limiting — active only when
 * {@code app.rate-limit.enabled=true} (default). The client is <em>created but not connected</em>
 * here (a tight command timeout is baked in); {@link DistributedRateLimiter} connects lazily on
 * first use, so app startup never depends on Valkey being reachable — consistent with fail-open.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(RedisConnectionFactory connectionFactory, RateLimitProperties properties) {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            throw new IllegalStateException("Rate limiting requires a Lettuce Redis/Valkey connection factory");
        }
        RedisStandaloneConfiguration standalone = lettuce.getStandaloneConfiguration();
        if (standalone == null) {
            throw new IllegalStateException("Rate limiting requires a standalone Redis/Valkey configuration");
        }
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHostName())
                .withPort(standalone.getPort())
                .withTimeout(properties.backendTimeout());
        RedisPassword password = standalone.getPassword();
        if (password.isPresent()) {
            if (standalone.getUsername() != null) {
                uri.withAuthentication(standalone.getUsername(), password.get());
            } else {
                uri.withPassword(password.get());
            }
        }
        RedisClient client = RedisClient.create(uri.build()); // create() does not connect — the limiter connects lazily
        // Bound the TCP connect too (RedisURI.withTimeout is only the command timeout; connect defaults to 10s),
        // so a down Valkey fails fast into the limiter's fail-open path instead of stalling threads.
        client.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(properties.backendTimeout()).build())
                .build());
        return client;
    }
}
