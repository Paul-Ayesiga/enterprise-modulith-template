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
     * The windows in effect for one ORG — its own, never the platform's.
     *
     * <p>This used to be one query with {@code (w.orgId is null or w.orgId = :orgId)} in it, and ADR
     * 0010 Phase 2 is what split it in two. {@code maintenance_window} exists in both schemas now
     * (§2 row 25): the platform-wide windows are in {@code platform} and each tenant's own are in that
     * tenant's schema, so the {@code or} could no longer be an {@code or} — one query cannot span two
     * schemas, and after Phase 7 it cannot span two databases either. The union happens in
     * {@link MaintenanceService#activeFor} instead, from two reads.
     *
     * <p>Unqualified, so the {@code search_path} resolves it to the caller's tenant — which is the only
     * form that keeps working when that tenant is promoted to a schema of its own.
     */
    @Query("select w from MaintenanceWindow w where w.startsAt <= :now and w.endsAt > :now "
            + "and w.orgId = :orgId")
    List<MaintenanceWindow> activeForOrg(@Param("now") Instant now, @Param("orgId") UUID orgId);

    /**
     * The PLATFORM-WIDE windows in effect — {@code org_id is null}, the platform's own announcement or
     * restriction, read on every org request regardless of which tenant is calling (ADR 0010 §2 row 25).
     *
     * <p>NATIVE, and schema-qualified, and that is the whole point: this is read while the connection is
     * on a TENANT's {@code search_path}, so an unqualified name would resolve to that tenant's copy of
     * the table and the platform's windows would be invisible to every tenant — a RESTRICT window that
     * silently stops restricting anything, which is the failure mode nobody would notice until a
     * maintenance run wrote to a live database.
     *
     * <p>Two things a native query does not inherit from the entity, both spelled out here because
     * their absence is silent: {@code @SQLRestriction("deleted_at is null")} is not applied (so a
     * cancelled window would come back from the dead), and neither is any {@code @Where}-style ordering.
     * The {@code org_id is null} predicate is what makes this the platform HALF rather than the whole
     * table — {@code platform.maintenance_window} also holds nothing else, but stating it keeps the
     * query honest if a row ever lands there with an org.
     */
    @Query(nativeQuery = true, value = """
            select * from platform.maintenance_window
             where deleted_at is null
               and org_id is null
               and starts_at <= :now
               and ends_at > :now
            """)
    List<MaintenanceWindow> activePlatformWide(@Param("now") Instant now);

    List<MaintenanceWindow> findByOrgIdOrderByStartsAtDesc(UUID orgId);

    List<MaintenanceWindow> findByOrgIdIsNullOrderByStartsAtDesc();
}
