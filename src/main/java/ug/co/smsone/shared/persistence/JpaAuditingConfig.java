package ug.co.smsone.shared.persistence;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import ug.co.smsone.shared.security.CurrentUserProvider;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /**
     * Audits as the caller's {@code person.id}, and as NOTHING when no person is behind the write.
     *
     * <p>{@code created_by}/{@code updated_by} are {@code uuid} holding {@code person.id}, and V10 is
     * explicit that NULL means a non-person actor — a scheduled job, a startup task, a machine key. So
     * the old {@code "system"} sentinel is gone rather than translated: there is no system person row to
     * point at, and minting one would put a robot in the directory, eligible for a membership and for a
     * GDPR erasure request. An empty {@code Optional} leaves the column null, which is the schema's own
     * way of saying the same thing the sentinel was trying to say.
     *
     * <p>The EFFECTIVE person, not the accountable one: an impersonated write lands in the target's
     * history exactly as their own would, and {@code audit_log.actor} is the single place that records
     * which operator was answerable ({@code CurrentUser.accountablePersonId()}).
     *
     * <p>This runs inside a Hibernate flush, so it must not be the call that triggers resolution —
     * {@code CurrentUserFilter} resolves the caller at the edge for exactly this reason, and off a
     * request thread there is no authentication to resolve at all.
     */
    @Bean
    AuditorAware<UUID> auditorProvider(CurrentUserProvider currentUserProvider) {
        return currentUserProvider::currentPersonId;
    }

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant());
    }
}
