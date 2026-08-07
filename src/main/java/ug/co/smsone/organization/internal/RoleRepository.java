package ug.co.smsone.organization.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RoleRepository extends JpaRepository<Role, UUID>, JpaSpecificationExecutor<Role> {

    Optional<Role> findByOrgIdAndCode(UUID orgId, String code);

    List<Role> findByOrgId(UUID orgId);

    /**
     * id → code pairs only: the member listing needs the map, not N EAGER permission collections —
     * one two-column query instead of 1+N secondary selects per page.
     */
    @Query("select r.id as id, r.code as code from Role r where r.orgId = :orgId")
    List<RoleCode> codesByOrgId(@Param("orgId") UUID orgId);

    /**
     * {@link #codesByOrgId} narrowed to named ids — the same two-column projection for renderers whose
     * rows span MANY organizations, where "every role of the org" is both the wrong question and a
     * larger fetch. {@code GET /me/organizations} is that case: one caller, N tenants, N role ids, and
     * it used to ask each tenant for its whole role catalogue in turn.
     */
    @Query("select r.id as id, r.code as code from Role r where r.id in :ids")
    List<RoleCode> codesByIds(@Param("ids") Collection<UUID> ids);

    /**
     * The id → code map every listing renders with. It lives here, not in each caller, because the
     * pattern this repository exists to prevent is a second one growing next to it: the group listing
     * had already regressed to a {@code findById} per row while the member listing beside it used the
     * map. One mechanism, two ways to name the rows you want.
     */
    default Map<UUID, String> codeMapByOrgId(UUID orgId) {
        return toCodeMap(codesByOrgId(orgId));
    }

    /** {@link #codeMapByOrgId} for a known set of role ids; an empty set spends no query. */
    default Map<UUID, String> codeMapByIds(Collection<UUID> ids) {
        return ids.isEmpty() ? Map.of() : toCodeMap(codesByIds(ids));
    }

    private static Map<UUID, String> toCodeMap(List<RoleCode> codes) {
        Map<UUID, String> map = new LinkedHashMap<>();
        codes.forEach(code -> map.put(code.getId(), code.getCode()));
        // An id ABSENT from the map is a soft-deleted role — @SQLRestriction hides it from this query
        // exactly as it hid it from the per-row findById, and both leave the caller rendering a null
        // code. Membership rows outlive their role (the FK is blind to deleted_at), so this is normal.
        return map;
    }

    interface RoleCode {
        UUID getId();

        String getCode();
    }

    /**
     * Shared lock for writers about to reference this role. Soft delete removed the FK's veto — a
     * deleted {@code org_role} row still satisfies {@code membership.role_id} — so the two sides have
     * to agree explicitly. Holding this blocks {@link #lockById} until commit; being blocked BY it
     * means the delete won and the re-read then finds nothing (@SQLRestriction), which is the 404 the
     * caller would have got a moment earlier.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select r from Role r where r.orgId = :orgId and r.code = :code")
    Optional<Role> lockByOrgIdAndCode(@Param("orgId") UUID orgId, @Param("code") String code);

    /** Exclusive counterpart of {@link #lockByOrgIdAndCode}, taken before a role is soft-deleted. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.id = :id")
    Optional<Role> lockById(@Param("id") UUID id);
}
