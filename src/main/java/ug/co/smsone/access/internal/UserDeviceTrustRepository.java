package ug.co.smsone.access.internal;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserDeviceTrustRepository extends JpaRepository<UserDeviceTrust, UserDeviceTrust.Key> {

    /**
     * The enforcement question, asked once per request on an org that requires trusted devices: does
     * THIS org trust the live device this person presented?
     *
     * <p><b>ONE TABLE, AND THE MISSING PREDICATE IS THE POINT.</b> This used to join {@code user_device}
     * for {@code person_id}, {@code fingerprint} and — load-bearing — {@code deleted_at is null}, since
     * revoking a device is a soft delete and the surviving row is what stopped its grant from continuing
     * to pass the policy. V53 cut that join: {@code user_device} is PLATFORM-tier and this table is
     * TENANT-tier (ADR 0010 §2), the foreign key between them could not survive the boundary, and a join
     * cannot survive the org moving to its own database either.
     *
     * <p>So the liveness check is gone and <b>the invariant moved into the row's existence</b>: a grant
     * exists only while its device does. Three things maintain that, and all three are required —
     * V53 §2 spells them out. (1) V53's backfill DELETED every grant whose device was already revoked,
     * because denormalizing those would have promoted rows the join was suppressing into live trust.
     * (2) {@link #revokeEverywhere} runs off {@code access.DeviceRevoked}. (3)
     * {@code SoftDeletePurgeJob}'s {@code CASCADES} entry reconciles whatever (2) never saw — notably
     * the compliance erasure path, which soft-deletes devices by raw SQL and publishes nothing.
     *
     * <p>The residual exposure, stated so nobody has to rediscover it: the grant outlives the revocation
     * by the async listener's latency. What it can never do is outlive it indefinitely — which is what
     * a stale row WOULD do, because {@code uq_user_device_person_fingerprint_live} is partial on
     * {@code deleted_at is null}, so re-registering the same fingerprint mints a new device row and a
     * surviving grant would vouch for a device this org has never blessed.
     *
     * <p>Still native, and still {@code exists(…)}: it stops at the first row and reads as an Index Only
     * Scan on {@code idx_user_device_trust_enforcement}, so the grant row itself is never visited.
     */
    @Query(value = """
            select exists(
                select 1 from user_device_trust t
                where t.org_id = :orgId
                  and t.person_id = :personId
                  and t.fingerprint = :fingerprint)
            """, nativeQuery = true)
    boolean isTrusted(@Param("orgId") UUID orgId, @Param("personId") UUID personId,
            @Param("fingerprint") String fingerprint);

    /** Revocation. Idempotent by nature: revoking a grant that is not there is already the goal. */
    @Modifying
    @Query("delete from UserDeviceTrust t where t.id.deviceId = :deviceId and t.id.orgId = :orgId")
    int revoke(@Param("deviceId") UUID deviceId, @Param("orgId") UUID orgId);

    /**
     * Every organization's grant over one device, gone. Named apart from {@link #revoke} because it is
     * a CROSS-TENANT write and must read as one at the call site: {@code revoke} is an org withdrawing
     * its own trust, this is the platform deleting other people's rows because the device underneath
     * them no longer exists.
     *
     * <p>Only two callers may exist — the {@code DeviceRevoked} listener and the purge reconciler — and
     * neither is acting for a tenant. A tenant surface calling this would be revoking trust on behalf
     * of organizations it has nothing to do with.
     */
    @Modifying
    @Query("delete from UserDeviceTrust t where t.id.deviceId = :deviceId")
    int revokeEverywhere(@Param("deviceId") UUID deviceId);

    boolean existsByIdDeviceIdAndIdOrgId(UUID deviceId, UUID orgId);

    /**
     * Which of these devices does this org trust? One query for a whole page — the listing renders a
     * per-device {@code trusted} flag, and asking per row would be an N+1 on a paginated endpoint.
     */
    @Query("select t.id.deviceId from UserDeviceTrust t "
            + "where t.id.orgId = :orgId and t.id.deviceId in :deviceIds")
    Set<UUID> trustedAmong(@Param("orgId") UUID orgId, @Param("deviceIds") Collection<UUID> deviceIds);

    /**
     * Which of these devices does ANY organization trust? For the platform-support listing only, which
     * has no org in context — a different question from {@link #trustedAmong}, and named so it cannot
     * be mistaken for it. A tenant surface must never call this: "someone trusts it" is precisely the
     * cross-tenant answer V51 exists to stop being given.
     */
    @Query("select distinct t.id.deviceId from UserDeviceTrust t where t.id.deviceId in :deviceIds")
    Set<UUID> trustedByAnyOrgAmong(@Param("deviceIds") Collection<UUID> deviceIds);
}
