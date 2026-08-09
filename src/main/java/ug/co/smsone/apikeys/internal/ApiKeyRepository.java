package ug.co.smsone.apikeys.internal;

import java.util.List;
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

interface ApiKeyRepository extends JpaRepository<ApiKey, UUID>, JpaSpecificationExecutor<ApiKey> {

    Optional<ApiKey> findByPrefix(String prefix);

    Optional<ApiKey> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<ApiKey> findByIdAndOrgIdIsNull(UUID id);

    /**
     * Every LIVE key of one organization — {@code @SQLRestriction("deleted_at is null")} supplies that
     * half, so it is not repeated in the method name.
     *
     * <p>Unpaged on purpose, and it is the one read in this repository that is. ADR 0002's cursor rule
     * exists for collections that reach the wire; this one is the extraction's revoke-them-all step
     * (ADR 0010 §6 item 8), where a page boundary would leave some of a departing tenant's credentials
     * live. The set is bounded by how many keys one org has minted, which is small and operator-driven.
     */
    List<ApiKey> findByOrgIdOrderByCreatedAtAsc(UUID orgId);

    /**
     * Throttled usage stamp on a SEPARATE connection (native, no version bump): the hot auth path
     * must not turn every call into an optimistic-lock write. Only advances when the last stamp is
     * older than the throttle window.
     */
    @Modifying
    @Query(value = "update platform.api_key set last_used_at = :now where id = :id "
            + "and (last_used_at is null or last_used_at < :throttleBefore)", nativeQuery = true)
    void touchThrottled(@Param("id") UUID id, @Param("now") java.time.Instant now,
            @Param("throttleBefore") java.time.Instant throttleBefore);

    default Window<ApiKey> pageByOrg(UUID orgId, CursorPageRequest page) {
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        return findBy(
                (root, query, cb) -> orgId == null ? cb.isNull(root.get("orgId")) : cb.equal(root.get("orgId"), orgId),
                q -> q.limit(page.size()).sortBy(sort).scroll(page.scrollPosition(sort)));
    }
}
