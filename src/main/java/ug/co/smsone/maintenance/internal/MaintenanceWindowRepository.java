package ug.co.smsone.maintenance.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MaintenanceWindowRepository extends JpaRepository<MaintenanceWindow, UUID> {

    Optional<MaintenanceWindow> findByIdAndOrgId(UUID id, UUID orgId);

    Optional<MaintenanceWindow> findByIdAndOrgIdIsNull(UUID id);

    /**
     * Windows currently in effect for a scope: platform-wide OR this org. Used by the enforcement
     * filter (cache-friendly: few rows, indexed on the time bounds).
     */
    @Query("select w from MaintenanceWindow w where w.startsAt <= :now and w.endsAt > :now "
            + "and (w.orgId is null or w.orgId = :orgId)")
    List<MaintenanceWindow> activeFor(@Param("now") Instant now, @Param("orgId") UUID orgId);

    List<MaintenanceWindow> findByOrgIdOrderByStartsAtDesc(UUID orgId);

    List<MaintenanceWindow> findByOrgIdIsNullOrderByStartsAtDesc();
}
