package ug.co.smsone.subscription.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The shared secret the API gateway presents when asking for a consumer's quota ({@code
 * app.gateway.quota-secret}); it must match the gateway's {@code gateway.platform.quota.secret}. Bound
 * separately so the quota seam stays owned by the subscription module.
 */
@ConfigurationProperties("app.gateway")
record GatewayQuotaProperties(String quotaSecret) {
}
