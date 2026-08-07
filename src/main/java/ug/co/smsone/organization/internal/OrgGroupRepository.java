package ug.co.smsone.organization.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * How many members each group on ONE page has — a projection, deliberately, so the listing can say
     * "12 members" without loading twelve rows twelve groups over (see {@link OrgGroup#getMembers()}).
     * Derivation cannot express an aggregate over an {@code @ElementCollection}, hence {@code @Query}.
     *
     * <p>{@code left join} is load-bearing: an inner join would silently drop empty groups from the
     * page they were listed on, and a group with no members yet is the normal state of a freshly
     * created one.
     *
     * <p>Postgres answers it from {@code org_group_member_pkey (group_id, person_id)}: an Index Only
     * Scan per group id, all of it collapsed by one HashAggregate. Note what that means and what it
     * does NOT — the per-id index searches are searches WITHIN a single execution, so the whole page
     * is ONE round trip, and its cost tracks {@code page[size]} rather than the organization's total
     * group membership. Verified against the seeded database: a 20-group page reads 143 buffers.
     */
    @Query("select g.id as id, count(m) as memberCount from OrgGroup g left join g.members m "
            + "where g.id in :ids group by g.id")
    List<GroupMemberCount> memberCounts(@Param("ids") Collection<UUID> ids);

    /** {@link #memberCounts} as the map a renderer wants; an empty page spends no query. */
    default Map<UUID, Long> memberCountMap(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> counts = new LinkedHashMap<>();
        memberCounts(ids).forEach(count -> counts.put(count.getId(), count.getMemberCount()));
        return counts;
    }

    interface GroupMemberCount {
        UUID getId();

        long getMemberCount();
    }

    boolean existsByRoleId(UUID roleId);
}
