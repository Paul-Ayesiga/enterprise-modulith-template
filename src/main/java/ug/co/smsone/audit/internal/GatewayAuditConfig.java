package ug.co.smsone.audit.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link GatewayAuditProperties} for the edge-audit seam. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayAuditProperties.class)
class GatewayAuditConfig {
}
