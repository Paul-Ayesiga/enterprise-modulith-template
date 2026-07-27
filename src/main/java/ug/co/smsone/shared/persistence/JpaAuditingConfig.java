package ug.co.smsone.shared.persistence;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import ug.co.smsone.shared.security.CurrentUser;
import ug.co.smsone.shared.security.CurrentUserProvider;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    private static final String SYSTEM_AUDITOR = "system";

    /** Audits as the authenticated username; "system" outside a request (jobs, startup). */
    @Bean
    AuditorAware<String> auditorProvider(CurrentUserProvider currentUserProvider) {
        return () -> Optional.of(currentUserProvider.currentUser()
                .map(CurrentUser::username)
                .orElse(SYSTEM_AUDITOR));
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
