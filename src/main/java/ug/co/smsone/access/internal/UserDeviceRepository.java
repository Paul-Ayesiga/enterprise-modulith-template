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

    /** Throttled last-seen stamp on a separate connection — the request path must not version-bump. */
    @Modifying
    @Query(value = "update user_device set last_seen_at = :now where person_id = :personId "
            + "and fingerprint = :fingerprint and (last_seen_at is null or last_seen_at < :throttleBefore)",
            nativeQuery = true)
    void touchThrottled(@Param("personId") UUID personId, @Param("fingerprint") String fingerprint,
            @Param("now") Instant now, @Param("throttleBefore") Instant throttleBefore);

    @Query(value = "select exists(select 1 from user_device where person_id = :personId "
            + "and fingerprint = :fingerprint and trusted = true and deleted_at is null)", nativeQuery = true)
    boolean isTrusted(@Param("personId") UUID personId, @Param("fingerprint") String fingerprint);

    default Window<UserDevice> pageByPersonId(UUID personId, CursorPageRequest page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return findBy((root, query, cb) -> cb.equal(root.get("personId"), personId),
                q -> q.limit(page.size()).sortBy(sort).scroll(page.scrollPosition(sort)));
    }
}
