package ug.co.smsone.organization.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
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
