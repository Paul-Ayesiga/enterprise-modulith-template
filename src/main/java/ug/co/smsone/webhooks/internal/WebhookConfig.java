package ug.co.smsone.webhooks.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookConfig {
}
