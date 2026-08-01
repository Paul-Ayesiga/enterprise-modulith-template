package ug.co.smsone.shared.retention;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A no-op {@link RetentionOverrides} fallback — "no overrides", so every org stays on the platform
 * default — so any module whose retention job consults the port still boots when the scheduler
 * module (the real owner) isn't on the context, notably Modulith single-module slice tests. The
 * real impl is {@code @Primary}, so this is used only in its absence.
 */
@Configuration(proxyBeanMethods = false)
class RetentionOverridesConfiguration {

    @Bean
    @ConditionalOnMissingBean(RetentionOverrides.class)
    RetentionOverrides noOpRetentionOverrides() {
        return scope -> Map.<UUID, Integer>of();
    }
}
