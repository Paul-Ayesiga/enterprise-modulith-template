package ug.co.smsone.identity.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProvisioningProperties.class, ImpersonationProperties.class,
        IdentityReconciliationProperties.class})
class IdentityConfig {
}
