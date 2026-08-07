package ug.co.smsone.access.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ug.co.smsone.shared.web.CursorPageRequest;

interface UserDeviceRepository extends JpaRepository<UserDevice, UUID>, JpaSpecificationExecutor<UserDevice> {

    Optional<UserDevice> findByPersonIdAndFingerprint(UUID personId, String fingerprint);

    Optional<UserDevice> findByIdAndPersonId(UUID id, UUID personId);

    /**
     * Throttled last-seen stamp on a separate connection — the request path must not version-bump.
     *
     * <p>{@code deleted_at is null} is not tidiness, it is the only thing that makes this statement
     * indexable. Every index on {@code user_device} covering {@code person_id} is PARTIAL on
     * {@code where deleted_at is null}, and Postgres will only use a partial index when it can PROVE
     * the query implies the predicate — which it cannot if the query never mentions it. So this
     * statement full-scanned the table on every request carrying an {@code X-Device-Id}, synchronously,
     * before the filter chain continued. Proved by forcing {@code enable_seqscan = off}: the plan
     * STILL chose a sequential scan, marked Disabled — there was no index alternative at all.
     * 6359 cost / 2759 buffers / 16.8 ms became 2.65 / 3 / 0.086 ms with the clause added.
     *
     * <p>It is also a correctness fix. The partial unique index permits any number of REVOKED rows per
     * {@code (person, fingerprint)}, and without this clause the statement stamped {@code last_seen_at}
     * on all of them — writing activity onto devices the person had explicitly revoked.
     *
     * <p>{@code @SQLRestriction} does not reach native queries, which is why it has to be written here.
     */
    @Modifying
    @Query(value = "update user_device set last_seen_at = :now where person_id = :personId "
            + "and fingerprint = :fingerprint and deleted_at is null "
            + "and (last_seen_at is null or last_seen_at < :throttleBefore)",
            nativeQuery = true)
    void touchThrottled(@Param("personId") UUID personId, @Param("fingerprint") String fingerprint,
            @Param("now") Instant now, @Param("throttleBefore") Instant throttleBefore);

    default Window<UserDevice> pageByPersonId(UUID personId, CursorPageRequest page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return findBy((root, query, cb) -> cb.equal(root.get("personId"), personId),
                q -> q.limit(page.size()).sortBy(sort).scroll(page.scrollPosition(sort)));
    }
}
