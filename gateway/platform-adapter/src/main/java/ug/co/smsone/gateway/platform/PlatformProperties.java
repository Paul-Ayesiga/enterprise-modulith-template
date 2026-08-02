package ug.co.smsone.gateway.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single shared secret the gateway presents on every platform seam (introspection, audit, quota) —
 * {@code gateway.platform.secret}. One trust relationship, one secret; it must match the modulith's
 * {@code app.gateway.secret}. Each seam still has its own {@code uri}; only the secret is shared.
 */
@ConfigurationProperties("gateway.platform")
public record PlatformProperties(String secret) {
}
