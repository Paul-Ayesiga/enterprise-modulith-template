package ug.co.smsone.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Enables @Async — required by @ApplicationModuleListener (Modulith event consumers run async in
 * their own transaction). Executor comes from Boot's task auto-configuration (virtual threads).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {
}
