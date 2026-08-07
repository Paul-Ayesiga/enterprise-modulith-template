package ug.co.smsone.scheduler.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import ug.co.smsone.testsupport.AbstractIntegrationTest;
import ug.co.smsone.testsupport.EdgeSeed;

/**
 * The load-bearing compliance guarantee, at the purge itself: an active legal hold on a person keeps
 * their aged-out soft-deleted rows from being HARD-deleted, however far past retention; once the hold
 * is released, the same purge finally clears them. Lives here because the purge job is package-private;
 * the hold row is seeded by raw SQL (compliance owns the table, the purge reaches it the same
 * cross-cutting way it reaches every module's tables).
 *
 * <p>Both sides of the hold moved with the identity decoupling and they have to move together, since a
 * hold that matches nothing fails silently: the held row is a {@code person}, and the hold names it by
 * {@code legal_hold.person_id}. The scope word is still {@code SUBJECT} — that is stored data, not a
 * column, and V34 deliberately left it alone.
 */
class LegalHoldPurgeTest extends AbstractIntegrationTest {

    @Autowired
    private SoftDeletePurgeJob purgeJob;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aHeldSubjectSurvivesThePurgeUntilReleased() {
        // A soft-deleted person, aged well past the retention window so the purge WOULD hard-delete
        // them. Seeded through EdgeSeed so the row (and its provider link) is the shape provisioning
        // leaves behind; only deleted_at is applied afterwards, because no live seed ever sets it.
        UUID personId = EdgeSeed.person(jdbc, "hold-purge-" + UUID.randomUUID());
        jdbc.update("update person set deleted_at = now() - interval '400 days' where id = ?", personId);

        // placed_by_person_id is NOT NULL and is a person id, so the placer is a real person too —
        // the admin surface refuses a hold it cannot attribute to one.
        UUID placedBy = EdgeSeed.person(jdbc, "hold-placer-" + UUID.randomUUID());
        UUID holdId = UUID.randomUUID();
        jdbc.update("insert into legal_hold (id, scope, person_id, reason, placed_by_person_id, placed_at) "
                + "values (?, 'SUBJECT', ?, 'hold', ?, now())", holdId, personId, placedBy);

        purge();
        assertThat(exists(personId)).as("a held subject's aged row survives the purge").isTrue();

        // Release the hold; the next purge clears it.
        jdbc.update("update legal_hold set released_at = now(), released_by_person_id = ? where id = ?",
                placedBy, holdId);
        purge();
        assertThat(exists(personId)).as("released, the aged row is finally hard-deleted").isFalse();
    }

    /** ShedLock intercepts direct calls too — free the lock before each in-test invocation. */
    private void purge() {
        jdbc.update("update shedlock set lock_until = timestamp '1970-01-01 00:00:00' where name = ?",
                "soft-delete-purge");
        purgeJob.purgeExpiredSoftDeletes();
    }

    private boolean exists(UUID personId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from person where id = ?)", Boolean.class, personId));
    }
}
