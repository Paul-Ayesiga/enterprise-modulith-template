package ug.co.smsone.apikeys.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayIntrospectionProperties.class)
class GatewayIntrospectionConfig {
}
