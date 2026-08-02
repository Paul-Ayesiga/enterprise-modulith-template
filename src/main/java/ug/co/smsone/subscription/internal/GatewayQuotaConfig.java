package ug.co.smsone.subscription.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link GatewayQuotaProperties} for the edge quota seam. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayQuotaProperties.class)
class GatewayQuotaConfig {
}
