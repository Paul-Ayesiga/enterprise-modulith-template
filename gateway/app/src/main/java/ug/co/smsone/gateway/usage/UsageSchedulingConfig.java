package ug.co.smsone.gateway.usage;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The gateway's only scheduled work is the usage flush; scoped here so the intent stays visible. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class UsageSchedulingConfig {
}
