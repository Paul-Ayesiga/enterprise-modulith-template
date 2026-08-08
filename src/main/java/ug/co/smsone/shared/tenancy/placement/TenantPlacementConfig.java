package ug.co.smsone.shared.tenancy.placement;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.tenancy.placement.*} for {@link TenantProvisioner}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantPlacementProperties.class)
class TenantPlacementConfig {
}
