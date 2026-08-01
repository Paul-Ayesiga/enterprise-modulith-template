package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The load-bearing compliance guarantee, at the purge itself: an active legal hold on a subject
 * keeps their aged-out soft-deleted rows from being HARD-deleted, however far past retention; once
 * the hold is released, the same purge finally clears them. Lives here because the purge job is
 * package-private; the hold row is seeded by raw SQL (compliance owns the table, the purge reaches
 * it the same cross-cutting way it reaches every module's tables).
 */
class LegalHoldPurgeTest extends AbstractIntegrationTest {

    @Autowired
    private SoftDeletePurgeJob purgeJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aHeldSubjectSurvivesThePurgeUntilReleased() {
        String subject = "hold-purge-" + UUID.randomUUID();
        // A soft-deleted user, aged well past the retention window so the purge WOULD hard-delete it.
        jdbc.update("insert into app_user (id, subject, email, status, provisioned_at, version, created_at, deleted_at) "
                + "values (?, ?, ?, 'ACTIVE', now(), 0, now(), now() - interval '400 days')",
                UUID.randomUUID(), subject, subject + "@smsone.co.ug");

        UUID holdId = UUID.randomUUID();
        jdbc.update("insert into legal_hold (id, scope, subject, reason, placed_by, placed_at) "
                + "values (?, 'SUBJECT', ?, 'hold', 'tester', now())", holdId, subject);

        purge();
        assertThat(exists(subject)).as("a held subject's aged row survives the purge").isTrue();

        // Release the hold; the next purge clears it.
        jdbc.update("update legal_hold set released_at = now(), released_by = 'tester' where id = ?", holdId);
        purge();
        assertThat(exists(subject)).as("released, the aged row is finally hard-deleted").isFalse();
    }

    /** ShedLock intercepts direct calls too — free the lock before each in-test invocation. */
    private void purge() {
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "soft-delete-purge");
        purgeJob.purgeExpiredSoftDeletes();
    }

    private boolean exists(String subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from app_user where subject = ?)", Boolean.class, subject));
    }
}
