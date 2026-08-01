package ug.co.smsone.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ug.co.smsone.settings.internal.Setting;
import ug.co.smsone.settings.internal.SettingService;
import ug.co.smsone.shared.error.ConflictException;
import ug.co.smsone.shared.error.NotFoundException;
import ug.co.smsone.shared.web.CursorPageRequest;
import ug.co.smsone.testsupport.AbstractIntegrationTest;

/**
 * The soft-delete mechanics against REAL Postgres: {@code @SQLDelete} turns a delete into an update,
 * {@code @SQLRestriction} hides the result from every JPA read path, and the partial unique index frees
 * the key for reuse.
 *
 * <p>{@link Setting} stands in for all seven soft-deletable aggregates: its entity type is one of the
 * few visible outside its own module, and the annotations under test are identical on each. That
 * sameness is not assumed here — {@code ArchitectureTests} pins it for every entity, because Hibernate
 * inherits neither annotation from the mapped superclass. The module-specific consequences — RBAC
 * revocation, member listings, role deletability, the audit trail outliving its subject — live in the
 * tests of the modules that own them.
 *
 * <p>Reached through {@code JpaRepository<Setting, UUID>} rather than {@code SettingRepository}, which
 * is package-private: the behaviour under test belongs to Spring Data's {@code delete}/finders, not to
 * anything the settings module adds.
 */
class SoftDeleteTest extends AbstractIntegrationTest {

    @Autowired
    private JpaRepository<Setting, UUID> settings;

    @Autowired
    private JpaSpecificationExecutor<Setting> settingSearch; // same bean, the criteria half of its API

    @Autowired
    private SettingService settingService; // the derived-query and Window paths, as production uses them

    @Autowired
    private JdbcTemplate jdbc; // the only view left that still sees a soft-deleted row

    @Autowired
    private SoftDeleteRecovery recovery; // the one sanctioned way back

    @Autowired
    private PlatformTransactionManager transactionManager; // two overlapping transactions, one row

    private String prefix;
    private String key;

    @BeforeEach
    void freshKeys() {
        prefix = "soft-delete." + UUID.randomUUID() + ".";
        key = prefix + "target";
    }

    @Test
    void deleteStampsTheRowInsteadOfRemovingIt() {
        Setting setting = settings.save(Setting.create(key, "on", "probe"));

        settings.delete(setting);

        var row = jdbc.queryForMap(
                "select setting_key, setting_value, deleted_at from setting where id = ?", setting.getId());
        assertThat(row.get("setting_key")).isEqualTo(key);
        assertThat(row.get("setting_value")).isEqualTo("on"); // nothing is scrubbed, only stamped
        assertThat(row.get("deleted_at")).isNotNull();
    }

    /**
     * One deleted row and one live sibling, so every assertion is non-vacuous: the queries demonstrably
     * reach this test's data and return exactly the survivor.
     */
    @Test
    void everyJpaReadPathStopsSeeingADeletedRow() {
        Setting deleted = settings.save(Setting.create(key, "on", null));
        Setting survivor = settings.save(Setting.create(prefix + "survivor", "on", null));

        settings.delete(deleted);

        assertThat(settings.findById(deleted.getId())).isEmpty();
        assertThat(settings.existsById(deleted.getId())).isFalse();
        assertThat(settings.findAllById(List.of(deleted.getId(), survivor.getId())))
                .extracting(Setting::getId).containsExactly(survivor.getId());
        assertThat(settings.findAll()).extracting(Setting::getId).doesNotContain(deleted.getId());

        // Derived query (findByKey), reached through the service that owns it.
        assertThatThrownBy(() -> settingService.require(key)).isInstanceOf(NotFoundException.class);
        assertThat(settingService.valueOf(key)).isNull();

        // Criteria + counts: a deleted row must not inflate a total either.
        assertThat(settingSearch.findAll(underTestPrefix())).extracting(Setting::getId)
                .containsExactly(survivor.getId());
        assertThat(settingSearch.count(underTestPrefix())).isEqualTo(1);

        // Keyset-scrolled Window, the production listing path. Newest first, so both rows are on page 1.
        assertThat(settingService.list(new CursorPageRequest(CursorPageRequest.MAX_SIZE, null)).getContent())
                .extracting(Setting::getId)
                .contains(survivor.getId())
                .doesNotContain(deleted.getId());
    }

    /**
     * The whole reason V17 replaced every unique constraint with a partial index: a soft-deleted row
     * still occupies its key, so without {@code where deleted_at is null} deleting a setting would
     * permanently forbid ever creating that key again.
     */
    @Test
    void aDeletedKeyIsFreeToUseAgain() {
        Setting first = settings.save(Setting.create(key, "one", null));
        settings.delete(first);

        Setting second = settings.save(Setting.create(key, "two", null));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(settingService.require(key).getValue()).isEqualTo("two");
        // Both rows are in the table; only one of them is live.
        assertThat(jdbc.queryForObject("select count(*) from setting where setting_key = ?", Integer.class, key))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from setting where setting_key = ? and deleted_at is null", Integer.class, key))
                .isEqualTo(1);
    }

    /**
     * Deleting a stale instance must fail like any other stale write. The row stays live and the
     * concurrent update stands — a soft delete that silently won would be an unnoticed data loss,
     * because there is no missing row to notice.
     */
    @Test
    void deletingAStaleInstanceFailsInsteadOfSilentlyWinning() {
        Setting stale = settings.save(Setting.create(key, "v1", null)); // snapshot at version 0

        settingService.put(key, "v2", null); // someone else writes; the row moves to version 1

        assertThatThrownBy(() -> settings.delete(stale))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(deletedAt(stale.getId())).isNull();
        assertThat(settingService.require(key).getValue()).isEqualTo("v2");
    }

    /**
     * The other direction, and the one a soft delete quietly loses. A hard DELETE dooms every instance
     * loaded before it — there is no row left to update. A soft delete leaves the row in place, so
     * without {@code version = version + 1} the older instance still matches {@code where version = ?}
     * and its flush writes its own in-memory {@code deletedAt} (null) back over the stamp. Nothing is
     * missing afterwards, so nothing looks wrong: the record is simply alive again.
     */
    @Test
    void aConcurrentUpdateCannotResurrectADeletedRow() {
        UUID id = settings.save(Setting.create(key, "v1", null)).getId();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> outer.executeWithoutResult(tx -> {
            Setting mine = settings.findById(id).orElseThrow(); // loaded BEFORE the delete, at version 0
            // Its own connection and persistence context, committed while the outer one is still open.
            inner.executeWithoutResult(other -> settings.delete(settings.findById(id).orElseThrow()));
            mine.change("v2", null);
            settings.saveAndFlush(mine);
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(deletedAt(id)).as("the delete stands").isNotNull();
        assertThat(jdbc.queryForObject("select setting_value from setting where id = ?", String.class, id))
                .isEqualTo("v1");
    }

    /** The in-model transition and the {@code @SQLDelete} path converge on the same hidden row. */
    @Test
    void markDeletedHidesTheRowThroughTheSameRestriction() {
        Setting setting = settings.save(Setting.create(key, "on", null));

        setting.markDeleted(Instant.now());
        settings.save(setting); // an ordinary UPDATE — never reaches @SQLDelete

        assertThat(settings.findById(setting.getId())).isEmpty();
        assertThat(deletedAt(setting.getId())).isNotNull();
    }

    @Test
    void deletingTwiceIsRejected() {
        Setting setting = Setting.create(key, "on", null);
        setting.markDeleted(Instant.now());

        assertThatThrownBy(() -> setting.markDeleted(Instant.now()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already deleted");
    }

    @Test
    void restoringALiveRecordIsRejected() {
        Setting setting = Setting.create(key, "on", null);

        assertThatThrownBy(setting::restore)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not deleted");
    }

    @Test
    void restoreClearsTheDeletionStamp() {
        Setting setting = Setting.create(key, "on", null);
        Instant when = Instant.now();

        setting.markDeleted(when);
        assertThat(setting.isDeleted()).isTrue();
        assertThat(setting.getDeletedAt()).isEqualTo(when);

        setting.restore();
        assertThat(setting.isDeleted()).isFalse();
        assertThat(setting.getDeletedAt()).isNull();
    }

    /**
     * The round trip the in-model transition above cannot prove: that test operates on a TRANSIENT
     * entity, which is exactly how {@code restore()} stayed unreachable for persisted rows unnoticed —
     * no repository can load a deleted row to call it on.
     */
    @Test
    void aDeletedRowCanBeFoundAgainAndRestored() {
        Setting setting = settings.save(Setting.create(key, "on", null));
        UUID id = setting.getId();
        settings.delete(setting);
        assertThat(settings.findById(id)).as("hidden from every ordinary read path").isEmpty();

        Setting deleted = recovery.findDeleted(Setting.class, id).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();

        recovery.restore(deleted);

        assertThat(settings.findById(id)).as("visible again through the ordinary path").isPresent();
        assertThat(deletedAt(id)).isNull();
    }

    /** A live row is not "recoverable" — returning it would make a no-op restore look like a success. */
    @Test
    void findDeletedIgnoresLiveRows() {
        Setting live = settings.save(Setting.create(key, "on", null));

        assertThat(recovery.findDeleted(Setting.class, live.getId())).isEmpty();
        assertThat(recovery.findDeleted(Setting.class, UUID.randomUUID())).isEmpty();
    }

    /**
     * The trap the partial unique index creates: while a row is deleted its key is FREE, so someone can
     * take it. Restoring then collides, and the caller — not this seam — has to decide what that means.
     */
    @Test
    void restoringOntoAKeyTakenWhileDeletedIsRejected() {
        Setting original = settings.save(Setting.create(key, "first", null));
        UUID id = original.getId();
        settings.delete(original);

        settings.save(Setting.create(key, "second", null)); // the key was free, so this is legal

        Setting deleted = recovery.findDeleted(Setting.class, id).orElseThrow();
        assertThatThrownBy(() -> recovery.restore(deleted))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private Specification<Setting> underTestPrefix() {
        return (root, query, cb) -> cb.like(root.get("key"), prefix + "%");
    }

    private Timestamp deletedAt(UUID id) {
        return jdbc.queryForObject("select deleted_at from setting where id = ?", Timestamp.class, id);
    }
}
