package ug.co.smsone.shared.tenancy.promotion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.tenancy.promotion.*} for {@link TenantPromoter}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TenantPromotionProperties.class)
class TenantPromotionConfig {
}
