package ug.co.smsone.scheduler.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ug.co.smsone.shared.web.ResourceObject;

/**
 * Read-only observability into clustered scheduling: the ShedLock rows show which job holds (or last
 * held) its lock, when, and on which instance — the proof that {@code @Scheduled} jobs fire exactly
 * once across the fleet. Admin-only; there is no trigger endpoint (jobs are time-driven by design).
 */
@RestController
@RequestMapping("/api/v1/scheduler/locks")
class SchedulerController {

    private static final String RESOURCE_TYPE = "scheduler-lock";

    private final JdbcTemplate jdbc;

    SchedulerController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record LockAttributes(String name, Instant lockUntil, Instant lockedAt, String lockedBy) {
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    List<ResourceObject> list() {
        return jdbc.query(
                "select name, lock_until, locked_at, locked_by from shedlock order by locked_at desc",
                (rs, rowNum) -> new ResourceObject(rs.getString("name"), RESOURCE_TYPE,
                        new LockAttributes(
                                rs.getString("name"),
                                toInstant(rs.getTimestamp("lock_until")),
                                toInstant(rs.getTimestamp("locked_at")),
                                rs.getString("locked_by"))));
    }

    // ShedLock stores UTC wall-clock in a TIMESTAMP (no zone) via usingDbTime(); read it back as UTC.
    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(java.time.ZoneOffset.UTC);
    }
}
