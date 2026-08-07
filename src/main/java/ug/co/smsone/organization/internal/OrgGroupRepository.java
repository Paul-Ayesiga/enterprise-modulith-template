package ug.co.smsone.organization.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrgGroupRepository extends JpaRepository<OrgGroup, UUID>, JpaSpecificationExecutor<OrgGroup> {

    Optional<OrgGroup> findByIdAndOrgId(UUID id, UUID orgId);

    /** The groups a person belongs to in one org — the resolver's union source. */
    @Query("select g from OrgGroup g join g.members m where g.orgId = :orgId and m = :personId")
    List<OrgGroup> findByOrgIdAndMember(@Param("orgId") UUID orgId, @Param("personId") UUID personId);

    boolean existsByRoleId(UUID roleId);
}
