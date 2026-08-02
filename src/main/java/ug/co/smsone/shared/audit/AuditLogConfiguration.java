package ug.co.smsone.shared.audit;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A no-op {@link AuditLog} fallback so any module that audits still boots when the audit module isn't
 * on the context — notably Modulith single-module slice tests. The real recorder (audit module) is
 * {@code @Primary}, so this is used only in its absence.
 */
@Configuration(proxyBeanMethods = false)
class AuditLogConfiguration {

    @Bean
    @ConditionalOnMissingBean(AuditLog.class)
    AuditLog noOpAuditLog() {
        return new AuditLog() {
            @Override
            public void record(String action, UUID orgId, String target, String fromState, String toState) {
                // no-op: no audit module on this context
            }

            @Override
            public void recordExternal(String action, UUID orgId, String actor, String target,
                    String fromState, String toState) {
                // no-op: no audit module on this context
            }
        };
    }
}
