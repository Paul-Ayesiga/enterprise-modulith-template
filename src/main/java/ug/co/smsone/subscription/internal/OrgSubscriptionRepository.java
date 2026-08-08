package ug.co.smsone.subscription.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrgSubscriptionRepository extends JpaRepository<OrgSubscription, UUID> {

    Optional<OrgSubscription> findByOrgId(UUID orgId);

    java.util.List<OrgSubscription> findByStatusAndUpdatedAtBefore(OrgSubscription.Status status,
            java.time.Instant cutoff);

    /** Is any organization on this plan? Guards a plan delete against orphaning subscriptions. */
    boolean existsByPlanId(UUID planId);

    /**
     * Which organizations a plan edit re-gates — the ids only, because the caller wants cache keys and
     * not entities. Excludes the orgs with no subscription row at all, which run on FREE; editing FREE
     * therefore takes the clear-everything path rather than this one (see
     * {@code SubscriptionService.evictEntitlementsOfOrgsOn}).
     */
    @Query("select s.orgId from OrgSubscription s where s.planId = :planId")
    List<UUID> orgIdsByPlanId(@Param("planId") UUID planId);

    /** Live TRIALING subscriptions whose trial has lapsed (index-backed) — the expiry scan. */
    List<OrgSubscription> findByStatusAndTrialEndsAtBefore(OrgSubscription.Status status, Instant cutoff);

    /** Just the standing for an org, for the read-only gate — no entity hydration on the hot path. */
    @Query("select s.status from OrgSubscription s where s.orgId = :orgId")
    Optional<OrgSubscription.Status> statusOf(@Param("orgId") UUID orgId);
}
