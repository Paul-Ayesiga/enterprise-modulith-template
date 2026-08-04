package ug.co.smsone.access.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import ug.co.smsone.shared.persistence.SoftDeletableEntity;

/** One org's security policy. Every field TIGHTENS access; absent = the platform default applies. */
@Entity
@Table(name = "org_security_policy")
@SQLDelete(sql = "update org_security_policy set deleted_at = now(), version = version + 1 where id = ? and version = ?")
@SQLRestriction("deleted_at is null")
class OrgSecurityPolicy extends SoftDeletableEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "ip_allowlist", columnDefinition = "text")
    private String ipAllowlist;

    @Column(name = "require_trusted_device", nullable = false)
    private boolean requireTrustedDevice;

    @Column(name = "session_max_age_seconds")
    private Long sessionMaxAgeSeconds;

    @Column(name = "require_mfa", nullable = false)
    private boolean requireMfa;

    protected OrgSecurityPolicy() {
        // JPA
    }

    static OrgSecurityPolicy of(UUID orgId) {
        OrgSecurityPolicy policy = new OrgSecurityPolicy();
        policy.orgId = orgId;
        return policy;
    }

    void update(String ipAllowlist, boolean requireTrustedDevice, Long sessionMaxAgeSeconds,
            boolean requireMfa) {
        this.ipAllowlist = ipAllowlist;
        this.requireTrustedDevice = requireTrustedDevice;
        this.sessionMaxAgeSeconds = sessionMaxAgeSeconds;
        this.requireMfa = requireMfa;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getIpAllowlist() {
        return ipAllowlist;
    }

    boolean isRequireTrustedDevice() {
        return requireTrustedDevice;
    }

    Long getSessionMaxAgeSeconds() {
        return sessionMaxAgeSeconds;
    }

    boolean isRequireMfa() {
        return requireMfa;
    }
}
