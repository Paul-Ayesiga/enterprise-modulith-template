package ug.co.smsone.gateway.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and how to reach the platform's quota endpoint ({@code gateway.platform.quota.*}). When {@code
 * uri} is unset the provider is not created and quotas are simply not enforced at the edge.
 */
@ConfigurationProperties("gateway.platform.quota")
public record EdgeQuotaProperties(String uri) {
}
