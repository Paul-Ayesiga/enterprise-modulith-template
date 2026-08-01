package ug.co.smsone.gateway.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and how to reach the platform's key-introspection endpoint ({@code
 * gateway.platform.introspection.*}). When {@code uri} is unset the adapter is not created and
 * API-key authentication is simply unavailable at the edge (the bearer path still works).
 */
@ConfigurationProperties("gateway.platform.introspection")
public record IntrospectionProperties(String uri, String secret) {
}
