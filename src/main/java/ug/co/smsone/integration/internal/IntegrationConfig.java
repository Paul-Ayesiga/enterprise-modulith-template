package ug.co.smsone.integration.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationProperties.class)
class IntegrationConfig {
}
