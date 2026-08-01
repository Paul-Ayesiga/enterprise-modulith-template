package ug.co.smsone.gateway.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ug.co.smsone.gateway.core.security.ApiKeyIntrospector;

/**
 * Wires the platform adapters. The API-key introspector exists only when an introspection endpoint is
 * configured ({@code gateway.platform.introspection.uri}) — absent, the gateway simply does not offer
 * API-key auth, and the core is none the wiser.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntrospectionProperties.class)
class PlatformAdapterConfiguration {

    @Bean
    @ConditionalOnProperty("gateway.platform.introspection.uri")
    ApiKeyIntrospector modulithApiKeyIntrospector(IntrospectionProperties properties) {
        return new ModulithApiKeyIntrospector(properties);
    }
}
