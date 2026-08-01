package ug.co.smsone.exchange.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/**
 * A recurring EXPORT (imports cannot recur — there is no source to re-read): fire the handler on a
 * cron, as the requester who created the schedule. User-managed configuration, so it is a
 * soft-deletable aggregate like {@code webhook_subscription}.
 */
@Entity
@Table(name = "exchange_schedule")
@SQLDelete(sql = "update exchange_schedule set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class ExchangeSchedule extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(nullable = false, updatable = false, length = 64)
    private String requester;

    @Column(nullable = false, updatable = false, length = 60)
    private String handler;

    @Column(nullable = false, updatable = false, length = 10)
    private String format;

    @Column(nullable = false, length = 120)
    private String cron;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_job_id")
    private UUID lastJobId;

    protected ExchangeSchedule() {
        // JPA
    }

    static ExchangeSchedule create(UUID orgId, String requester, String handler, String format,
            String cron, Instant firstRunAt) {
        ExchangeSchedule schedule = new ExchangeSchedule();
        schedule.orgId = orgId;
        schedule.requester = requester;
        schedule.handler = handler;
        schedule.format = format;
        schedule.cron = cron;
        schedule.enabled = true;
        schedule.nextRunAt = firstRunAt;
        return schedule;
    }

    void fired(UUID jobId, Instant nextRunAt) {
        this.lastJobId = jobId;
        this.nextRunAt = nextRunAt;
    }

    /** A requester who lost the handler permission stops firing LOUDLY, not silently forever. */
    void disable() {
        this.enabled = false;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getRequester() {
        return requester;
    }

    String getHandler() {
        return handler;
    }

    String getFormat() {
        return format;
    }

    String getCron() {
        return cron;
    }

    boolean isEnabled() {
        return enabled;
    }

    Instant getNextRunAt() {
        return nextRunAt;
    }

    UUID getLastJobId() {
        return lastJobId;
    }
}
