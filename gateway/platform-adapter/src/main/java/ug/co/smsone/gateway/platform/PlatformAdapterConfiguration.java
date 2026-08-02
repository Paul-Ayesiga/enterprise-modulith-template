package ug.co.smsone.gateway.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ug.co.smsone.gateway.core.audit.AuditSink;
import ug.co.smsone.gateway.core.quota.QuotaProvider;
import ug.co.smsone.gateway.core.security.ApiKeyIntrospector;

/**
 * Wires the platform adapters, each gated on its own endpoint being configured — absent, the gateway
 * simply does not offer that capability and the core is none the wiser. The API-key introspector needs
 * {@code gateway.platform.introspection.uri}; the edge audit sink needs {@code gateway.platform.audit.uri};
 * the quota provider needs {@code gateway.platform.quota.uri}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IntrospectionProperties.class, EdgeAuditProperties.class, EdgeQuotaProperties.class})
class PlatformAdapterConfiguration {

    @Bean
    @ConditionalOnProperty("gateway.platform.introspection.uri")
    ApiKeyIntrospector modulithApiKeyIntrospector(IntrospectionProperties properties) {
        return new ModulithApiKeyIntrospector(properties);
    }

    @Bean
    @ConditionalOnProperty("gateway.platform.audit.uri")
    AuditSink modulithAuditSink(EdgeAuditProperties properties) {
        return new ModulithAuditSink(properties);
    }

    @Bean
    @ConditionalOnProperty("gateway.platform.quota.uri")
    QuotaProvider modulithQuotaProvider(EdgeQuotaProperties properties) {
        return new ModulithQuotaProvider(properties);
    }
}
