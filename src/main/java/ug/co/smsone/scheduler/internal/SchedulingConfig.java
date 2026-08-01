package ug.co.smsone.scheduler.internal;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import ug.co.smsone.shared.persistence.SoftDeleteProperties;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", defaultLockAtLeastFor = "PT30S")
// The soft-delete retention policy is declared with the mapping it belongs to (shared/persistence);
// the scheduler is only the module that acts on it. The event/inbox retention record is the
// scheduler's own — its jobs are the only consumers.
@EnableConfigurationProperties({SoftDeleteProperties.class, SchedulerRetentionProperties.class})
class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                // DB-server UTC time — instances need no clock agreement
                .usingDbTime()
                .build());
    }
}
