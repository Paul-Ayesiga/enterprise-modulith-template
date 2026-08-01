package ug.co.smsone.scheduler.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retention for the two event-plumbing tables the scheduler sweeps. Validated at startup — a
 * non-positive window would purge live registry/dedup state. The inbox window must not undercut the
 * registry's: inbox rows only matter while a publication could still be redelivered, and a
 * publication completed late still redelivers on the restart after it.
 */
@ConfigurationProperties(prefix = "app.scheduler")
record SchedulerRetentionProperties(Duration eventRetention, Duration eventInboxRetention) {

    SchedulerRetentionProperties {
        eventRetention = eventRetention == null ? Duration.ofDays(7) : eventRetention;
        eventInboxRetention = eventInboxRetention == null ? Duration.ofDays(14) : eventInboxRetention;
        // Zero is legitimate ("purge immediately" — tests use it); negative is the startup-fatal
        // misconfiguration, because the cutoff lands in the future and takes everything with it.
        if (eventRetention.isNegative()) {
            throw new IllegalArgumentException("app.scheduler.event-retention must not be negative");
        }
        if (eventInboxRetention.compareTo(eventRetention) < 0) {
            throw new IllegalArgumentException(
                    "app.scheduler.event-inbox-retention must be >= event-retention — dedup must outlive redelivery");
        }
    }
}
