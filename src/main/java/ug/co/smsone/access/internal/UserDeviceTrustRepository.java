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
     * <p>Native and spelled out because {@code @SQLRestriction} does not reach native SQL, and the
     * {@code deleted_at is null} on {@code user_device} is load-bearing: revoking a device is a soft
     * delete, and a revoked device whose grant row still existed would otherwise keep passing the
     * policy. The grant row is hard-deleted on revoke and cascades on a device hard-delete, so the
     * only stale case is exactly this one.
     *
     * <p>Reads through the (person_id, fingerprint) index on user_device, then the
     * user_device_trust primary key — the request-path cost of the whole check is two index probes.
     */
    @Query(value = """
            select exists(
                select 1 from user_device_trust t
                join user_device d on d.id = t.device_id
                where t.org_id = :orgId
                  and d.person_id = :personId
                  and d.fingerprint = :fingerprint
                  and d.deleted_at is null)
            """, nativeQuery = true)
    boolean isTrusted(@Param("orgId") UUID orgId, @Param("personId") UUID personId,
            @Param("fingerprint") String fingerprint);

    /** Revocation. Idempotent by nature: revoking a grant that is not there is already the goal. */
    @Modifying
    @Query("delete from UserDeviceTrust t where t.id.deviceId = :deviceId and t.id.orgId = :orgId")
    int revoke(@Param("deviceId") UUID deviceId, @Param("orgId") UUID orgId);

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
