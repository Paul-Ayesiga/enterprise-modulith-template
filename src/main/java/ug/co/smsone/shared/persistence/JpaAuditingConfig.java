package ug.co.smsone.shared.persistence;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import ug.co.smsone.shared.security.CurrentUserProvider;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    private static final String SYSTEM_AUDITOR = "system";

    /**
     * Audits as the caller's stable SUBJECT; {@code "system"} outside a request (jobs, startup).
     *
     * <p>Deliberately not the username: {@code preferred_username} is mutable and reassignable in
     * Keycloak, so a rename would silently re-point every historical row at whoever holds that name
     * now. Storing the subject also makes {@code created_by}/{@code updated_by} join-compatible with
     * {@code audit_log.actor} and {@code membership.user_subject}, which have always been subjects.
     *
     * <p>The sentinel is a deliberate exception to "this column holds a subject" — it records that
     * there was no principal at all, which is more useful than a null that could also mean "unset".
     */
    @Bean
    AuditorAware<String> auditorProvider(CurrentUserProvider currentUserProvider) {
        return () -> Optional.of(currentUserProvider.currentSubject().orElse(SYSTEM_AUDITOR));
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
