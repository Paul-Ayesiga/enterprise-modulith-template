package ug.co.smsone.scheduler.internal;

import io.swagger.v3.oas.annotations.Operation;
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
 * once across the fleet. The floor is {@code platform-support}: reading lock state is the
 * ops-investigation job. There is no trigger endpoint (jobs are time-driven by design).
 *
 * <p>The {@code JdbcTemplate} read stays inline deliberately: {@code shedlock} is a framework-owned
 * table with no entity and no domain service to delegate to — a service holding one two-column
 * SELECT would be ceremony, not a boundary.
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
    @Operation(summary = "List scheduled-job lock state across the fleet",
            description = """
                    One row per scheduled job, showing which instance holds (or last held) its lock \
                    and until when — the evidence that a job fires once cluster-wide. Read-only: there \
                    is no endpoint to trigger a job, they are time-driven by design.""")
    @PreAuthorize("hasRole('platform-support')")
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
