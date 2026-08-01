package ug.co.smsone.exchange.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExchangeProperties.class)
class ExchangeConfig {
}
