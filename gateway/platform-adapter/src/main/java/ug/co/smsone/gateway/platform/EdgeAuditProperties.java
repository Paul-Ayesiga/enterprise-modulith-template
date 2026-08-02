package ug.co.smsone.gateway.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and how to reach the platform's edge-audit endpoint ({@code gateway.platform.audit.*}). When
 * {@code uri} is unset the sink is not created and edge auditing is simply off — the security log still
 * records denials locally.
 */
@ConfigurationProperties("gateway.platform.audit")
public record EdgeAuditProperties(String uri, String secret) {
}
