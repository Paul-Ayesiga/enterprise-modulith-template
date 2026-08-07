package ug.co.smsone.access.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One organization's grant of trust over one device — the row whose PRESENCE is the trust.
 *
 * <p>Replaces {@code user_device.trusted}, which was a single global boolean while
 * {@code org_security_policy.require_trusted_device} is per-org: a device blessed inside org A
 * satisfied org B's rule for the same person. V31's own header recorded that as a known modelling bug
 * and deferred it; V51 is the slice that pays it off.
 *
 * <p>Absence means untrusted, so revocation is a delete and an organization that never granted trust
 * has none. A boolean column has to default to something; a missing row cannot be misread.
 *
 * <p>Deliberately not a {@code BaseEntity}: this is a grant, not a soft-deletable aggregate. It has no
 * version (nothing mutates — you grant or you revoke) and no {@code deleted_at} (a revoked grant must
 * leave nothing behind that could still answer "trusted"). The audit trail lives in {@code audit_log},
 * where {@code access.device_trusted} / {@code access.device_untrusted} already record who changed it.
 *
 * <p><b>{@code personId} and {@code fingerprint} are COPIES of the device's own columns</b>, denormalized
 * by V53 so {@link UserDeviceTrustRepository#isTrusted} can answer without joining {@code user_device}.
 * That join used to cross what ADR 0010 makes a tenant boundary — this table is TENANT-tier, the device
 * is PLATFORM-tier — and a foreign key cannot span two databases. They are immutable because the columns
 * they copy are: {@code UserDevice} exposes no setter for either, {@code fingerprint} is
 * {@code updatable = false}, and {@code person_id} is the row's owner. A device that changes fingerprint
 * is a different device row (V31's partial unique index makes re-registration mint a new one), which is
 * why a stale copy here is not a drift problem but a DELETION problem — see the repository.
 */
@Entity
@Table(name = "user_device_trust")
class UserDeviceTrust {

    @EmbeddedId
    private Key id;

    /** Copy of {@code user_device.person_id} — see the class javadoc. Never updated, only deleted. */
    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    /** Copy of {@code user_device.fingerprint} — the {@code X-Device-Id} the request presents. */
    @Column(name = "fingerprint", nullable = false, updatable = false, length = 100)
    private String fingerprint;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    /** {@code person.id} of whoever blessed it — a soft ref, null for a grant made by the platform. */
    @Column(name = "granted_by_person_id")
    private UUID grantedByPersonId;

    protected UserDeviceTrust() {
    }

    /**
     * Takes the WHOLE device, not a device id, so the copied columns can only ever come from the row
     * they belong to. A factory taking {@code (deviceId, personId, fingerprint)} would let a caller
     * pass three values that never met, and the mismatch would be a silent trust grant over someone
     * else's fingerprint — the exact class of bug V51 exists to have removed.
     */
    static UserDeviceTrust of(UserDevice device, UUID orgId, UUID grantedByPersonId, Instant grantedAt) {
        UserDeviceTrust trust = new UserDeviceTrust();
        trust.id = new Key(device.getId(), orgId);
        trust.personId = device.getPersonId();
        trust.fingerprint = device.getFingerprint();
        trust.grantedByPersonId = grantedByPersonId;
        trust.grantedAt = grantedAt;
        return trust;
    }

    UUID getDeviceId() {
        return id.deviceId;
    }

    UUID getOrgId() {
        return id.orgId;
    }

    Instant getGrantedAt() {
        return grantedAt;
    }

    UUID getGrantedByPersonId() {
        return grantedByPersonId;
    }

    @Embeddable
    static class Key implements Serializable {

        @Column(name = "device_id", nullable = false)
        private UUID deviceId;

        @Column(name = "org_id", nullable = false)
        private UUID orgId;

        protected Key() {
        }

        Key(UUID deviceId, UUID orgId) {
            this.deviceId = deviceId;
            this.orgId = orgId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && Objects.equals(deviceId, key.deviceId) && Objects.equals(orgId, key.orgId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceId, orgId);
        }
    }
}
